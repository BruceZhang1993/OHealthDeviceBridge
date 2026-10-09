package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import java.util.Locale

internal class MiScaleDriver(override val model: String) : HistoryDeviceDriver {
    private val v2 get() = model == XiaomiModels.V2
    private val sessions = mutableMapOf<String, MiScaleGattSession>()
    private fun key(mac: String) = mac.uppercase(Locale.ROOT)
    override fun hasSession(mac: String) = sessions.containsKey(key(mac))
    override fun isConnected(mac: String) = sessions[key(mac)]?.connected == true
    override fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit {
        var finished = false
        var stop: (() -> Unit)? = null
        stop = XiaomiBleScan().start(context, XiaomiBleScan.uuid(if (v2) 0x181b else 0x181d), null, timeoutMs, { scan, bytes ->
            val name = scan.scanRecord?.deviceName.orEmpty().uppercase(Locale.ROOT)
            val knownName = name.startsWith("MI_SCALE") || name.startsWith("MIBCS") || name.startsWith("MIBFS") || name == "MI SCALE2"
            if (!finished && knownName && MiScaleProtocol.parse(bytes, v2, System.currentTimeMillis() / 1000) != null) {
                finished = true; stop?.invoke()
                onDevice(BridgeDevice(scan.device.address, XiaomiModels.names.getValue(model), model))
            }
        }, { if (!finished) { finished = true; onTimeout() } }, { if (!finished) { finished = true; onError(it) } })
        return { finished = true; stop.invoke() }
    }
    override fun verifyForBind(context: Context, mac: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.BIND_VERIFY, onSuccess, {}, {}, {}, {}, onError)
    override fun measure(context: Context, mac: String, profile: UserProfile, onLiveWeight: (Double) -> Unit, onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.MEASURE, {}, onLiveWeight, onFinal, onHistory, {}, onError)
    override fun receiveHistory(context: Context, mac: String, profile: UserProfile, onHistory: (MeasurementRecord) -> Unit, onConnected: (Boolean) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, MiScaleGattSession.Mode.HISTORY, {}, {}, {}, onHistory, onConnected, onError)
    @SuppressLint("MissingPermission")
    private fun start(context: Context, mac: String, mode: MiScaleGattSession.Mode, verified: () -> Unit, live: (Double) -> Unit, final: (MeasurementRecord) -> Unit, history: (MeasurementRecord) -> Unit, onConnected: (Boolean) -> Unit, failed: (Throwable) -> Unit) {
        disconnect(mac)
        try {
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: error("蓝牙不可用")
            check(adapter.isEnabled) { "请开启蓝牙" }
            lateinit var session: MiScaleGattSession
            session = MiScaleGattSession(context, adapter.getRemoteDevice(mac), v2, mode, verified, live, final, history, onConnected, failed) { sessions.remove(key(mac), session) }
            sessions[key(mac)] = session; session.connect()
        } catch (error: Exception) { failed(error) }
    }
    override fun disconnect(mac: String) { sessions.remove(key(mac))?.disconnect() }
}
