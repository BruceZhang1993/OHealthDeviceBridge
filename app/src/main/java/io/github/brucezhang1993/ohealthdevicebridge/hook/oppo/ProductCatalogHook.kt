package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore
import java.lang.reflect.Method

object ProductCatalogHook {
    private const val FRAGMENT =
        "com.heytap.health.watchpair.watchconnect.pair.producttype.ProductCategorFragment"
    private const val PRODUCT_CATEGORY =
        "com.heytap.health.devicemanager.processor.cloudaccess.response.ProductCategoryRsp\$ProductCategory"
    private const val PRODUCT =
        "com.heytap.health.devicemanager.processor.cloudaccess.response.ProductCategoryRsp\$Product"
    private val known = setOf("3102", "3105", "3107", "3501", "3504")

    fun install(cl: ClassLoader) {
        check(HookInstallState.coreReady)
        val fragment = XposedHelpers.findClass(FRAGMENT, cl)
        val category = XposedHelpers.findClass(PRODUCT_CATEGORY, cl)
        val target = findRenderMethod(fragment, category)
            ?: error("Product category render method not found")
        XposedBridge.hookMethod(target, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val value = param.args.firstOrNull(category::isInstance) ?: return
                inject(value, cl)
            }
        })
        HookInstallState.catalog = true
        BridgeLog.i("product catalog hook installed at ${target.name}")
    }

    private fun findRenderMethod(fragment: Class<*>, category: Class<*>): Method? =
        fragment.declaredMethods.firstOrNull { it.name == "showScreen\$lambda\$4\$lambda\$3" }
            ?: fragment.declaredMethods.firstOrNull {
                it.name.contains("showScreen") && it.parameterTypes.any(category::isAssignableFrom)
            }

    @Suppress("UNCHECKED_CAST")
    private fun inject(category: Any, cl: ClassLoader) {
        val original = OppoReflect.read(category, "getModelList", "modelList") as? List<Any> ?: return
        val template = original.firstOrNull { modelOf(it) in known } ?: return
        val deviceType = (OppoReflect.read(template, "getDeviceType", "deviceType") as? Number)?.toInt() ?: 0

        OppoReflect.context()?.let { context ->
            AfuBindingStore.setBodyFatDeviceType(context, deviceType)
        }

        if (original.any { modelOf(it).equals(BridgeConstants.AFU_MODEL, true) }) return

        val afu = XposedHelpers.newInstance(XposedHelpers.findClass(PRODUCT, cl))
        OppoReflect.set(afu, "deviceType", deviceType)
        OppoReflect.set(afu, "deviceName", BridgeConstants.AFU_DISPLAY_NAME)
        OppoReflect.set(afu, "model", BridgeConstants.AFU_MODEL)
        OppoReflect.set(afu, "thirdPartyModel", BridgeConstants.AFU_MODEL)
        OppoReflect.set(afu, "slogan", BridgeConstants.AFU_SUBTITLE)
        OppoReflect.set(afu, "imageUrl", OppoReflect.read(template, "getImageUrl", "imageUrl"))
        runCatching { OppoReflect.set(afu, "imageList", emptyList<Any>()) }
            .onFailure { if (it is VirtualMachineError) throw it }

        val replacement = ArrayList(original)
        replacement += afu
        OppoReflect.set(category, "modelList", replacement)
        BridgeLog.i(
            "product injected: ${BridgeConstants.AFU_DISPLAY_NAME} (${BridgeConstants.AFU_MODEL}) deviceType=$deviceType"
        )
    }

    private fun modelOf(product: Any) = OppoReflect.readString(product, "getModel", "model")
}
