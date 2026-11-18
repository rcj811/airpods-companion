package com.rcj811.podscompanion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.SystemClock
import android.util.Log

/**
 * Scans for nearby AirPods broadcasts. Because many people around you may wear AirPods,
 * only strong signals are accepted and, when the connected model is known, only that model.
 */
class BleScanner(
    private val context: Context,
    private val onResult: (AppleProximityParser.Result, Int) -> Unit,
) {
    private var scanning = false
    private var fast = false
    var expectedModelId: Int? = null

    var onDiag: (com.rcj811.podscompanion.ScanDiag.() -> com.rcj811.podscompanion.ScanDiag) -> Unit = {}
    /** When true (AirPods are connected to us) accept weaker signals, e.g. pods in ears + phone in pocket. */
    var relaxed = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
            onDiag { copy(scanning = false, scanError = "掃描失敗（錯誤碼 $errorCode）") }
        }
    }

    private fun handle(r: ScanResult) {
        val data = r.scanRecord?.getManufacturerSpecificData(AppleProximityParser.APPLE_COMPANY_ID) ?: return
        val parsed = AppleProximityParser.parse(data) ?: return
        onDiag { copy(appleSeen = appleSeen + 1, bestRssi = maxOf(bestRssi ?: -127, r.rssi)) }
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

    @SuppressLint("MissingPermission")
    fun start(lowLatency: Boolean) {
        if (scanning && fast == lowLatency) return
        stop()
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            onDiag { copy(scanning = false, scanError = "藍牙未開啟") }
            return
        }
        val filter = ScanFilter.Builder()
            .setManufacturerData(
                AppleProximityParser.APPLE_COMPANY_ID,
                byteArrayOf(0x07, 0x19),
                byteArrayOf(0xFF.toByte(), 0xFF.toByte()),
            ).build()
        val settings = ScanSettings.Builder()
            .setScanMode(if (lowLatency) ScanSettings.SCAN_MODE_LOW_LATENCY else ScanSettings.SCAN_MODE_LOW_POWER)
            .setReportDelay(0)
            .build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
            fast = lowLatency
            onDiag { copy(scanning = true, scanError = null) }
            startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e)
            onDiag { copy(scanning = false, scanError = "無法開始掃描：${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner?.stopScan(callback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan", e)
        }
        scanning = false
        onDiag { copy(scanning = false) }
    }

    private var startedAt = 0L

    companion object {
        private const val TAG = "BleScanner"
        private const val MIN_RSSI = -70
        private const val RELAXED_RSSI = -90
    }
}
