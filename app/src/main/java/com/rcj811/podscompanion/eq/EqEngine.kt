package com.rcj811.podscompanion.eq

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.rcj811.podscompanion.Prefs

/**
 * Phone-side EQ, applied before audio is sent to the headphones (works with any headphones).
 *
 * Two attach points:
 *  - global output mix (session 0): works on many phones without any player support
 *  - per-app audio sessions announced by players via OPEN_AUDIO_EFFECT_CONTROL_SESSION
 */
object EqEngine {
    private const val TAG = "EqEngine"
    private const val PRIORITY = Int.MAX_VALUE
    private val effects = mutableMapOf<Int, DynamicsProcessing>()

    var globalWorks: Boolean? = null
        private set

    @Synchronized
    fun attachGlobal() = attach(0)

    @Synchronized
    fun attach(session: Int) {
        if (effects.containsKey(session)) {
            apply(effects.getValue(session))
            return
        }
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                2,
                true, EqData.BANDS.size,
                false, 0,
                false, 0,
                true,
            ).build()
            val dp = DynamicsProcessing(PRIORITY, session, cfg)
            effects[session] = dp
            apply(dp)
            if (session == 0) globalWorks = true
        } catch (e: Throwable) {
            Log.w(TAG, "attach $session failed", e)
            if (session == 0) globalWorks = false
        }
    }

    @Synchronized
    fun detach(session: Int) {
        effects.remove(session)?.let { runCatching { it.release() } }
    }

    @Synchronized
    fun refresh() {
        effects.values.forEach { apply(it) }
    }

    @Synchronized
    fun releaseAll() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
    }

    private fun apply(dp: DynamicsProcessing) {
        try {
            val on = Prefs.eqEnabled
            val gains = Prefs.eqGains
            val maxGain = gains.maxOrNull()?.coerceAtLeast(0f) ?: 0f
            val eq = DynamicsProcessing.Eq(true, on, EqData.BANDS.size)
            for (i in EqData.BANDS.indices) {
                // A band in DynamicsProcessing covers everything up to its cutoff frequency,
                // so use the geometric midpoint to the next band as cutoff.
                val cutoff = if (i < EqData.BANDS.size - 1) {
                    kotlin.math.sqrt(EqData.BANDS[i].toFloat() * EqData.BANDS[i + 1])
                } else 20000f
                eq.setBand(i, DynamicsProcessing.EqBand(true, cutoff, gains[i]))
            }
            dp.setPreEqAllChannelsTo(eq)
            // Pre-amp to avoid clipping when boosting.
            dp.setInputGainAllChannelsTo(if (on) -maxGain else 0f)
            dp.setEnabled(on)
        } catch (e: Throwable) {
            Log.w(TAG, "apply failed", e)
        }
    }
}
