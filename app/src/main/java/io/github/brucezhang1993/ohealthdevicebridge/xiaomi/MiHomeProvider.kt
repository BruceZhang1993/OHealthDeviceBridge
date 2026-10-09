package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi.XiaomiModels

internal object MiHomeCallerPolicy {
    fun allowed(callingUid: Int, hostUid: Int, moduleUid: Int) = callingUid == hostUid && callingUid / 100000 == moduleUid / 100000
}

class MiHomeProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = checkNotNull(context)
        val hostUid = ctx.packageManager.getApplicationInfo(BridgeConstants.TARGET_PACKAGE, 0).uid
        if (!MiHomeCallerPolicy.allowed(Binder.getCallingUid(), hostUid, ctx.applicationInfo.uid)) throw SecurityException("Caller not allowed")
        val account = extras?.getString("account") ?: throw IllegalArgumentException("Missing account")
        require(account.isNotEmpty() && account.length <= 256)
        return try {
            when (method) {
                "begin" -> {
                    val model = extras.getString("model") ?: error("Missing model")
                    require(XiaomiModels.needsKey(model))
                    Bundle().apply { putString("id", MiHomeSync.begin(account, model).id) }
                }
                "status", "cancel", "list" -> {
                    val request = MiHomeSync.find(arg.orEmpty())?.takeIf { it.account == account }
                    if (request == null) Bundle().apply { putString("state", "CANCELLED"); putString("message", "读取已取消或模块已重启，请重试") }
                    else if (method == "cancel") { MiHomeSync.cancel(request.id); Bundle() }
                    else if (method == "list") Bundle().apply { putStringArrayList("devices", ArrayList(request.devices.map { it.name })) }
                    else MiHomeSync.status(request)
                }
                "credential" -> {
                    val mac = arg.orEmpty(); require(mac.matches(Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}")))
                    val identity = MiHomeRootReader.read(ctx).identity
                    val store = MiCredentialStore(ctx)
                    store.invalidateIdentity(identity)
                    val credential = store.get(account, mac, identity) ?: throw MiHomeFailure("KEY_UNAVAILABLE", "设备凭证已失效，请在添加设备页面重新从米家读取")
                    try { Bundle().apply {
                        putByteArray("key", credential.key.copyOf()); putString("model", credential.device.model)
                    } } finally { credential.key.fill(0) }
                }
                "remove" -> { MiCredentialStore(ctx).remove(account, arg.orEmpty()); Bundle() }
                else -> throw IllegalArgumentException("Unknown operation")
            }
        } catch (error: MiHomeFailure) { Bundle().apply { putString("error", error.code); putString("message", error.message) } }
        catch (_: Exception) { Bundle().apply { putString("error", "UNAVAILABLE"); putString("message", "模块服务不可用，请打开模块检查授权后重试") } }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = throw UnsupportedOperationException()
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
}
