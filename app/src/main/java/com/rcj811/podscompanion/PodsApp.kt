package com.rcj811.podscompanion

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PodsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/") }
        }
        Prefs.init(this)
        DiagLog.init(this)
    }
}
