package com.rcj811.podscompanion.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rcj811.podscompanion.AacpStatus
import com.rcj811.podscompanion.ListeningMode
import com.rcj811.podscompanion.Pod
import com.rcj811.podscompanion.PodsModel
import com.rcj811.podscompanion.PodsRepository
import com.rcj811.podscompanion.PodsState
import com.rcj811.podscompanion.Prefs
import com.rcj811.podscompanion.R
import com.rcj811.podscompanion.aacp.AacpClient
import com.rcj811.podscompanion.ble.AppleProximityParser
import com.rcj811.podscompanion.ble.BleScanner
import com.rcj811.podscompanion.eq.EqEngine
import com.rcj811.podscompanion.popup.BatteryPopup
import com.rcj811.podscompanion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Long-running service:
 *  - watches for AirPods connecting (like iPad/iPhone) and shows the battery popup
 *  - keeps a notification with live battery levels
 *  - reads battery from BLE broadcasts (all phones) and from AACP (newer Android)
 *  - hosts the EQ effect
 */
class PodsService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var scanner: BleScanner
    private lateinit var aacp: AacpClient
    private lateinit var popup: BatteryPopup
    private var fastScanJob: Job? = null
    private var lastPopupAt = 0L
    private var lowAlerted = false
    private var lastBleAt = 0L
    private var lastLidOpen: Boolean? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        createChannels()
        if (!startAsForeground(buildNotification(PodsRepository.state.value))) {
            stopSelf()
            return
        }
        started = true
        popup = BatteryPopup(this)
        scanner = BleScanner(this, ::onBle)
        scanner.onDiag = { f -> scope.launch { PodsRepository.update { it.copy(diag = it.diag.f()) } } }
        aacp = AacpClient(this, scope, aacpListener)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "." + BluetoothAssignedNumbers.APPLE)
        }
        ContextCompat.registerReceiver(this, btReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        scope.launch {
            PodsRepository.state
                .map { Triple(it.connected, notificationKey(it), it.listeningMode) }
                .distinctUntilChanged()
                .collectLatest {
                    val s = PodsRepository.state.value
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIF_ID, buildNotification(s))
                    popup.update(s)
                    checkLowBattery(s)
                }
        }

        EqEngine.attachGlobal()
        checkAlreadyConnected()

        // Auto update: check now and then every 3 hours while the service runs.
        scope.launch {
            while (true) {
                if (Prefs.autoUpdate) com.rcj811.podscompanion.update.Updater.check(this@PodsService)
                delay(3 * 60 * 60 * 1000L)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_SHOW_POPUP -> showPopup(force = true)
            ACTION_SET_MODE -> {
                val code = intent.getIntExtra(EXTRA_MODE, -1)
                if (code > 0) setListeningMode(code)
            }
            ACTION_SET_CA -> aacp.setConversationalAwareness(intent.getBooleanExtra(EXTRA_ENABLED, true))
                .also { if (it) PodsRepository.update { s -> s.copy(conversationalAwareness = intent.getBooleanExtra(EXTRA_ENABLED, true)) } }
            ACTION_CYCLE_MODE -> cycleMode()
            ACTION_REFRESH_EQ -> EqEngine.refresh()
            ACTION_HFP_BATTERY -> {
                val level = intent.getIntExtra(EXTRA_LEVEL, -1)
                if (level in 0..100 && PodsRepository.state.value.connected) {
                    PodsRepository.update { it.copy(systemLevel = level, diag = it.diag.copy(hfpEvents = it.diag.hfpEvents + 1)) }
                }
            }
            ACTION_DEVICE_CONNECTED -> checkAlreadyConnected()
            ACTION_RECONNECT_AACP -> PodsRepository.state.value.deviceAddress?.let { reconnectAacp(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (!started) {
            scope.cancel()
            super.onDestroy()
            return
        }
        runCatching { unregisterReceiver(btReceiver) }
        scanner.stop()
        aacp.disconnect()
        popup.dismiss()
        EqEngine.releaseAll()
        scope.cancel()
        super.onDestroy()
    }

    // ---- connection handling ---------------------------------------------------------------

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = deviceFrom(intent) ?: return
            when (intent.action) {
                BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> handleHfpEvent(intent, device)
                ACTION_BATTERY_LEVEL_CHANGED -> if (device.address == PodsRepository.state.value.deviceAddress) {
                    val level = intent.getIntExtra(EXTRA_BATTERY_LEVEL, -1)
                    if (level in 0..100) PodsRepository.update { it.copy(systemLevel = level) }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> if (isApplePods(device)) onPodsConnected(device)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (device.address == PodsRepository.state.value.deviceAddress) onPodsDisconnected()
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val st = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    if (st == BluetoothProfile.STATE_CONNECTED && isApplePods(device) && !PodsRepository.state.value.connected) {
                        onPodsConnected(device)
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkAlreadyConnected() {
        if (!hasConnectPermission()) return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val dev = proxy.connectedDevices.firstOrNull { isApplePods(it) }
                adapter.closeProfileProxy(profile, proxy)
                if (dev != null && PodsRepository.state.value.deviceAddress != dev.address) onPodsConnected(dev)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    @SuppressLint("MissingPermission")
    private fun onPodsConnected(device: BluetoothDevice) {
        val name = runCatching { device.name }.getOrNull()
        // Show the last known levels right away (like iPad does), refreshed as soon as new data arrives.
        val cached = Prefs.loadBattery(device.address)
        PodsRepository.update {
            it.copy(
                model = cached?.model ?: it.model,
                left = cached?.left?.copy(charging = false) ?: it.left,
                right = cached?.right?.copy(charging = false) ?: it.right,
                case = cached?.case ?: it.case,
                single = cached?.single ?: it.single,
                lastUpdate = cached?.lastUpdate ?: 0L,
                batterySource = if (cached != null) "上次記錄" else "",
                connected = true, deviceName = name, deviceAddress = device.address,
                systemLevel = systemBatteryLevel(device), diag = com.rcj811.podscompanion.ScanDiag(),
            )
        }
        scanner.relaxed = true
        lowAlerted = false
        boostScan()
        showPopup(force = false)
        reconnectAacp(device.address)
    }

    @SuppressLint("MissingPermission")
    private fun reconnectAacp(address: String) {
        if (!hasConnectPermission()) return
        val device = getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(address) ?: return
        PodsRepository.update { it.copy(aacp = AacpStatus.CONNECTING, aacpError = null) }
        scope.launch {
            delay(1500) // give A2DP a moment to settle
            aacp.connect(device)
        }
    }

    private fun onPodsDisconnected() {
        aacp.disconnect()
        popup.dismiss()
        scanner.expectedModelId = null
        scanner.relaxed = false
        lastLidOpen = null
        fastScanJob?.cancel()
        scanner.stop()
        PodsRepository.update { PodsState() }
    }

    private val aacpListener = object : AacpClient.Listener {
        override fun onConnected() {
            scope.launch { PodsRepository.update { it.copy(aacp = AacpStatus.CONNECTED, aacpError = null) } }
        }

        override fun onDisconnected(error: String?) {
            scope.launch {
                PodsRepository.update {
                    if (error != null) it.copy(aacp = AacpStatus.UNSUPPORTED, aacpError = error)
                    else it.copy(aacp = AacpStatus.IDLE, listeningMode = null)
                }
            }
        }

        override fun onBattery(left: Pod?, right: Pod?, case: Pod?, single: Pod?) {
            scope.launch {
                PodsRepository.update {
                    it.copy(
                        left = left?.copy(inEar = it.left.inEar) ?: it.left,
                        right = right?.copy(inEar = it.right.inEar) ?: it.right,
                        case = case ?: it.case,
                        single = single ?: it.single,
                        batterySource = "AACP（精確 1%）",
                        lastUpdate = System.currentTimeMillis(),
                    )
                }
            }
        }

        override fun onListeningMode(code: Int) {
            scope.launch { PodsRepository.update { it.copy(listeningMode = ListeningMode.from(code)) } }
        }

        override fun onConversationalAwareness(enabled: Boolean) {
            scope.launch { PodsRepository.update { it.copy(conversationalAwareness = enabled) } }
        }
    }

    // ---- BLE battery ---------------------------------------------------------------------

    private fun onBle(r: AppleProximityParser.Result, rssi: Int) {
        lastBleAt = SystemClock.elapsedRealtime()
        val s = PodsRepository.state.value
        // When not connected, only use broadcasts to detect "case opened" nearby; otherwise ignore.
        if (!s.connected) {
            if (r.lidOpen == true && rssi > -55) {
                // Case opened right next to the phone, AirPods will usually connect in a moment.
                lastLidOpen = true
            }
            return
        }
        // Lock onto the model of the first strong broadcast so other people's AirPods are ignored.
        if (scanner.expectedModelId == null && r.model != PodsModel.UNKNOWN && rssi > -72) scanner.expectedModelId = r.modelId
        if (scanner.expectedModelId == null && rssi <= -72) return
        val aacpBattery = s.aacp == AacpStatus.CONNECTED && s.batterySource.startsWith("AACP")
        PodsRepository.update {
            it.copy(
                model = if (r.model != PodsModel.UNKNOWN) r.model else it.model,
                left = if (aacpBattery) it.left.copy(inEar = r.left.inEar) else r.left.takeIf { p -> p.known } ?: it.left,
                right = if (aacpBattery) it.right.copy(inEar = r.right.inEar) else r.right.takeIf { p -> p.known } ?: it.right,
                case = if (aacpBattery) it.case else r.case.takeIf { p -> p.known } ?: it.case,
                single = if (aacpBattery) it.single else r.single.takeIf { p -> p.known } ?: it.single,
                lidOpen = r.lidOpen ?: it.lidOpen,
                batterySource = if (aacpBattery) it.batterySource else "BLE 廣播（10% 精度）",
                lastUpdate = System.currentTimeMillis(),
            )
        }
        PodsRepository.state.value.deviceAddress?.let { Prefs.saveBattery(it, PodsRepository.state.value) }
        // Case lid opened again while connected → show the popup like on iPhone/iPad.
        if (r.lidOpen == true && lastLidOpen == false) showPopup(force = false)
        if (r.lidOpen != null) lastLidOpen = r.lidOpen
    }

    private fun boostScan() {
        if (!hasScanPermission()) {
            PodsRepository.update { it.copy(diag = it.diag.copy(scanning = false, scanError = "缺少「附近的裝置」權限")) }
            return
        }
        scanner.start(lowLatency = true)
        fastScanJob?.cancel()
        fastScanJob = scope.launch {
            delay(30_000)
            // Keep a slow scan while connected so battery stays up to date.
            if (PodsRepository.state.value.connected) scanner.start(lowLatency = false) else scanner.stop()
        }
    }

    // ---- popup / notification --------------------------------------------------------------

    private fun showPopup(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && (!Prefs.popupEnabled || now - lastPopupAt < 8_000)) return
        lastPopupAt = now
        boostScan()
        popup.show(PodsRepository.state.value, Prefs.popupSeconds)
    }

    private fun setListeningMode(code: Int) {
        if (aacp.setListeningMode(code)) {
            PodsRepository.update { it.copy(listeningMode = ListeningMode.from(code)) }
        }
    }

    private fun cycleMode() {
        val s = PodsRepository.state.value
        val order = listOf(ListeningMode.ANC, ListeningMode.TRANSPARENCY, ListeningMode.ADAPTIVE, ListeningMode.OFF)
        val idx = order.indexOf(s.listeningMode)
        setListeningMode(order[(idx + 1).mod(order.size)].code)
    }

    private fun checkLowBattery(s: PodsState) {
        if (!Prefs.lowBatteryAlert || !s.connected || lowAlerted) return
        val levels = listOf(s.left, s.right, s.single).filter { it.known && !it.charging }.map { it.level }
        val low = levels.minOrNull() ?: return
        if (low <= 20) {
            lowAlerted = true
            val n = NotificationCompat.Builder(this, CHANNEL_ALERT)
                .setSmallIcon(R.drawable.ic_pods)
                .setContentTitle("${s.model.label} 電量偏低")
                .setContentText("剩下 $low%，記得放回充電盒")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_ALERT_ID, n)
        }
    }

    private fun notificationKey(s: PodsState) =
        "${s.model}|${s.left}|${s.right}|${s.case}|${s.single}|${s.aacp}|${s.systemLevel}"

    private fun buildNotification(s: PodsState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_pods)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_STATUS)

        if (!s.connected) {
            b.setContentTitle("等待 AirPods 連線").setContentText("連上後會自動跳出電量")
            return b.build()
        }
        val title = s.deviceName ?: s.model.label
        val text = batteryLine(s)
        b.setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text + (s.listeningMode?.let { "\n聲音模式：${it.label}" } ?: "")))
        b.addAction(0, "顯示電量", servicePending(ACTION_SHOW_POPUP, 1))
        if (s.aacp == AacpStatus.CONNECTED) b.addAction(0, "切換降噪", servicePending(ACTION_CYCLE_MODE, 2))
        return b.build()
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, PodsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "AirPods 電量", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, "低電量提醒", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun startAsForeground(n: Notification): Boolean {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * AirPods report their battery to the phone over the hands-free (HFP) link with Apple's
     * AT+IPHONEACCEV command. Android forwards it to apps as a public vendor-specific event,
     * which works even when the AirPods don't send BLE broadcasts.
     */
    @SuppressLint("MissingPermission")
    private fun handleHfpEvent(intent: Intent, device: BluetoothDevice) {
        val level = hfpBatteryLevel(intent) ?: return
        val s = PodsRepository.state.value
        if (!s.connected) {
            if (isApplePods(device)) onPodsConnected(device) else return
        } else if (s.deviceAddress != device.address) return
        PodsRepository.update { it.copy(systemLevel = level, diag = it.diag.copy(hfpEvents = it.diag.hfpEvents + 1)) }
    }

    /** Android's own headset battery (from AirPods' HFP report); hidden API, read via reflection. */
    @SuppressLint("MissingPermission")
    private fun systemBatteryLevel(device: BluetoothDevice): Int = runCatching {
        val v = BluetoothDevice::class.java.getMethod("getBatteryLevel").invoke(device) as Int
        if (v in 0..100) v else -1
    }.getOrDefault(-1)

    @SuppressLint("MissingPermission")
    private fun isApplePods(device: BluetoothDevice): Boolean {
        if (!hasConnectPermission()) return false
        val uuids = runCatching { device.uuids }.getOrNull()
        if (uuids?.any { it == AacpClient.AAP_UUID } == true) return true
        val name = runCatching { device.name }.getOrNull() ?: return false
        return name.contains("AirPods", true) || name.contains("Beats", true) || name.contains("Powerbeats", true)
    }

    companion object {
        const val CHANNEL_STATUS = "status"
        const val CHANNEL_ALERT = "alert"
        const val NOTIF_ID = 1
        const val NOTIF_ALERT_ID = 2

        const val ACTION_SHOW_POPUP = "show_popup"
        const val ACTION_SET_MODE = "set_mode"
        const val ACTION_SET_CA = "set_ca"
        const val ACTION_CYCLE_MODE = "cycle_mode"
        const val ACTION_REFRESH_EQ = "refresh_eq"
        const val ACTION_DEVICE_CONNECTED = "device_connected"
        const val ACTION_RECONNECT_AACP = "reconnect_aacp"
        const val EXTRA_MODE = "mode"
        const val EXTRA_ENABLED = "enabled"
        const val ACTION_HFP_BATTERY = "hfp_battery"
        const val EXTRA_LEVEL = "level"
        /** Parses "+IPHONEACCEV" args: [count, key, value, key, value…]; key 1 = battery 0-9. */
        fun hfpBatteryLevel(intent: Intent): Int? {
            if (intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD) != "+IPHONEACCEV") return null
            @Suppress("DEPRECATION")
            val args = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS) as? Array<*> ?: return null
            return hfpBatteryLevel(args.map { it?.toString()?.trim()?.toIntOrNull() })
        }

        fun hfpBatteryLevel(args: List<Int?>): Int? {
            val count = args.firstOrNull() ?: return null
            for (i in 0 until count) {
                val key = args.getOrNull(1 + i * 2)
                val value = args.getOrNull(2 + i * 2) ?: continue
                if (key == 1 && value in 0..9) return (value + 1) * 10
            }
            return null
        }

        const val ACTION_BATTERY_LEVEL_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

        fun batteryLine(s: PodsState): String {
            fun fmt(label: String, p: Pod) = if (p.known) "$label ${p.level}%${if (p.charging) "⚡" else ""}" else null
            val parts = if (s.model.overEar) listOfNotNull(fmt("電量", s.single))
            else listOfNotNull(fmt("左", s.left), fmt("右", s.right), fmt("盒", s.case))
            if (parts.isEmpty() && s.systemLevel in 0..100) return "耳機電量 ${s.systemLevel}%"
            return if (parts.isEmpty()) "讀取電量中…" else parts.joinToString("  ·  ")
        }

        fun canRun(context: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            // Android 14+ refuses a connectedDevice foreground service without Bluetooth permission.
            if (!canRun(context)) return
            val i = Intent(context, PodsService::class.java).setAction(action).apply(extras)
            try {
                ContextCompat.startForegroundService(context, i)
            } catch (e: Exception) {
                // Background start not allowed right now; ignore.
            }
        }

        @Suppress("DEPRECATION")
        fun deviceFrom(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
    }
}
