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

    /** Last battery reading per device: "model|l|lc|r|rc|c|cc|single|sc|time". */
    fun saveBattery(address: String, s: PodsState) {
        fun p(x: Pod) = "${x.level}|${if (x.charging) 1 else 0}"
        sp.edit().putString("bat_$address",
            "${s.model.name}|${p(s.left)}|${p(s.right)}|${p(s.case)}|${p(s.single)}|${s.lastUpdate}").apply()
    }

    fun loadBattery(address: String): PodsState? = runCatching {
        val v = sp.getString("bat_$address", null)?.split("|") ?: return null
        fun pod(i: Int) = Pod(v[i].toInt(), v[i + 1] == "1")
        PodsState(
            model = PodsModel.valueOf(v[0]),
            left = pod(1), right = pod(3), case = pod(5), single = pod(7),
            lastUpdate = v[9].toLong(),
        )
    }.getOrNull()

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    /** Which headphone model the user owns (key from EqData), used for correction/mimic presets. */
    var ownModelKey: String?
        get() = sp.getString("own_model", null)
        set(v) = sp.edit().putString("own_model", v).apply()
}
