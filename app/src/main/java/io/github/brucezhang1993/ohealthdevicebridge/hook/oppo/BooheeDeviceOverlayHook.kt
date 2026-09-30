package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore

/** Best-effort local overlay used if OPPO cloud does not persist the unknown AFU model. */
object BooheeDeviceOverlayHook{
    private const val LOADER="com.heytap.device.ui.weight.scale.boohee.BooheeDeviceLoader"
    fun install(cl:ClassLoader){val clazz=XposedHelpers.findClass(LOADER,cl);XposedBridge.hookAllMethods(clazz,"convertBoohee",object:XC_MethodHook(){@Suppress("UNCHECKED_CAST") override fun afterHookedMethod(param:MethodHookParam){val context=OppoReflect.context()?:return;val macs=AfuBindingStore.macs(context);if(macs.isEmpty())return;val original=param.result as? List<Any>?:return;if(original.any{OppoReflect.readString(it,"getModel","model").equals(BridgeConstants.AFU_MODEL,true)})return;val template=original.firstOrNull()?:return;val replacement=ArrayList(original);macs.forEach{mac->val item=runCatching{template.javaClass.getDeclaredConstructor().apply{isAccessible=true}.newInstance()}.getOrElse{BridgeLog.e("cannot instantiate WeightScaleDeviceInfo overlay",it);return@forEach};runCatching{OppoReflect.set(item,"deviceName",BridgeConstants.AFU_DISPLAY_NAME);OppoReflect.set(item,"deviceUniqueId",mac);OppoReflect.set(item,"mac",mac);OppoReflect.set(item,"connectScale",true);OppoReflect.set(item,"connected",false);OppoReflect.set(item,"model",BridgeConstants.AFU_MODEL);OppoReflect.set(item,"manufacturer","薄荷健康");OppoReflect.read(template,"getDeviceType","deviceType")?.let{OppoReflect.set(item,"deviceType",it)};replacement+=item}.onFailure{BridgeLog.e("failed to populate AFU local overlay",it)}};param.result=replacement}})}
}
