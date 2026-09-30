package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuB1Driver
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore

object BooheeDeviceOverlayHook {
    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BooheeDeviceLoader", cl)
        val hooks = XposedBridge.hookAllMethods(clazz, "convertBoohee", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val context = OppoReflect.context() ?: return
                val account = try { OppoAccount.key(cl) } catch (_: Exception) { return }
                val original = param.result as? List<*> ?: return
                for (item in original.filterNotNull()) {
                    if (OppoReflect.readString(item, "getModel", "model").equals(io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants.AFU_MODEL, true)) {
                        OppoReflect.readString(item, "getMac", "mac")?.let { AfuBindingStore.add(context, account, it) }
                    }
                }
                val existing = original.filterNotNull().mapNotNull { OppoReflect.readString(it, "getMac", "mac") }
                    .map(AfuBindingStore::normalize).toSet()
                val items = ArrayList<Any>(original.filterNotNull())
                val factory = OppoObjectFactory(cl)
                for (mac in AfuBindingStore.macs(context, account) - existing) {
                    items.add(factory.createWeightDevice(mac, AfuB1Driver.isConnected(mac)))
                }
                param.result = items
            }
        })
        check(hooks.isNotEmpty())
    }
}
