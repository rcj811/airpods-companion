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

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            scanning = false
        }
    }

    private fun handle(r: ScanResult) {
        if (r.rssi < MIN_RSSI) return
        val data = r.scanRecord?.getManufacturerSpecificData(AppleProximityParser.APPLE_COMPANY_ID) ?: return
        val parsed = AppleProximityParser.parse(data) ?: return
        val expected = expectedModelId
        if (expected != null && parsed.modelId != expected) return
        onResult(parsed, r.rssi)
    }

    @SuppressLint("MissingPermission")
    fun start(lowLatency: Boolean) {
        if (scanning && fast == lowLatency) return
        stop()
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return
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
            startedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(TAG, "startScan", e)
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
    }

    private var startedAt = 0L

    companion object {
        private const val TAG = "BleScanner"
        private const val MIN_RSSI = -70
    }
}
