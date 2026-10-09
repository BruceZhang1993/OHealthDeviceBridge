package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.brucezhang1993.ohealthdevicebridge.BuildConfig
import io.github.brucezhang1993.ohealthdevicebridge.device.BridgeDevice
import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.OppoAccount
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Runs inside OPPO. Only the module's provider can read sessions or request root. */
internal object MiHomeBridgeClient {
    private val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.mihome")
    private val main = Handler(Looper.getMainLooper())
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4)).apply { allowCoreThreadTimeOut(true) }
    private fun account(context: Context) = OppoAccount.key(context.classLoader)
    private fun call(context: Context, account: String, method: String, arg: String? = null, model: String? = null): Bundle {
        val result = context.contentResolver.call(uri, method, arg, Bundle().apply { putString("account", account); putString("model", model) }) ?: error("请安装并启用桥接模块")
        result.getString("error")?.let { throw MiHomeFailure(it, result.getString("message") ?: "模块读取失败") }
        return result
    }
    fun scan(context: Context, model: String, onDevice: (BridgeDevice) -> Unit, onTimeout: () -> Unit, onError: (Throwable) -> Unit): () -> Unit {
        val account = account(context)
        val id = java.util.UUID.randomUUID().toString()
        var active = true
        var proof: android.app.PendingIntent? = null
        lateinit var watchdog: Runnable
        fun cleanup() { main.removeCallbacks(watchdog); proof?.cancel() }
        val deadline = android.os.SystemClock.elapsedRealtime() + 120_000L
        fun cancel() { try { if (active) { active = false; call(context, account, "cancel", id) } } finally { cleanup() } }
        watchdog = Runnable { if (active) { try { cancel() } catch (_: Exception) { }; onTimeout() } }
        main.postDelayed(watchdog, 120_000)
        lateinit var poll: Runnable
        poll = Runnable {
            if (!active) return@Runnable
            try {
                if (account(context) != account) { cancel(); return@Runnable }
                if (android.os.SystemClock.elapsedRealtime() >= deadline) { cancel(); onTimeout(); return@Runnable }
                val status = call(context, account, "status", id)
                when (status.getString("state")) {
                    "READY" -> { active = false; cleanup(); onDevice(BridgeDevice(status.getString("mac")!!, status.getString("name")!!, status.getString("model")!!)) }
                    "CANCELLED" -> { active = false; cleanup(); onError(IllegalStateException(status.getString("message"))) }
                    else -> main.postDelayed(poll, 500)
                }
            } catch (error: Exception) { active = false; cleanup(); onError(error) }
        }
        try {
            proof = android.app.PendingIntent.getBroadcast(context, id.hashCode(), Intent("${BuildConfig.APPLICATION_ID}.CALLER_PROOF").setPackage(context.packageName), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT)
            val receiver = object : android.os.ResultReceiver(main) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    if (!active) return
                    try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); main.removeCallbacks(poll); main.post(poll) }
                    catch (error: Exception) { active = false; cleanup(); onError(IllegalStateException("无法连接模块凭证服务，请重新从米家读取", error)) }
                }
            }
            context.startActivity(Intent().setComponent(ComponentName(BuildConfig.APPLICATION_ID, "${BuildConfig.APPLICATION_ID}.xiaomi.MiHomeAuthorizeActivity"))
                .putExtra("request", id).putExtra("account", account).putExtra("model", model)
                .putExtra("caller", proof).putExtra("ready", receiver).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) { try { cancel() } catch (_: Exception) { }; onError(IllegalStateException("无法打开模块授权页，请确认桥接模块已安装且启用", error)) }
        return { main.removeCallbacks(poll); try { cancel() } catch (_: Exception) { active = false } }
    }
    fun credential(context: Context, mac: String, model: String, done: (ByteArray) -> Unit, failed: (Throwable) -> Unit): () -> Unit {
        val account = account(context); val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val timeout = Runnable { if (active.compareAndSet(true, false)) failed(IllegalStateException("读取体脂秤凭证超时，请检查模块 root 授权")) }
        main.postDelayed(timeout, 30_000)
        try { executor.execute {
            try {
                val result = call(context, account, "credential", mac)
                require(result.getString("model") == model) { "体脂秤型号与凭证不匹配" }
                val key = result.getByteArray("key") ?: error("体脂秤凭证不可用")
                main.post {
                    main.removeCallbacks(timeout)
                    if (active.compareAndSet(true, false) && account(context) == account) done(key)
                    else key.fill(0)
                }
            } catch (error: Exception) { main.post { main.removeCallbacks(timeout); if (active.compareAndSet(true, false)) failed(error) } }
        } } catch (error: java.util.concurrent.RejectedExecutionException) { main.removeCallbacks(timeout); active.set(false); failed(IllegalStateException("凭证读取繁忙，请稍后重试")) }
        return { active.set(false); main.removeCallbacks(timeout) }
    }
    fun remove(context: Context, account: String, mac: String) { call(context, account, "remove", mac) }
}
