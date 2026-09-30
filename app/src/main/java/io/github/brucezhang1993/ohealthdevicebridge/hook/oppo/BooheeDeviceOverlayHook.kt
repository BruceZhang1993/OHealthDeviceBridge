package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuB1Driver
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore
import java.lang.reflect.Proxy

object BooheeDeviceOverlayHook {
    private const val LOADER = "com.heytap.device.ui.weight.scale.boohee.BooheeDeviceLoader"
    private const val LEGACY_DEVICE_TYPE_FALLBACK = 100

    fun install(cl: ClassLoader) {
        val clazz = XposedHelpers.findClass(LOADER, cl)

        val convertHooks = XposedBridge.hookAllMethods(clazz, "convertBoohee", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                param.result = overlay("convertBoohee", cl, param.result)
            }
        })
        check(convertHooks.isNotEmpty()) { "BooheeDeviceLoader.convertBoohee not found" }

        // Some host paths short-circuit conversion when the cloud/local source list is empty.
        // Wrap the final loader stream too, so a locally-bound AFU is still emitted even when
        // convertBoohee() never receives a source row for the unknown model.
        val loadHooks = XposedBridge.hookAllMethods(clazz, "load", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.hasThrowable()) return
                val result = param.result ?: return
                param.result = if (result is List<*>) {
                    overlay("load-direct", cl, result)
                } else {
                    wrapListStream(cl, result)
                }
            }
        })

        BridgeLog.i(
            "bound-device overlay installed convert=${convertHooks.size} load=${loadHooks.size}"
        )
    }

    private fun wrapListStream(cl: ClassLoader, stream: Any): Any {
        return try {
            val map = stream.javaClass.methods.firstOrNull {
                it.name == "map" && it.parameterCount == 1 && it.parameterTypes[0].isInterface
            } ?: run {
                BridgeLog.e("BooheeDeviceLoader.load result has no compatible map() method: ${stream.javaClass.name}")
                return stream
            }
            val functionType = map.parameterTypes[0]
            val function = Proxy.newProxyInstance(
                functionType.classLoader ?: cl,
                arrayOf(functionType)
            ) { proxy, method, args ->
                when (method.name) {
                    "apply" -> overlay("load-stream", cl, args?.firstOrNull())
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    "toString" -> "AFU bound-device overlay mapper"
                    else -> null
                }
            }
            map.invoke(stream, function) ?: stream
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            BridgeLog.e("failed to wrap BooheeDeviceLoader.load result", error)
            stream
        }
    }

    private fun overlay(source: String, cl: ClassLoader, value: Any?): Any? {
        val original = value as? List<*> ?: return value
        return try {
            val context = OppoReflect.context() ?: return value
            val account = OppoAccount.key(cl)
            val originalItems = original.filterNotNull()

            // If OPPO cloud did accept AFU, adopt that row into the local binding store and let
            // the native row win. This also keeps migrations resilient after clearing module data.
            for (item in originalItems) {
                if (OppoReflect.readString(item, "getModel", "model")
                        .equals(BridgeConstants.AFU_MODEL, true)
                ) {
                    OppoReflect.readString(item, "getMac", "mac")?.let {
                        AfuBindingStore.add(context, account, it)
                    }
                }
            }

            val nativeDeviceType = originalItems.asSequence()
                .mapNotNull {
                    (OppoReflect.read(it, "getDeviceType", "deviceType") as? Number)?.toInt()
                }
                .firstOrNull()
            if (nativeDeviceType != null) {
                AfuBindingStore.setBodyFatDeviceType(context, nativeDeviceType)
            }
            val deviceType = nativeDeviceType
                ?: AfuBindingStore.bodyFatDeviceType(context)
                ?: LEGACY_DEVICE_TYPE_FALLBACK

            val existing = originalItems.mapNotNull { OppoReflect.readString(it, "getMac", "mac") }
                .map(AfuBindingStore::normalize)
                .toSet()
            val bound = AfuBindingStore.macs(context, account)
            val missing = bound - existing
            if (missing.isEmpty()) return value

            val items = ArrayList<Any>(originalItems.size + missing.size)
            items.addAll(originalItems)
            val factory = OppoObjectFactory(cl)
            for (mac in missing) {
                items += factory.createWeightDevice(mac, AfuB1Driver.isConnected(mac), deviceType)
            }

            BridgeLog.i(
                "bound-device overlay source=$source original=${originalItems.size} " +
                    "bound=${bound.size} injected=${missing.size} deviceType=$deviceType"
            )
            items
        } catch (error: Throwable) {
            if (error is VirtualMachineError) throw error
            BridgeLog.e("bound-device overlay failed source=$source", error)
            value
        }
    }
}
