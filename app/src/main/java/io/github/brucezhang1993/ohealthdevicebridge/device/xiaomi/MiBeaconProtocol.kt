package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import kotlin.math.roundToInt

internal object MiBeaconProtocol {
    data class ObjectValue(val type: Int, val value: ByteArray)
    data class Frame(val counter: Long, val objects: List<ObjectValue>)
    data class S400Part(val profile: Int, val stamp: Long, val weight: Double?, val resistance: Double?, val heart: Int?)
    private fun u8(data: ByteArray, i: Int) = data[i].toInt() and 255
    fun u16(data: ByteArray, i: Int) = u8(data, i) or (u8(data, i + 1) shl 8)
    fun u32(data: ByteArray, i: Int): Long = (0..3).fold(0L) { n, b -> n or (u8(data, i + b).toLong() shl (8 * b)) }
    fun decode(data: ByteArray, mac: String, key: ByteArray, model: String): Frame? {
        if (data.size < 12 || key.size != 16) return null
        val flags = u16(data, 0)
        if (flags ushr 12 !in 4..5 || flags and 0x48 != 0x48) return null
        val product = u16(data, 2)
        if (model == XiaomiModels.S800 && product != 0x51e2) return null
        if (model == XiaomiModels.S400 && product !in setOf(0x30d9, 0x3bd5, 0x48cf)) return null
        val address = mac.split(':').map { it.toInt(16).toByte() }.reversed().toByteArray()
        var offset = 5
        if (flags and 0x10 != 0) {
            if (data.size < 11 || !data.copyOfRange(5, 11).contentEquals(address)) return null
            offset += 6
        }
        if (flags and 0x20 != 0) {
            if (offset >= data.size - 7) return null
            val capability = u8(data, offset++)
            if (capability and 0x20 != 0) offset++
        }
        if (offset >= data.size - 7) return null
        val nonce = address + data.copyOfRange(2, 5) + data.copyOfRange(data.size - 7, data.size - 4)
        val authenticated = data.copyOfRange(offset, data.size - 7) + data.takeLast(4).toByteArray()
        val plain = try {
            val ccm = CCMBlockCipher(AESEngine.newInstance())
            ccm.init(false, AEADParameters(KeyParameter(key), 32, nonce, byteArrayOf(0x11)))
            val out = ByteArray(ccm.getOutputSize(authenticated.size))
            val count = ccm.processBytes(authenticated, 0, authenticated.size, out, 0)
            val size = count + ccm.doFinal(out, count)
            out.copyOf(size)
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) { return null }
        val objects = mutableListOf<ObjectValue>()
        var i = 0
        while (i < plain.size) {
            if (i + 3 > plain.size) return null
            val length = u8(plain, i + 2)
            if (i + 3 + length > plain.size) return null
            objects += ObjectValue(u16(plain, i), plain.copyOfRange(i + 3, i + 3 + length))
            i += 3 + length
        }
        val counter = u8(data, 4).toLong() or ((u32(data.copyOfRange(data.size - 7, data.size - 4) + byteArrayOf(0), 0)) shl 8)
        return Frame(counter, objects)
    }
    fun s400(value: ObjectValue): S400Part? {
        if (value.type != 0x6e16 || value.value.size != 9) return null
        val packed = u32(value.value, 1)
        if (packed == 0L) return S400Part(u8(value.value, 0), u32(value.value, 5), null, null, null)
        val mass = (packed and 0x7ff) / 10.0
        val heart = ((packed ushr 11) and 0x7f).toInt()
        val resistance = (packed ushr 18) / 10.0
        return S400Part(u8(value.value, 0), u32(value.value, 5), mass.takeIf { it in 1.0..300.0 },
            resistance.takeIf { it in 1.0..1600.0 }, (heart + 50).takeIf { heart in 1..126 })
    }
    fun s800(value: ObjectValue, now: Long): MeasurementRecord? {
        if (value.type != 0x4e16 || value.value.size != 9) return null
        val kg = u16(value.value, 7) / 100.0
        return kg.takeIf { it in 1.0..350.0 }?.let { MeasurementRecord(now, it, null) }
    }
}

/** Each measurement owns its partial metrics; nothing is carried into the next weigh-in. */
internal class S400MeasurementFlow {
    private var first: MiBeaconProtocol.S400Part? = null
    private var started = 0L
    fun reset() { first = null }
    fun receive(part: MiBeaconProtocol.S400Part, elapsed: Long, epoch: Long): MeasurementRecord? {
        if (part.weight != null) {
            first = part; started = elapsed
            if (part.resistance == null) { first = null; return MeasurementRecord(epoch, part.weight, null, heartRateBpm = part.heart) }
            return null
        }
        if (part.resistance == null) { reset(); return null }
        val weight = first ?: return null
        if (elapsed - started > 5_000 || part.profile != weight.profile || (part.stamp != 0L && weight.stamp != 0L && part.stamp != weight.stamp)) { reset(); return null }
        first = null
        // 0x6e16: the weight-bearing packet carries 50 kHz; the second carries 250 kHz.
        // Protocol field reference: Bluetooth-Devices/xiaomi-ble parser.py, obj6e16.
        return MeasurementRecord(epoch, weight.weight!!, weight.resistance?.roundToInt(), part.resistance, weight.heart)
    }
    fun poll(elapsed: Long, epoch: Long): MeasurementRecord? {
        val pending = first ?: return null
        if (elapsed - started < 5_000) return null
        first = null
        // Preserve the received 50 kHz value; the absent 250 kHz band remains absent.
        return MeasurementRecord(epoch, pending.weight!!, pending.resistance?.roundToInt(), null, pending.heart)
    }
}
