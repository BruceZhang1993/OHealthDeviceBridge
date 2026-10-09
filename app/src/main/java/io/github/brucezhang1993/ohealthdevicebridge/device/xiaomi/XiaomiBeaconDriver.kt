package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.brucezhang1993.ohealthdevicebridge.device.*
import io.github.brucezhang1993.ohealthdevicebridge.xiaomi.MiHomeBridgeClient
import java.util.Locale

internal class XiaomiBeaconDriver(override val model: String) : DeviceDriver {
    private class Session {
        var active = true
        var listening = false
        var cancelKey: (() -> Unit)? = null
        var cancelScan: (() -> Unit)? = null
        var key: ByteArray? = null
    }
    private val sessions = mutableMapOf<String, Session>()
    private fun normalized(mac: String) = mac.uppercase(Locale.ROOT)
    override fun hasSession(mac: String) = sessions[normalized(mac)]?.active == true
    override fun isConnected(mac: String) = sessions[normalized(mac)]?.listening == true
    override fun scan(context: Context, timeoutMs: Long, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit =
        MiHomeBridgeClient.scan(context, model, onDevice, onTimeout, onError)
    override fun verifyForBind(context: Context, mac: String, onSuccess: () -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, true, onSuccess, {}, {}, onError)
    override fun measure(context: Context, mac: String, profile: UserProfile, onLiveWeight: (Double) -> Unit, onFinal: (MeasurementRecord) -> Unit, onHistory: (MeasurementRecord) -> Unit, onError: (Throwable) -> Unit) =
        start(context, mac, false, {}, onLiveWeight, onFinal, onError)
    private fun start(context: Context, mac: String, bind: Boolean, verified: () -> Unit, live: (Double) -> Unit, final: (MeasurementRecord) -> Unit, failed: (Throwable) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        disconnect(mac)
        val session = Session(); sessions[normalized(mac)] = session
        fun fail(error: Throwable) { if (session.active) { disconnect(mac); failed(error) } }
        session.cancelKey = MiHomeBridgeClient.credential(context, mac, model, { key ->
            if (!session.active) { key.fill(0); return@credential }
            session.key = key
            val flow = S400MeasurementFlow()
            val seen = linkedSetOf<Long>()
            val main = Handler(Looper.getMainLooper())
            val poll = object : Runnable {
                override fun run() {
                    if (!session.active) return
                    try { flow.poll(SystemClock.elapsedRealtime(), System.currentTimeMillis() / 1000)?.let { final(it) } }
                    catch (error: Exception) { fail(error); return }
                    main.postDelayed(this, 250)
                }
            }
            main.post(poll)
            val cancel = XiaomiBleScan().start(context, XiaomiBleScan.MIBEACON, mac, if (bind) 30_000 else 120_000, { _, data ->
                val frame = MiBeaconProtocol.decode(data, mac, key, model) ?: return@start
                if (bind) { disconnect(mac); verified(); return@start }
                if (!seen.add(frame.counter)) return@start
                if (seen.size > 64) seen.remove(seen.first())
                val epoch = System.currentTimeMillis() / 1000
                for (obj in frame.objects) {
                    if (model == XiaomiModels.S800) MiBeaconProtocol.s800(obj, epoch)?.let { live(it.weightKg); final(it) }
                    else MiBeaconProtocol.s400(obj)?.let { part ->
                        part.weight?.let(live)
                        flow.receive(part, SystemClock.elapsedRealtime(), epoch)?.let(final)
                    }
                }
            }, { fail(IllegalStateException(if (bind) "未收到可解密的体脂秤广播，请站上秤后重试；仍失败时请重新从米家读取凭证" else "测量接收已超时，请重新开始")) }, ::fail)
            session.cancelScan = { cancel(); main.removeCallbacks(poll); flow.reset() }
            session.listening = session.active
            if (!session.active) session.cancelScan?.invoke()
        }, ::fail)
    }
    override fun disconnect(mac: String) {
        val session = sessions.remove(normalized(mac)) ?: return
        session.active = false; session.listening = false
        session.cancelKey?.invoke(); session.cancelScan?.invoke(); session.key?.fill(0); session.key = null
    }
}
