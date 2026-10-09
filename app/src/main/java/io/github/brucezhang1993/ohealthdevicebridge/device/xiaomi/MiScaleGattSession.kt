package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.device.MeasurementRecord
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuGattQueue
import java.util.UUID

/** One GATT owner, one bounded operation queue, all transitions on the main looper. */
@SuppressLint("MissingPermission")
internal class MiScaleGattSession(
    private val context: Context, private val device: BluetoothDevice, private val v2: Boolean,
    private val bindOnly: Boolean, private val verified: () -> Unit, private val live: (Double) -> Unit,
    private val final: (MeasurementRecord) -> Unit, private val history: (MeasurementRecord) -> Unit,
    private val failed: (Throwable) -> Unit, private val closed: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scheduled = mutableSetOf<Runnable>()
    private var gatt: BluetoothGatt? = null
    private var active = true
    var connected = false; private set
    private val queue = AfuGattQueue({ gatt }, ::schedule, ::fail)
    private val transfer = MiScaleProtocol.History(v2)
    private val seen = mutableSetOf<Pair<Long, Double>>()
    private var historyCharacteristic: BluetoothGattCharacteristic? = null
    private var pendingFinal: MiScaleProtocol.Reading? = null
    private var transferMarker = 0
    fun connect() {
        try {
            gatt = device.connectGatt(context, false, callbacks, BluetoothDevice.TRANSPORT_LE) ?: error("无法连接小米体脂秤")
            schedule(10_000) { if (!connected) fail(IllegalStateException("小米体脂秤连接超时，请唤醒秤并关闭占用蓝牙的应用")) }
            schedule(120_000) { fail(IllegalStateException("小米测量会话超时，请重新开始")) }
        } catch (error: Exception) { fail(error) }
    }
    private val callbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = dispatch {
            if (status != BluetoothGatt.GATT_SUCCESS || newState != BluetoothProfile.STATE_CONNECTED) fail(IllegalStateException("小米蓝牙连接断开（$status）"))
            else check(gatt.discoverServices()) { "无法读取小米蓝牙服务" }
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = dispatch {
            check(status == BluetoothGatt.GATT_SUCCESS) { "无法读取小米蓝牙服务（$status）" }
            configure(gatt)
        }
        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = dispatch { queue.onDescriptorWrite(descriptor, status) }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = dispatch { queue.onCharacteristicWrite(characteristic, status) }
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val data = characteristic.value?.copyOf() ?: return
            dispatch { receive(characteristic.uuid, data) }
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val data = value.copyOf(); dispatch { receive(characteristic.uuid, data) }
        }
    }
    private fun configure(gatt: BluetoothGatt) {
        val service = gatt.getService(XiaomiBleScan.uuid(if (v2) 0x181b else 0x181d)) ?: error("小米体脂秤型号与蓝牙服务不匹配")
        val history = service.getCharacteristic(HISTORY) ?: error("小米体脂秤历史通道不可用")
        historyCharacteristic = history
        val time = service.getCharacteristic(XiaomiBleScan.uuid(0x2a2b)) ?: error("小米体脂秤时间通道不可用")
        queue.enqueueWrite(time, MiScaleProtocol.currentTime(), "Xiaomi time sync")
        fun subscribe(characteristic: BluetoothGattCharacteristic, done: () -> Unit) {
            check(gatt.setCharacteristicNotification(characteristic, true)) { "无法订阅小米测量数据" }
            val descriptor = characteristic.getDescriptor(CCCD) ?: error("小米通知描述符不可用")
            val value = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            queue.enqueueDescriptor(descriptor, value, "Xiaomi measurement subscription", done)
        }
        fun ready() {
            connected = true
            if (bindOnly) { disconnect(); verified(); return }
            val marker = (System.nanoTime() and 0xffff).toInt()
            transferMarker = marker
            // Request all available history, never send a delete-history command.
            queue.enqueueWrite(history, byteArrayOf(1, 0, 0, (marker shr 8).toByte(), marker.toByte()), "Xiaomi history range")
            queue.enqueueWrite(history, byteArrayOf(2), "Xiaomi history transfer")
        }
        val magic = byteArrayOf(1, 0x96.toByte(), 0x8a.toByte(), 0xbd.toByte(), 0x62)
        if (!v2) queue.enqueueWrite(history, magic, "Xiaomi history enable")
        subscribe(history) {
            if (v2) queue.enqueueWrite(history, magic, "Xiaomi history enable")
            val weight = service.getCharacteristic(XiaomiBleScan.uuid(0x2a9d))
            if (weight == null) ready() else subscribe(weight, ::ready)
        }
    }
    private fun receive(uuid: UUID, data: ByteArray) {
        if (bindOnly) return
        val wasHistory = transfer.transferring && uuid == HISTORY
        val records = if (uuid == HISTORY) transfer.feed(data, epoch()) else listOfNotNull(MiScaleProtocol.parse(data, data.size == 13, epoch()))
        if (uuid == HISTORY && data.contentEquals(byteArrayOf(3))) historyCharacteristic?.let {
            queue.enqueueWrite(it, byteArrayOf(3), "Xiaomi history stop acknowledgement")
            queue.enqueueWrite(it, byteArrayOf(4, 0xff.toByte(), 0xff.toByte(), (transferMarker shr 8).toByte(), transferMarker.toByte()), "Xiaomi history completion acknowledgement")
        }
        records.forEach { reading ->
            if (reading.removed || reading.record.weightKg <= 0) { pendingFinal = null; return@forEach }
            if (wasHistory) {
                if (reading.stable && reading.dated && seen.add(reading.record.timestampEpochSeconds to reading.record.weightKg)) history(reading.record)
            } else {
                live(reading.record.weightKg)
                if (reading.stable) {
                    if (!v2 || reading.record.resistanceOhm != null) emitFinal(reading.record)
                    else {
                        pendingFinal = reading
                        schedule(3_000) { if (pendingFinal === reading) { pendingFinal = null; emitFinal(reading.record) } }
                    }
                }
            }
        }
    }
    private fun emitFinal(record: MeasurementRecord) { pendingFinal = null; if (seen.add(record.timestampEpochSeconds to record.weightKg)) final(record) }
    fun disconnect() {
        if (!active) return
        active = false; connected = false; scheduled.forEach(main::removeCallbacks); scheduled.clear(); queue.clear()
        val old = gatt; gatt = null
        try { old?.disconnect() } catch (_: Exception) { }
        finally { try { old?.close() } catch (_: Exception) { } finally { closed() } }
    }
    private fun fail(error: Exception) { if (active) { disconnect(); failed(error) } }
    private fun dispatch(block: () -> Unit) { main.post { if (active) try { block() } catch (error: Exception) { fail(error) } } }
    private fun schedule(delay: Long, block: () -> Unit): () -> Unit {
        lateinit var task: Runnable
        task = Runnable { scheduled.remove(task); if (active) try { block() } catch (error: Exception) { fail(error) } }
        scheduled += task; main.postDelayed(task, delay)
        return { scheduled.remove(task); main.removeCallbacks(task) }
    }
    private fun epoch() = System.currentTimeMillis() / 1000
    companion object {
        val HISTORY: UUID = UUID.fromString("00002a2f-0000-3512-2118-0009af100700")
        val CCCD = XiaomiBleScan.uuid(0x2902)
    }
}
