package com.rcj811.podscompanion.aacp

import com.rcj811.podscompanion.Pod

/** Pure packet encoding/decoding for the AirPods accessory protocol (unit-testable). */
object AacpPackets {
    const val ID_LISTENING_MODE = 0x0D
    const val ID_CONVERSATION_DETECT = 0x28

    private const val COMP_SINGLE = 0x01
    private const val COMP_RIGHT = 0x02
    private const val COMP_LEFT = 0x04
    private const val COMP_CASE = 0x08
    private const val STATUS_CHARGING = 1
    private const val STATUS_DISCONNECTED = 4

    val HANDSHAKE = byteArrayOf(0, 0, 4, 0, 1, 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    val FEATURE_FLAGS = data(byteArrayOf(0x4D, 0x00, 0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0))
    val REQUEST_NOTIFICATIONS = data(byteArrayOf(0x0F, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))

    fun data(payload: ByteArray) = byteArrayOf(4, 0, 4, 0) + payload

    fun control(id: Int, value: Int) = data(byteArrayOf(0x09, 0x00, id.toByte(), value.toByte(), 0, 0, 0))

    sealed interface Message {
        data class Battery(val left: Pod?, val right: Pod?, val case: Pod?, val single: Pod?) : Message
        data class Control(val id: Int, val value: Int) : Message
    }

    fun decode(p: ByteArray): Message? {
        if (p.size < 6) return null
        if (p[0].toInt() != 0x04 || p[1].toInt() != 0x00 || p[2].toInt() != 0x04 || p[3].toInt() != 0x00) return null
        return when (p[4].toInt() and 0xFF) {
            0x04 -> decodeBattery(p)
            0x09 -> if (p.size >= 8) Message.Control(p[6].toInt() and 0xFF, p[7].toInt() and 0xFF) else null
            else -> null
        }
    }

    /** 04 00 04 00 04 00 [count] then count × [component, 01, level, status, 01] */
    private fun decodeBattery(p: ByteArray): Message.Battery? {
        if (p.size < 7) return null
        val count = p[6].toInt() and 0xFF
        var left: Pod? = null
        var right: Pod? = null
        var case: Pod? = null
        var single: Pod? = null
        for (i in 0 until count) {
            val o = 7 + i * 5
            if (o + 3 >= p.size) break
            val component = p[o].toInt() and 0xFF
            val level = p[o + 2].toInt() and 0xFF
            val status = p[o + 3].toInt() and 0xFF
            if (status == STATUS_DISCONNECTED) continue
            val pod = Pod(level.coerceIn(0, 100), charging = status == STATUS_CHARGING)
            when (component) {
                COMP_LEFT -> left = pod
                COMP_RIGHT -> right = pod
                COMP_CASE -> case = pod
                COMP_SINGLE -> single = pod
            }
        }
        return Message.Battery(left, right, case, single)
    }
}
