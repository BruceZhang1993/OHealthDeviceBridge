package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry

object BooheeSearchHook {
    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BooheeScaleBinder", cl)
        val hooks = XposedBridge.hookAllMethods(clazz, "startSearch", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!OppoReflect.readString(param.thisObject, "getTargetModel", "targetModel").equals(BridgeConstants.AFU_MODEL, true)) return
                param.result = null
                OppoReflect.postMain {
                    val onError = param.args[3]
                    try {
                        val context = checkNotNull(OppoReflect.context()) { "OPPO context unavailable" }
                        OppoAfuRuntime.stopAll()
                        BooheeBindHook.pauseNative(cl, checkNotNull(OppoReflect.read(param.thisObject, "manager")))
                        val ticket = OppoAfuRuntime.Ticket(OppoAccount.key(cl))
                        OppoAfuRuntime.scans[param.thisObject] = ticket
                        val timeout = (param.args[0] as Number).toLong().coerceIn(1, 120) * 1000L
                        fun finish(action: () -> Unit) {
                            try { if (ticket.valid(cl)) action() }
                            finally {
                                ticket.active = false
                                ticket.cancelScan = null
                                if (OppoAfuRuntime.scans[param.thisObject] === ticket) OppoAfuRuntime.scans.remove(param.thisObject)
                            }
                        }
                        ticket.cancelScan = DeviceRegistry.afu().scan(context, timeout,
                            { found -> finish {
                                OppoReflect.call(param.args[1], "invoke", OppoObjectFactory(cl).createBindable(found.mac))
                            } },
                            { finish { OppoReflect.call(param.args[2], "invoke") } },
                            { error -> finish { OppoReflect.call(onError, "invoke", error) } })
                    } catch (error: Exception) {
                        BridgeLog.e("AFU scan failed", error)
                        OppoReflect.callFirst(onError, listOf("invoke"), error)
                    }
                }
            }
        })
        check(hooks.isNotEmpty())
        HookInstallState.scan = true
    }
}
