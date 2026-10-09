package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.Intent
import android.os.Binder
import android.os.Bundle
import com.topjohnwu.superuser.ipc.RootService
import java.io.File

/** No caller-controlled path, command, package or Android user. Never alters Mi Home data. */
class MiHomeRootService : RootService() {
    @android.annotation.SuppressLint("SdCardPath") // Fixed cross-app root snapshot, not module-owned storage.
    override fun onBind(intent: Intent): android.os.IBinder {
        val ownerUid = intent.getIntExtra("moduleUid", -1)
        require(ownerUid >= 0 && ownerUid % 100000 == applicationInfo.uid % 100000 &&
            packageManager.getPackagesForUid(ownerUid).orEmpty().contains(packageName))
        return object : IRootMiHomeSession.Stub() {
            override fun readSession(): Bundle {
                check(Binder.getCallingUid() == ownerUid) { "Caller not allowed" }
                return try {
                    val user = ownerUid / 100000
                    val dir = File("/data/user/$user/com.xiaomi.smarthome/shared_prefs")
                    if (!dir.isDirectory) throw MiHomeFailure("NOT_LOGGED_IN", "请先解锁手机，并在米家登录和绑定体脂秤")
                    fun read(name: String): ByteArray {
                        val file = File(dir, "$name.xml")
                        if (!file.exists()) return "<map/>".toByteArray()
                        require(file.canonicalFile.parentFile == dir.canonicalFile && file.length() <= 512 * 1024)
                        return file.inputStream().use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 512 * 1024); out.write(buffer, 0, n) }
                            out.toByteArray()
                        }
                    }
                    // Read twice to reject an account/region switch during the snapshot.
                    val account = read("com.xiaomi.smarthome.account")
                    val identity = read("AndroidIdUtils")
                    val region = read("com.xiaomi.smarthome.globaldynamicsetting")
                    val session = MiHomeSessionCodec.parse(account, identity, region)
                    if (!account.contentEquals(read("com.xiaomi.smarthome.account")) || !region.contentEquals(read("com.xiaomi.smarthome.globaldynamicsetting")))
                        throw MiHomeFailure("ACCOUNT_CHANGED", "米家账号或地区发生变化，请重试")
                    Bundle().apply {
                        putString("user", session.userId); putString("cuser", session.cUserId)
                        putString("token", session.serviceToken); putString("security", session.security)
                        putString("region", session.region); putLong("timeDiff", session.timeDiff)
                    }
                } catch (error: MiHomeFailure) { Bundle().apply { putString("error", error.code); putString("message", error.message) } }
                catch (_: Exception) { Bundle().apply { putString("error", "CACHE_UNSUPPORTED"); putString("message", "无法读取米家缓存，请检查 root 权限和米家版本") } }
            }
        }
    }
}
