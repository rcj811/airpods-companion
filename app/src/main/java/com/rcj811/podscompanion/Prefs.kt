package com.rcj811.podscompanion

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("pods", Context.MODE_PRIVATE)
    }

    var popupEnabled: Boolean
        get() = sp.getBoolean("popup", true)
        set(v) = sp.edit().putBoolean("popup", v).apply()

    var popupSeconds: Int
        get() = sp.getInt("popup_sec", 6)
        set(v) = sp.edit().putInt("popup_sec", v).apply()

    var lowBatteryAlert: Boolean
        get() = sp.getBoolean("low_alert", true)
        set(v) = sp.edit().putBoolean("low_alert", v).apply()

    var eqEnabled: Boolean
        get() = sp.getBoolean("eq_on", false)
        set(v) = sp.edit().putBoolean("eq_on", v).apply()

    /** Name of the current preset, shown in the UI. */
    var eqPresetName: String
        get() = sp.getString("eq_name", "自訂") ?: "自訂"
        set(v) = sp.edit().putString("eq_name", v).apply()

    var eqGains: FloatArray
        get() {
            val s = sp.getString("eq_gains", null) ?: return FloatArray(10)
            val parts = s.split(",").mapNotNull { it.toFloatOrNull() }
            return if (parts.size == 10) parts.toFloatArray() else FloatArray(10)
        }
        set(v) = sp.edit().putString("eq_gains", v.joinToString(",")).apply()

    /** Which headphone model the user owns (key from EqData), used for correction/mimic presets. */
    var ownModelKey: String?
        get() = sp.getString("own_model", null)
        set(v) = sp.edit().putString("own_model", v).apply()
}
