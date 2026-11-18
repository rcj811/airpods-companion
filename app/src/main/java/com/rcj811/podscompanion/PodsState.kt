package com.rcj811.podscompanion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** -1 means unknown / not available. */
data class Pod(val level: Int = -1, val charging: Boolean = false, val inEar: Boolean = false) {
    val known get() = level in 0..100
}

enum class ListeningMode(val code: Int, val label: String) {
    OFF(1, "關閉"), ANC(2, "降噪"), TRANSPARENCY(3, "通透"), ADAPTIVE(4, "自適應");

    companion object {
        fun from(code: Int) = entries.firstOrNull { it.code == code }
    }
}

enum class AacpStatus { IDLE, CONNECTING, CONNECTED, UNSUPPORTED }

data class PodsState(
    val connected: Boolean = false,
    val deviceName: String? = null,
    val deviceAddress: String? = null,
    val model: PodsModel = PodsModel.UNKNOWN,
    val left: Pod = Pod(),
    val right: Pod = Pod(),
    val case: Pod = Pod(),
    /** Single battery for over-ear (AirPods Max / Beats headphones). */
    val single: Pod = Pod(),
    val lidOpen: Boolean = false,
    val batterySource: String = "",
    val lastUpdate: Long = 0L,
    val aacp: AacpStatus = AacpStatus.IDLE,
    val aacpError: String? = null,
    val listeningMode: ListeningMode? = null,
    val conversationalAwareness: Boolean? = null,
) {
    val hasBattery get() = left.known || right.known || case.known || single.known
}

enum class PodsModel(val label: String, val overEar: Boolean = false, val eqKey: String? = null) {
    UNKNOWN("AirPods"),
    AIRPODS_1("AirPods (第 1 代)"),
    AIRPODS_2("AirPods (第 2 代)"),
    AIRPODS_3("AirPods (第 3 代)", eqKey = "airpods3"),
    AIRPODS_4("AirPods 4", eqKey = "airpods4"),
    AIRPODS_4_ANC("AirPods 4 降噪版", eqKey = "airpods4anc"),
    AIRPODS_PRO("AirPods Pro"),
    AIRPODS_PRO_2("AirPods Pro 2", eqKey = "airpodspro2"),
    AIRPODS_PRO_2_USBC("AirPods Pro 2 (USB-C)", eqKey = "airpodspro2"),
    AIRPODS_PRO_3("AirPods Pro 3", eqKey = "airpodspro2"),
    AIRPODS_MAX("AirPods Max", overEar = true, eqKey = "airpodsmax"),
    AIRPODS_MAX_USBC("AirPods Max (USB-C)", overEar = true, eqKey = "airpodsmax"),
    POWERBEATS_PRO("Powerbeats Pro"),
    BEATS_STUDIO_BUDS("Beats Studio Buds"),
    BEATS_FIT_PRO("Beats Fit Pro"),
    BEATS_SOLO_BUDS("Beats Solo Buds", eqKey = "beatssolobuds"),
    BEATS_STUDIO_PRO("Beats Studio Pro", overEar = true, eqKey = "beatsstudiopro"),
    BEATS_OTHER("Beats");

    val supportsAnc: Boolean
        get() = this in setOf(
            AIRPODS_PRO, AIRPODS_PRO_2, AIRPODS_PRO_2_USBC, AIRPODS_PRO_3,
            AIRPODS_MAX, AIRPODS_MAX_USBC, AIRPODS_4_ANC, UNKNOWN
        )

    companion object {
        /** Model id from Apple BLE proximity-pairing message (bytes 3..4, big-endian). */
        fun fromBleId(id: Int): PodsModel = when (id) {
            0x0220 -> AIRPODS_1
            0x0F20 -> AIRPODS_2
            0x1320 -> AIRPODS_3
            0x1920 -> AIRPODS_4
            0x1B20 -> AIRPODS_4_ANC
            0x0E20 -> AIRPODS_PRO
            0x1420 -> AIRPODS_PRO_2
            0x2420 -> AIRPODS_PRO_2_USBC
            0x2720 -> AIRPODS_PRO_3
            0x0A20 -> AIRPODS_MAX
            0x1F20, 0x2D20 -> AIRPODS_MAX_USBC
            0x0B20 -> POWERBEATS_PRO
            0x1120 -> BEATS_STUDIO_BUDS
            0x1220 -> BEATS_FIT_PRO
            0x2620 -> BEATS_SOLO_BUDS
            0x1720 -> BEATS_STUDIO_PRO
            0x0320, 0x0520, 0x0620, 0x0920, 0x0C20, 0x0D20, 0x1020, 0x1520, 0x1620,
            0x1D20, 0x2520, 0x3820 -> BEATS_OTHER
            else -> UNKNOWN
        }
    }
}

object PodsRepository {
    private val _state = MutableStateFlow(PodsState())
    val state: StateFlow<PodsState> = _state.asStateFlow()

    fun update(block: (PodsState) -> PodsState) = _state.update(block)
    fun reset() { _state.value = PodsState() }
}
