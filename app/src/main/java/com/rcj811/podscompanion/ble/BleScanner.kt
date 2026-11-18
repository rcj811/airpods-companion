package com.rcj811.podscompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.rcj811.podscompanion.ScanDiag

/**
 * Scans for nearby AirPods broadcasts.
 *
 * Some phones/tablets (notably Samsung) drop results when the hardware filter contains
 * manufacturer *data*, so we first filter by Apple's company id only, and if nothing at all
 * arrives within a few seconds we fall back to an unfiltered scan (works while the screen is on).
 * Android also silently blocks apps that start scans more than 5 times in 30 s, so starts are
 * rate-limited here.
 */
class BleScanner(
    private val context: Context,
    private val onResult: (AppleProximityParser.Result, Int) -> Unit,
) {
    private var scanning = false
    private var fast = false
    private var unfiltered = false
    private var anyResultSinceStart = false
    private val startTimes = ArrayDeque<Long>()
    private val handler = Handler(Looper.getMainLooper())

    var expectedModelId: Int? = null
    var onDiag: (ScanDiag.() -> ScanDiag) -> Unit = {}
    /** When true (AirPods are connected to us) accept weaker signals, e.g. pods in ears + phone in pocket. */
    var relaxed = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
            val why = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "已在掃描"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "系統拒絕（掃描太頻繁或藍牙忙碌）"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "不支援"
                else -> "錯誤碼 $errorCode"
            }
            onDiag { copy(scanning = false, scanError = "掃描失敗：$why") }
        }
    }

    private fun handle(r: ScanResult) {
        anyResultSinceStart = true
        val data = r.scanRecord?.getManufacturerSpecificData(AppleProximityParser.APPLE_COMPANY_ID)
        if (data == null) {
            onDiag { copy(totalSeen = totalSeen + 1) }
            return
        }
        val parsed = AppleProximityParser.parse(data)
        if (parsed == null) {
            // Apple device, but not an AirPods status message (iPhone, Watch, Mac, other message types…)
            onDiag {
                copy(
                    totalSeen = totalSeen + 1, appleOther = appleOther + 1,
                    lastAppleType = if (data.size >= 2) "%02X/%02X".format(data[0], data[1]) else lastAppleType,
                )
            }
            return
        }
        onDiag { copy(totalSeen = totalSeen + 1, appleSeen = appleSeen + 1, bestRssi = maxOf(bestRssi ?: -127, r.rssi)) }
        if (r.rssi < (if (relaxed) RELAXED_RSSI else MIN_RSSI)) {
            onDiag { copy(rejectedWeak = rejectedWeak + 1) }
            return
        }
        val expected = expectedModelId
        if (expected != null && parsed.modelId != expected) {
            onDiag { copy(rejectedModel = rejectedModel + 1) }
            return
        }
        onResult(parsed, r.rssi)
    }

    fun start(lowLatency: Boolean) {
        if (scanning && fast == lowLatency) return
        doStart(lowLatency, unfiltered = false)
    }

    @SuppressLint("MissingPermission")
    private fun doStart(lowLatency: Boolean, unfiltered: Boolean) {
        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > 30_000) startTimes.removeFirst()
        if (startTimes.size >= 4) {
            // Too many starts: retry once the window frees up instead of being silently blocked.
            handler.postDelayed({ doStart(lowLatency, unfiltered) }, 30_000 - (now - startTimes.first()) + 500)
            return
        }
        stopInternal()
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            onDiag { copy(scanning = false, scanError = "藍牙未開啟") }
            return
        }
        val filters = if (unfiltered) emptyList() else listOf(
            ScanFilter.Builder().setManufacturerData(AppleProximityParser.APPLE_COMPANY_ID, ByteArray(0)).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(filters, settings, callback)
            startTimes.addLast(now)
            scanning = true
            fast = lowLatency
            this.unfiltered = unfiltered
            anyResultSinceStart = false
            onDiag { copy(scanning = true, scanError = null, unfiltered = unfiltered) }
            if (!unfiltered) {
                handler.removeCallbacks(fallback)
                handler.postDelayed(fallback, 8_000)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e)
            onDiag { copy(scanning = false, scanError = "無法開始掃描：${e.message}") }
        }
    }

    private val fallback = Runnable {
        if (scanning && !unfiltered && !anyResultSinceStart) doStart(fast, unfiltered = true)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        stopInternal()
        onDiag { copy(scanning = false) }
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        if (!scanning) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
        scanning = false
    }

    companion object {
        private const val TAG = "BleScanner"
        private const val MIN_RSSI = -70
        private const val RELAXED_RSSI = -90
    }
}
