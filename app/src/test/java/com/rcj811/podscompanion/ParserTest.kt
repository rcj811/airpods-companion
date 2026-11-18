package com.rcj811.podscompanion

import com.rcj811.podscompanion.aacp.AacpPackets
import com.rcj811.podscompanion.ble.AppleProximityParser
import com.rcj811.podscompanion.eq.EqData
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ParserTest {

    private fun ble(model: Int, status: Int, battery: Int, flagsCase: Int, lid: Int): ByteArray {
        val b = ByteArray(27)
        b[0] = 0x07; b[1] = 0x19; b[2] = 0x01
        b[3] = (model shr 8).toByte(); b[4] = model.toByte()
        b[5] = status.toByte(); b[6] = battery.toByte(); b[7] = flagsCase.toByte(); b[8] = lid.toByte()
        return b
    }

    @Test fun bleLeftPrimary() {
        val r = AppleProximityParser.parse(ble(0x1420, 0x2A, 0x89, 0x05, 0x00))!!
        assertEquals(PodsModel.AIRPODS_PRO_2, r.model)
        assertEquals(90, r.left.level)
        assertEquals(80, r.right.level)
        assertEquals(50, r.case.level)
        assertTrue(r.left.inEar && r.right.inEar)
        assertFalse(r.left.charging || r.right.charging || r.case.charging)
    }

    @Test fun bleFlippedWhenRightPrimary() {
        val r = AppleProximityParser.parse(ble(0x1420, 0x0A, 0x89, 0x05, 0x00))!!
        assertEquals(80, r.left.level)
        assertEquals(90, r.right.level)
    }

    @Test fun bleChargingAndLid() {
        val open = AppleProximityParser.parse(ble(0x0E20, 0x24, 0xAA, 0x74, 0x00))!!
        assertEquals(PodsModel.AIRPODS_PRO, open.model)
        assertTrue(open.case.charging)
        assertTrue(open.left.charging && open.right.charging)
        assertEquals(100, open.left.level)
        assertEquals(true, open.lidOpen)
        val closed = AppleProximityParser.parse(ble(0x0E20, 0x24, 0xAA, 0x74, 0x08))!!
        assertEquals(false, closed.lidOpen)
        // lid not reliable when no pod in case
        assertNull(AppleProximityParser.parse(ble(0x0E20, 0x20, 0xAA, 0x74, 0x00))!!.lidOpen)
    }

    @Test fun bleUnknownBattery() {
        val r = AppleProximityParser.parse(ble(0x1420, 0x20, 0xFF, 0x0F, 0x00))!!
        assertFalse(r.left.known || r.right.known || r.case.known)
    }

    @Test fun bleOverEar() {
        val r = AppleProximityParser.parse(ble(0x0A20, 0x20, 0x07, 0x10, 0x00))!!
        assertTrue(r.model.overEar)
        assertEquals(70, r.single.level)
        assertTrue(r.single.charging)
    }

    @Test fun bleProximityAfterOtherMessage() {
        val nearby = byteArrayOf(0x10, 0x05, 1, 2, 3, 4, 5)
        val r = AppleProximityParser.parse(nearby + ble(0x1420, 0x2A, 0x89, 0x05, 0x00))!!
        assertEquals(90, r.left.level)
        assertEquals(listOf("10/05", "07/19"), AppleProximityParser.messageTypes(nearby + ble(0x1420, 0x2A, 0x89, 0x05, 0)))
        assertNull(AppleProximityParser.parse(nearby))
    }

    @Test fun bleRejectsGarbage() {
        assertNull(AppleProximityParser.parse(null))
        assertNull(AppleProximityParser.parse(ByteArray(3)))
        assertNull(AppleProximityParser.parse(ByteArray(27) { 0x10 }))
        repeat(2000) { AppleProximityParser.messageTypes(ByteArray(Random(it).nextInt(0, 40)) { b -> (b * 37).toByte() }) }
        val rnd = Random(1)
        repeat(20_000) { // fuzz: must never throw
            val len = rnd.nextInt(0, 40)
            val b = ByteArray(len) { rnd.nextInt().toByte() }
            if (len > 1 && rnd.nextBoolean()) { b[0] = 0x07; b[1] = 0x19 }
            AppleProximityParser.parse(b)
        }
    }

    @Test fun aacpBattery() {
        val p = byteArrayOf(4, 0, 4, 0, 4, 0, 3,
            0x04, 1, 100, 2, 1,
            0x02, 1, 50, 1, 1,
            0x08, 1, 10, 4, 1)
        val m = AacpPackets.decode(p) as AacpPackets.Message.Battery
        assertEquals(Pod(100, false), m.left)
        assertEquals(Pod(50, true), m.right)
        assertNull(m.case) // disconnected
    }

    @Test fun aacpControl() {
        val m = AacpPackets.decode(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 3, 0, 0, 0)) as AacpPackets.Message.Control
        assertEquals(0x0D, m.id)
        assertEquals(ListeningMode.TRANSPARENCY, ListeningMode.from(m.value))
        assertArrayEquals(byteArrayOf(4, 0, 4, 0, 9, 0, 0x0D, 2, 0, 0, 0), AacpPackets.control(0x0D, 2))
    }

    @Test fun aacpFuzz() {
        assertNull(AacpPackets.decode(ByteArray(0)))
        assertNotNull(AacpPackets.decode(byteArrayOf(4, 0, 4, 0, 4, 0, 9, 4, 1))) // truncated count
        val rnd = Random(2)
        repeat(20_000) {
            val b = ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }
            if (b.size > 5 && rnd.nextBoolean()) { b[0] = 4; b[1] = 0; b[2] = 4; b[3] = 0; b[4] = listOf(4, 9).random(rnd).toByte() }
            AacpPackets.decode(b)
        }
    }

    @Test fun hfpBattery() {
        assertEquals(70, com.rcj811.podscompanion.service.PodsService.hfpBatteryLevel(listOf(2, 1, 6, 2, 0)))
        assertEquals(100, com.rcj811.podscompanion.service.PodsService.hfpBatteryLevel(listOf(1, 1, 9)))
        assertNull(com.rcj811.podscompanion.service.PodsService.hfpBatteryLevel(listOf(1, 2, 0)))
        assertNull(com.rcj811.podscompanion.service.PodsService.hfpBatteryLevel(listOf(5, null)))
    }

    @Test fun eqData() {
        assertEquals(14, EqData.corrections.size)
        EqData.corrections.forEach { assertEquals(10, it.gains.size) }
        EqData.styles.forEach { assertEquals(10, it.gains.size) }
        for (a in EqData.corrections) for (b in EqData.corrections) {
            val g = EqData.mimic(a, b)
            assertEquals(10, g.size)
            g.forEach { assertTrue(it.isFinite() && it in -10f..10f) }
        }
        EqData.corrections.forEach { c -> EqData.neutral(c).forEach { assertTrue(it in -10f..10f) } }
        // every model that claims an EQ key has data
        PodsModel.entries.mapNotNull { it.eqKey }.forEach { assertNotNull(EqData.correction(it)) }
    }
}
