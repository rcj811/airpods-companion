package com.rcj811.podscompanion.eq

/**
 * Headphone correction curves derived from AutoEq (https://github.com/jaakkopasanen/AutoEq, MIT License).
 * Source: Rtings measurements on a Brüel & Kjær 5128 rig, GraphicEQ results, averaged to 10 bands
 * and normalised to 0 dB at 1 kHz.
 *
 * correction(X) = the EQ that makes headphone X sound neutral (Harman-style target).
 * To make headphone A sound like headphone B: correction(A) - correction(B).
 */
object EqData {
    val BANDS = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    val BAND_LABELS = arrayOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    data class Correction(val key: String, val label: String, val gains: FloatArray)

    val corrections: List<Correction> = listOf(
        Correction("airpods3", "AirPods 3", floatArrayOf(6.1f, 2.0f, 0.9f, -0.2f, -0.1f, 0.0f, -2.5f, 1.9f, 2.6f, 5.7f)),
        Correction("airpods4anc", "AirPods 4 降噪版", floatArrayOf(3.3f, 0.8f, -0.3f, -0.3f, 0.6f, 0.0f, 2.1f, 7.9f, 2.6f, 0.7f)),
        Correction("airpods4", "AirPods 4", floatArrayOf(3.4f, 1.2f, -0.4f, -0.2f, 0.8f, 0.0f, 2.4f, 8.3f, 3.6f, 1.2f)),
        Correction("airpodspro2", "AirPods Pro 2", floatArrayOf(-2.0f, -0.9f, -1.0f, -1.8f, -2.0f, 0.0f, 1.8f, 2.7f, 0.3f, 3.3f)),
        Correction("beatssolobuds", "Beats Solo Buds", floatArrayOf(0.4f, 1.0f, 3.4f, 4.1f, 4.9f, 0.0f, -2.7f, -0.2f, 4.1f, 7.6f)),
        Correction("boseqcue", "Bose QC Ultra Earbuds", floatArrayOf(-9.7f, -7.7f, -5.3f, -4.1f, -2.6f, 0.0f, -6.7f, -4.2f, -3.3f, 2.1f)),
        Correction("buds2pro", "Galaxy Buds2 Pro", floatArrayOf(-8.5f, -6.5f, -5.1f, -4.8f, -2.1f, 0.0f, -2.4f, -5.2f, -3.8f, -3.5f)),
        Correction("buds3pro", "Galaxy Buds3 Pro", floatArrayOf(-7.9f, -7.2f, -5.0f, -3.0f, 0.1f, 0.0f, 0.5f, 0.2f, -2.0f, -2.1f)),
        Correction("wf1000xm5", "Sony WF-1000XM5", floatArrayOf(-3.5f, -4.5f, -5.3f, -3.9f, 0.6f, 0.0f, -2.1f, -2.2f, -1.2f, 3.5f)),
        Correction("airpodsmax", "AirPods Max", floatArrayOf(-0.7f, -0.5f, -0.3f, -0.7f, 0.6f, 0.0f, 3.3f, 4.7f, 1.8f, 2.6f)),
        Correction("beatsstudiopro", "Beats Studio Pro", floatArrayOf(-2.4f, 2.2f, 0.6f, 3.7f, 2.2f, 0.0f, -1.8f, -0.5f, -3.4f, 2.0f)),
        Correction("boseqc45", "Bose QC45", floatArrayOf(-1.4f, -1.8f, -4.5f, -3.0f, -0.4f, 0.0f, -1.4f, -5.0f, -0.4f, -1.2f)),
        Correction("hd600", "Sennheiser HD 600", floatArrayOf(6.4f, 6.2f, 1.4f, -0.1f, 0.7f, 0.0f, -0.4f, 0.3f, 1.3f, 2.2f)),
        Correction("wh1000xm5", "Sony WH-1000XM5", floatArrayOf(-6.3f, -5.9f, -8.2f, -6.6f, -2.5f, 0.0f, -2.8f, -3.9f, -2.8f, 1.3f)),
    )

    fun correction(key: String?) = corrections.firstOrNull { it.key == key }

    data class Style(val name: String, val desc: String, val gains: FloatArray)

    val styles = listOf(
        Style("平衡", "原音，不做調整", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Style("重低音", "低頻加強，適合電子、嘻哈", floatArrayOf(6f, 5f, 3.5f, 1.5f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Style("人聲", "人聲更前、更清楚", floatArrayOf(-2f, -1.5f, -1f, 0.5f, 2f, 3f, 3f, 1.5f, 0f, -1f)),
        Style("Podcast", "說話清楚，減少轟鳴", floatArrayOf(-5f, -4f, -2f, 0f, 1.5f, 2.5f, 3f, 2f, 0f, -2f)),
        Style("清亮", "高頻提亮，細節更多", floatArrayOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 4f, 4.5f)),
        Style("溫暖", "柔和不刺耳，適合長時間聽", floatArrayOf(2f, 2f, 1.5f, 1f, 0f, 0f, -1f, -2f, -2.5f, -3f)),
        Style("跑步", "節奏感強，人聲清楚", floatArrayOf(5f, 4f, 2f, 0f, -0.5f, 0.5f, 2f, 2.5f, 1.5f, 0f)),
        Style("古典", "寬廣自然", floatArrayOf(2f, 1.5f, 0.5f, 0f, -0.5f, -0.5f, 0f, 1f, 2f, 2.5f)),
        Style("搖滾", "V 型，鼓點有力", floatArrayOf(4f, 3f, 1.5f, -0.5f, -1.5f, -1f, 0.5f, 2f, 3f, 3f)),
        Style("夜間", "降低刺激的高頻與低頻", floatArrayOf(-3f, -2f, -1f, 0f, 0.5f, 0.5f, 0f, -1.5f, -3f, -4f)),
    )

    /** EQ that makes [own] sound like [target]; clamped, top band damped (16 kHz measurements are noisy). */
    fun mimic(own: Correction, target: Correction): FloatArray {
        val out = FloatArray(BANDS.size) { i -> own.gains[i] - target.gains[i] }
        out[9] *= 0.5f
        out[8] *= 0.75f
        for (i in out.indices) out[i] = out[i].coerceIn(-10f, 10f)
        return out
    }

    /** EQ that makes [own] sound neutral. */
    fun neutral(own: Correction): FloatArray {
        val out = own.gains.copyOf()
        out[9] *= 0.5f
        for (i in out.indices) out[i] = out[i].coerceIn(-10f, 10f)
        return out
    }
}
