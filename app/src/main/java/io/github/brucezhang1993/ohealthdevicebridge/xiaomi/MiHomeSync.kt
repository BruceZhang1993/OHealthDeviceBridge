package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.Context
import android.os.Bundle
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal object MiHomeSync {
    class Request(val id: String, val account: String, val model: String) {
        @Volatile var active = true
        @Volatile var state = "WAITING"
        @Volatile var message = "等待模块授权"
        @Volatile var identity: String? = null
        @Volatile var devices = emptyList<MiCloudDevice>()
        @Volatile var selected: MiCloudDevice? = null
        @Volatile var busy = false
    }
    private val executor = ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(1)).apply { allowCoreThreadTimeOut(true) }
    @Volatile private var current: Request? = null
    @Synchronized fun begin(account: String, model: String, id: String = UUID.randomUUID().toString()): Request {
        current?.active = false
        return Request(id, account, model).also { current = it }
    }
    fun find(id: String): Request? = current?.takeIf { it.id == id && it.active }
    fun cancel(id: String) { find(id)?.let { it.active = false; it.state = "CANCELLED"; it.message = "已取消读取" } }
    fun list(context: Context, request: Request, done: () -> Unit) = work(request, done) {
        val session = MiHomeRootReader.read(context)
        MiCredentialStore(context).invalidateIdentity(session.identity)
        val devices = MiCloudClient(session, cancelled = { !request.active }).devices().filter { it.model == request.model }
        checkIdentity(context, request, session)
        request.identity = session.identity
        request.devices = devices
        request.state = "LISTED"
        request.message = if (devices.isEmpty()) "米家当前地区没有已绑定的该型号体脂秤" else "请选择已绑定的体脂秤"
    }
    fun select(context: Context, request: Request, device: MiCloudDevice, done: () -> Unit) = work(request, done) {
        check(device in request.devices)
        val session = MiHomeRootReader.read(context)
        MiCredentialStore(context).invalidateIdentity(session.identity)
        if (request.identity != session.identity) throw MiHomeFailure("ACCOUNT_CHANGED", "米家账号或地区发生变化，请重新读取")
        // Re-fetch the list: a device removed from Mi Home after listing cannot be selected.
        val client = MiCloudClient(session, cancelled = { !request.active })
        val actual = client.devices().firstOrNull { it.did == device.did && it.mac == device.mac && it.model == device.model }
            ?: throw MiHomeFailure("DEVICE_REMOVED", "设备已从米家移除，请重新读取")
        val key = client.beaconKey(actual)
        try {
            checkIdentity(context, request, session)
            MiCredentialStore(context).put(request.account, MiCredential(actual, key, session.identity))
            if (!request.active) { MiCredentialStore(context).remove(request.account, actual.mac); return@work }
            request.selected = actual; request.state = "READY"; request.message = "凭证已获取，返回 OPPO 健康后请站上体脂秤完成验证"
        } finally { key.fill(0) }
    }
    private fun checkIdentity(context: Context, request: Request, before: MiHomeSession) {
        if (!request.active) throw MiHomeFailure("CANCELLED", "已取消读取")
        val after = MiHomeRootReader.read(context)
        if (before.identity != after.identity) {
            MiCredentialStore(context).invalidateIdentity(after.identity)
            throw MiHomeFailure("ACCOUNT_CHANGED", "米家账号或地区发生变化，请重新读取")
        }
        if (!request.active) throw MiHomeFailure("CANCELLED", "已取消读取")
    }
    @Synchronized private fun work(request: Request, done: () -> Unit, block: () -> Unit) {
        if (request.busy || !request.active) return
        request.busy = true; request.state = "RUNNING"; request.message = "正在读取米家…"
        try { executor.execute {
            try { if (request.active) block() }
            catch (error: MiHomeFailure) { if (request.active) { request.state = "ERROR"; request.message = error.message ?: "读取失败" } }
            catch (_: Exception) { if (request.active) { request.state = "ERROR"; request.message = "读取失败，请检查 root 授权、网络和米家登录后重试" } }
            finally { request.busy = false; done() }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { request.busy = false; request.state = "ERROR"; request.message = "上一项读取尚未结束，请稍后重试"; done() }
    }
    fun status(request: Request) = Bundle().apply {
        putString("state", request.state); putString("message", request.message)
        request.selected?.let { putString("mac", it.mac); putString("name", it.name); putString("model", it.model) }
    }
}
