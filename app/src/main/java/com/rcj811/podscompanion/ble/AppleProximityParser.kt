package com.rcj811.podscompanion.ble

import com.rcj811.podscompanion.Pod
import com.rcj811.podscompanion.PodsModel

/**
 * Decodes the "proximity pairing" message (type 0x07) that AirPods / Beats broadcast over BLE
 * with Apple's manufacturer id 0x004C. Works on every Android phone without root.
 *
 * Layout of the manufacturer data (after the 0x004C company id):
 *  [0]   0x07 message type
 *  [1]   0x19 length (25)
 *  [2]   prefix
 *  [3-4] model id (big endian, e.g. 0x1420 = AirPods Pro 2)
 *  [5]   status bits (bit5 = left pod is primary, bit6 = broadcasting pod in case, bit1/bit3 = in ear ...)
 *  [6]   pod battery nibbles (0-10 → 0-100 %, 15 = unknown)
 *  [7]   upper nibble: charging flags, lower nibble: case battery
 *  [8]   lid state
 */
object AppleProximityParser {
    const val APPLE_COMPANY_ID = 0x004C

    data class Result(
        val model: PodsModel,
        val modelId: Int,
        val left: Pod,
        val right: Pod,
        val case: Pod,
        val single: Pod,
        val lidOpen: Boolean?,
        val raw: String = "",
    )

    /**
     * Apple manufacturer data can hold several messages back to back as [type][length][payload…].
     * Returns the proximity-pairing (0x07) message if present.
     */
    fun findProximityMessage(data: ByteArray?): ByteArray? {
        if (data == null) return null
        var i = 0
        while (i + 1 < data.size) {
            val type = data[i].toInt() and 0xFF
            val len = data[i + 1].toInt() and 0xFF
            if (type == 0x07) return data.copyOfRange(i, minOf(data.size, i + 2 + len))
            i += 2 + len
        }
        return null
    }

    /** All message types in an Apple advertisement, e.g. "10/07" (type/length). */
    fun messageTypes(data: ByteArray): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i + 1 < data.size) {
            out += "%02X/%02X".format(data[i], data[i + 1])
            i += 2 + (data[i + 1].toInt() and 0xFF)
        }
        return out
    }

    private fun hex(b: ByteArray) = b.take(11).joinToString("") { "%02X".format(it) }

    fun parse(raw: ByteArray?): Result? {
        val m = findProximityMessage(raw) ?: return null
        if (m.size < 9) return null
        // Length is 0x19 on most models; accept other lengths as long as the fields we need exist.

        val modelId = ((m[3].toInt() and 0xFF) shl 8) or (m[4].toInt() and 0xFF)
        val model = PodsModel.fromBleId(modelId)
        val status = m[5].toInt() and 0xFF
        val battery = m[6].toInt() and 0xFF
        val flags = (m[7].toInt() and 0xF0) shr 4
        val caseNibble = m[7].toInt() and 0x0F
        val lidByte = m[8].toInt() and 0xFF

        fun bit(n: Int) = (status shr n) and 1 == 1
        fun level(n: Int) = when {
            n == 15 -> -1
            n > 10 -> 100
            else -> n * 10
        }

        val flipped = !bit(5)
        val thisPodInCase = bit(6)
        val upper = (battery shr 4) and 0x0F
        val lower = battery and 0x0F

        if (model.overEar) {
            val single = Pod(level(lower), charging = flags and 0x01 != 0, inEar = bit(5))
            return Result(model, modelId, Pod(), Pod(), Pod(), single, null, hex(m))
        }

        val leftLevel = level(if (flipped) upper else lower)
        val rightLevel = level(if (flipped) lower else upper)
        val leftCharging = flags and (if (flipped) 0x02 else 0x01) != 0
        val rightCharging = flags and (if (flipped) 0x01 else 0x02) != 0
        val caseCharging = flags and 0x04 != 0

        val swapEar = flipped xor thisPodInCase
        val leftInEar = if (swapEar) bit(3) else bit(1)
        val rightInEar = if (swapEar) bit(1) else bit(3)

        val lidReliable = thisPodInCase || bit(2)
        val lidOpen = if (lidReliable) ((lidByte shr 3) and 1) == 0 else null

        return Result(
            model = model,
            modelId = modelId,
            left = Pod(leftLevel, leftCharging, leftInEar),
            right = Pod(rightLevel, rightCharging, rightInEar),
            case = Pod(level(caseNibble), caseCharging),
            single = Pod(),
            lidOpen = lidOpen,
            raw = hex(m),
        )
    }
}
