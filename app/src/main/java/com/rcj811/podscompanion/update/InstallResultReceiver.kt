package com.rcj811.podscompanion.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.onInstallResult(context, intent)
}
