package com.rcj811.podscompanion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the service after boot / update and when any Bluetooth device connects. */
class SystemReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PodsService.start(context)
            "android.bluetooth.device.action.ACL_CONNECTED" -> PodsService.start(context, PodsService.ACTION_DEVICE_CONNECTED)
        }
    }
}
