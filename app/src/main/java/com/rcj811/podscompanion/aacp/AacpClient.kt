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
                send(HANDSHAKE)
                delay(200)
                send(data(byteArrayOf(0x4D, 0x00, 0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0)))
                delay(200)
                send(data(byteArrayOf(0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())))
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

    fun setListeningMode(code: Int): Boolean = sendControl(ID_LISTENING_MODE, code)

    fun setConversationalAwareness(enabled: Boolean): Boolean =
        sendControl(ID_CONVERSATION_DETECT, if (enabled) 1 else 2)

    private fun sendControl(id: Int, value: Int): Boolean {
        if (!isConnected) return false
        val packet = data(byteArrayOf(0x09, 0x00, id.toByte(), value.toByte(), 0, 0, 0))
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
        if (p.size < 6) return
        if (p[0].toInt() != 0x04 || p[1].toInt() != 0x00 || p[2].toInt() != 0x04 || p[3].toInt() != 0x00) return
        when (p[4].toInt() and 0xFF) {
            0x04 -> parseBattery(p)
            0x09 -> if (p.size >= 8) {
                val id = p[6].toInt() and 0xFF
                val value = p[7].toInt() and 0xFF
                when (id) {
                    ID_LISTENING_MODE -> listener.onListeningMode(value)
                    ID_CONVERSATION_DETECT -> listener.onConversationalAwareness(value == 1)
                }
            }
        }
    }

    /** 04 00 04 00 04 00 [count] then count × [component, 01, level, status, 01] */
    private fun parseBattery(p: ByteArray) {
        if (p.size < 7) return
        val count = p[6].toInt() and 0xFF
        var left: Pod? = null
        var right: Pod? = null
        var case: Pod? = null
        var single: Pod? = null
        for (i in 0 until count) {
            val o = 7 + i * 5
            if (o + 3 >= p.size) break
            val component = p[o].toInt() and 0xFF
            val level = p[o + 2].toInt() and 0xFF
            val status = p[o + 3].toInt() and 0xFF
            if (status == STATUS_DISCONNECTED) continue
            val pod = Pod(level.coerceIn(0, 100), charging = status == STATUS_CHARGING)
            when (component) {
                COMP_LEFT -> left = pod
                COMP_RIGHT -> right = pod
                COMP_CASE -> case = pod
                COMP_SINGLE -> single = pod
            }
        }
        listener.onBattery(left, right, case, single)
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

        private const val ID_LISTENING_MODE = 0x0D
        private const val ID_CONVERSATION_DETECT = 0x28

        private const val COMP_SINGLE = 0x01
        private const val COMP_RIGHT = 0x02
        private const val COMP_LEFT = 0x04
        private const val COMP_CASE = 0x08
        private const val STATUS_CHARGING = 1
        private const val STATUS_DISCONNECTED = 4

        private val HANDSHAKE = byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
        private fun data(payload: ByteArray) = byteArrayOf(4, 0, 4, 0) + payload
    }
}
