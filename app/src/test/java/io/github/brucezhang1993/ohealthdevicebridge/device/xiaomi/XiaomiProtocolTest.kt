package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import io.github.brucezhang1993.ohealthdevicebridge.xiaomi.MiHomeSessionCodec
import org.junit.Assert.*
import org.junit.Test

class XiaomiProtocolTest {
    private val key = ByteArray(16) { it.toByte() }
    private val s800 = MiHomeSessionCodec.hex("4850e25107030a81eee4ec2965c0e50e1501020300133e60")
    @Test fun s800DecryptsIndependentCcmFixtureAndReportsWeightOnly() {
        val frame = MiBeaconProtocol.decode(s800, "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S800)!!
        val record = MiBeaconProtocol.s800(frame.objects.single(), 1000)!!
        assertEquals(72.35, record.weightKg, 0.0001)
        assertNull(record.resistanceOhm); assertNull(record.heartRateBpm)
    }
    @Test fun wrongKeyMacProductAndTamperedTagAreRejected() {
        assertNull(MiBeaconProtocol.decode(s800, "AA:BB:CC:DD:EE:FF", ByteArray(16), XiaomiModels.S800))
        assertNull(MiBeaconProtocol.decode(s800, "AA:BB:CC:DD:EE:00", key, XiaomiModels.S800))
        assertNull(MiBeaconProtocol.decode(s800, "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S400))
        assertNull(MiBeaconProtocol.decode(s800.copyOf().apply { this[lastIndex] = 0 }, "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S800))
        for (n in 0 until 12) assertNull(MiBeaconProtocol.decode(s800.copyOf(n), "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S800))
    }
    private fun part(kg: Double?, resistance: Double?, profile: Int = 1, stamp: Long = 12) = MiBeaconProtocol.S400Part(profile, stamp, kg, resistance, 80)
    @Test fun s400UsesWeightBearing50KhzBandForIcomonAndRetains250KhzSeparately() {
        val flow = S400MeasurementFlow()
        assertNull(flow.receive(part(72.3, 612.0), 100, 1000))
        val record = flow.receive(part(null, 455.0), 300, 1000)!!
        assertEquals(612, record.resistanceOhm)
        assertEquals(455.0, record.resistance250KhzOhm!!, 0.0)
        assertEquals(80, record.heartRateBpm)
        assertNull(flow.poll(7000, 1000))
    }
    @Test fun missingBandAndNextMeasurementNeverReuseEarlierMetrics() {
        val flow = S400MeasurementFlow()
        flow.receive(part(70.0, 600.0), 100, 1000)
        val timedOut = flow.poll(6000, 1000)!!
        assertEquals(600, timedOut.resistanceOhm); assertNull(timedOut.resistance250KhzOhm)
        assertNull(flow.receive(part(null, 450.0), 6100, 1000))
        val weightOnly = flow.receive(part(71.0, null), 7000, 1001)!!
        assertNull(weightOnly.resistanceOhm); assertNull(weightOnly.resistance250KhzOhm)
    }
    @Test fun crossProfileAndCrossWeighInPartialPacketsDoNotMerge() {
        val flow = S400MeasurementFlow()
        flow.receive(part(70.0, 600.0), 100, 1000)
        assertNull(flow.receive(part(null, 450.0, profile = 2), 200, 1000))
        flow.receive(part(70.0, 600.0), 300, 1000)
        assertNull(flow.receive(part(null, 450.0, stamp = 13), 400, 1000))
    }
    @Test fun legacyUnitsImpedanceAndHistoryFragments() {
        // Stable 2026-10-09 12:30:00, 70 kg, 500 ohm, v2.
        val bytes = MiHomeSessionCodec.hex("0022ea070a090c1e00f401b036")
        val reading = MiScaleProtocol.parse(bytes, true, 0)!!
        assertTrue(reading.stable); assertEquals(70.0, reading.record.weightKg, 0.0001); assertEquals(500, reading.record.resistanceOhm)
        assertNull(MiScaleProtocol.parse(bytes.copyOf(5), true, 0))
        val old = MiHomeSessionCodec.hex("20b036ea070a090c1e00")
        val history = MiScaleProtocol.History(false)
        assertTrue(history.feed(old.copyOf(4), 0).isEmpty())
        assertEquals(70.0, history.feed(old.copyOfRange(4, 10), 0).single().record.weightKg, 0.0001)
        history.feed(byteArrayOf(3), 0); assertFalse(history.transferring)
        assertTrue(MiScaleProtocol.parse(old.copyOf().apply { this[0] = 0xa0.toByte() }, false, 0)!!.removed)
    }
    @Test fun s800AndS400CloudModelsStayExplicit() {
        assertEquals(XiaomiModels.S400, XiaomiModels.cloudModel("yunmai.scales.ms104"))
        assertEquals(XiaomiModels.S800, XiaomiModels.cloudModel("xiaomi.scales.ms116"))
        assertNull(XiaomiModels.cloudModel("xiaomi.scales.ms110"))
    }
    @Test fun s400EncryptedPacketsWithMacAndExtendedCapabilityDecodeTogether() {
        // Independent PyCryptodome CCM fixtures, including an embedded MAC and capability extension.
        val a = MiHomeSessionCodec.hex("7850d93001ffeeddccbbaa20008a8113c3b16c6df68d8b9d190100001db40105")
        val b = MiHomeSessionCodec.hex("4850d93002da7d7e6cd0433ad59f85b92c01000079b18d4f")
        val first = MiBeaconProtocol.decode(a, "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S400)!!
        val second = MiBeaconProtocol.decode(b, "AA:BB:CC:DD:EE:FF", key, XiaomiModels.S400)!!
        assertEquals(257L, first.counter); assertEquals(258L, second.counter)
        val flow = S400MeasurementFlow()
        assertNull(flow.receive(MiBeaconProtocol.s400(first.objects.single())!!, 100, 1000))
        val result = flow.receive(MiBeaconProtocol.s400(second.objects.single())!!, 200, 1000)!!
        assertEquals(70.0, result.weightKg, 0.0001)
        assertEquals(612, result.resistanceOhm); assertEquals(455.0, result.resistance250KhzOhm!!, 0.0001)
        assertEquals(80, result.heartRateBpm)
        assertNull(MiBeaconProtocol.decode(a, "AA:BB:CC:DD:EE:00", key, XiaomiModels.S400))
    }

}
