package com.rcj811.podscompanion.aacp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.rcj811.podscompanion.Pod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Talks Apple's accessory protocol (AAP/AACP) with AirPods over an L2CAP channel (PSM 0x1001).
 *
 * Needs a phone whose Bluetooth stack has Google's fix (Android 16 QPR3+, ColorOS/OxygenOS 16,
 * Android 17) or a rooted phone with a Bluetooth hook. On other phones connect() fails and the
 * app falls back to BLE-only battery reading.
 */
class AacpClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(error: String?)
        fun onBattery(left: Pod?, right: Pod?, case: Pod?, single: Pod?)
        fun onListeningMode(code: Int)
        fun onConversationalAwareness(enabled: Boolean)
    }

    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null

    val isConnected get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (isConnected) return
        readJob?.cancel()
        readJob = scope.launch(Dispatchers.IO) {
            val s = try {
                createSocket(device)
            } catch (e: Throwable) {
                listener.onDisconnected("無法建立 L2CAP 連線：${e.message}")
                return@launch
            }
            val watchdog = scope.launch { delay(6000); runCatching { s.close() } }
            try {
                s.connect()
                watchdog.cancel()
            } catch (e: Throwable) {
                watchdog.cancel()
                runCatching { s.close() }
                listener.onDisconnected("手機藍牙堆疊不支援（需 Android 16 QPR3 以上或 root）")
                return@launch
            }
            socket = s
            try {
                send(AacpPackets.HANDSHAKE)
                delay(200)
                send(AacpPackets.FEATURE_FLAGS)
                delay(200)
                send(AacpPackets.REQUEST_NOTIFICATIONS)
                listener.onConnected()
                readLoop(s)
            } catch (e: Throwable) {
                Log.w(TAG, "aacp session ended", e)
            } finally {
                runCatching { s.close() }
                socket = null
                listener.onDisconnected(null)
            }
        }
    }

    fun disconnect() {
        readJob?.cancel()
        runCatching { socket?.close() }
        socket = null
    }

    fun setListeningMode(code: Int): Boolean = sendControl(AacpPackets.ID_LISTENING_MODE, code)

    fun setConversationalAwareness(enabled: Boolean): Boolean =
        sendControl(AacpPackets.ID_CONVERSATION_DETECT, if (enabled) 1 else 2)

    private fun sendControl(id: Int, value: Int): Boolean {
        if (!isConnected) return false
        val packet = AacpPackets.control(id, value)
        scope.launch(Dispatchers.IO) { runCatching { send(packet) } }
        return true
    }

    @Synchronized
    private fun send(bytes: ByteArray) {
        val out = socket?.outputStream ?: throw IOException("not connected")
        out.write(bytes)
        out.flush()
    }

    private suspend fun readLoop(s: BluetoothSocket) {
        val buf = ByteArray(1024)
        val input = s.inputStream
        while (scope.isActive) {
            val n = input.read(buf)
            if (n < 0) break
            if (n > 0) handlePacket(buf.copyOf(n))
        }
    }

    private fun handlePacket(p: ByteArray) {
        when (val m = AacpPackets.decode(p)) {
            is AacpPackets.Message.Battery -> listener.onBattery(m.left, m.right, m.case, m.single)
            is AacpPackets.Message.Control -> when (m.id) {
                AacpPackets.ID_LISTENING_MODE -> listener.onListeningMode(m.value)
                AacpPackets.ID_CONVERSATION_DETECT -> listener.onConversationalAwareness(m.value == 1)
            }
            null -> Unit
        }
    }

    @SuppressLint("MissingPermission", "DiscouragedPrivateApi")
    private fun createSocket(device: BluetoothDevice): BluetoothSocket {
        // 1) Public API on newer Android (BluetoothSocketSettings, API 36.1+/37)
        runCatching {
            val settingsClass = Class.forName("android.bluetooth.BluetoothSocketSettings")
            val builderClass = Class.forName("android.bluetooth.BluetoothSocketSettings\$Builder")
            val b = builderClass.getDeclaredConstructor().newInstance()
            builderClass.getMethod("setSocketType", Int::class.javaPrimitiveType).invoke(b, TYPE_L2CAP)
            builderClass.getMethod("setL2capPsm", Int::class.javaPrimitiveType).invoke(b, PSM)
            builderClass.getMethod("setAuthenticationRequired", Boolean::class.javaPrimitiveType).invoke(b, false)
            builderClass.getMethod("setEncryptionRequired", Boolean::class.javaPrimitiveType).invoke(b, false)
            val settings = builderClass.getMethod("build").invoke(b)
            return BluetoothDevice::class.java.getMethod("createUsingSocketSettings", settingsClass)
                .invoke(device, settings) as BluetoothSocket
        }
        // 2) Hidden helper method
        runCatching {
            val m = BluetoothDevice::class.java.getDeclaredMethod("createInsecureL2capSocket", Int::class.javaPrimitiveType)
            m.isAccessible = true
            return m.invoke(device, PSM) as BluetoothSocket
        }
        // 3) Hidden constructors (signature differs between Android versions)
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val specs: List<Array<Any>> = listOf(
            arrayOf(adapter, device, TYPE_L2CAP, true, true, PSM, AAP_UUID),
            arrayOf(device, TYPE_L2CAP, true, true, PSM, AAP_UUID),
            arrayOf(device, TYPE_L2CAP, 1, true, true, PSM, AAP_UUID),
            arrayOf(TYPE_L2CAP, 1, true, true, device, PSM, AAP_UUID),
            arrayOf(TYPE_L2CAP, true, true, device, PSM, AAP_UUID),
        )
        var last: Throwable? = null
        for (args in specs) {
            try {
                val types = args.map { a ->
                    when (a) {
                        is Int -> Int::class.javaPrimitiveType!!
                        is Boolean -> Boolean::class.javaPrimitiveType!!
                        is BluetoothAdapter -> BluetoothAdapter::class.java
                        is BluetoothDevice -> BluetoothDevice::class.java
                        is ParcelUuid -> ParcelUuid::class.java
                        else -> a.javaClass
                    }
                }.toTypedArray()
                val c = BluetoothSocket::class.java.getDeclaredConstructor(*types)
                c.isAccessible = true
                return c.newInstance(*args) as BluetoothSocket
            } catch (e: Throwable) {
                last = e
            }
        }
        throw last ?: IOException("no socket constructor")
    }

    companion object {
        private const val TAG = "AacpClient"
        private const val PSM = 0x1001
        private const val TYPE_L2CAP = 3
        val AAP_UUID: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

    }
}
