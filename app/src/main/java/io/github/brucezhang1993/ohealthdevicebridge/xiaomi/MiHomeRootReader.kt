package io.github.brucezhang1993.ohealthdevicebridge.xiaomi

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal object MiHomeRootReader {
    private val main = Handler(Looper.getMainLooper())
    @Synchronized fun read(context: Context): MiHomeSession {
        check(Looper.myLooper() != Looper.getMainLooper())
        try { context.packageManager.getApplicationInfo("com.xiaomi.smarthome", 0) }
        catch (_: android.content.pm.PackageManager.NameNotFoundException) { throw MiHomeFailure("NOT_INSTALLED", "请先安装米家并绑定体脂秤") }
        if (!context.getSystemService(android.os.UserManager::class.java).isUserUnlocked)
            throw MiHomeFailure("USER_LOCKED", "请先解锁手机")
        val shell = Shell.getShell()
        if (!shell.isRoot) { shell.close(); throw MiHomeFailure("ROOT_DENIED", "模块未获得 root 权限，请在 root 管理器中授权桥接模块后重试") }
        val future = CompletableFuture<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) { future.complete(service) }
            override fun onServiceDisconnected(name: ComponentName) { future.completeExceptionally(MiHomeFailure("ROOT_UNAVAILABLE", "root 服务已断开，请重试")) }
            override fun onNullBinding(name: ComponentName) { future.completeExceptionally(MiHomeFailure("ROOT_UNAVAILABLE", "root 服务不可用")) }
        }
        main.post { if (!future.isCancelled) try { RootService.bind(Intent(context, MiHomeRootService::class.java).putExtra("moduleUid", context.applicationInfo.uid), connection) } catch (_: Exception) { future.completeExceptionally(MiHomeFailure("ROOT_UNAVAILABLE", "无法启动模块 root 服务")) } }
        try {
            val bundle = IRootMiHomeSession.Stub.asInterface(future.get(15, TimeUnit.SECONDS)).readSession()
            bundle.getString("error")?.let { throw MiHomeFailure(it, bundle.getString("message") ?: "米家缓存不可用") }
            return MiHomeSession(bundle.getString("user")!!, bundle.getString("cuser").orEmpty(), bundle.getString("token")!!,
                bundle.getString("security")!!, bundle.getString("region")!!, bundle.getLong("timeDiff"))
        } catch (error: MiHomeFailure) {
            if (error.code in setOf("NOT_LOGGED_IN", "CACHE_UNSUPPORTED", "ACCOUNT_CHANGED")) MiCredentialStore(context).clear()
            throw error
        }
        catch (_: Exception) { throw MiHomeFailure("ROOT_UNAVAILABLE", "root 服务超时或不可用，请检查模块授权后重试") }
        finally {
            future.cancel(false)
            main.post { RootService.unbind(connection) }
        }
    }
}
