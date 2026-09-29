package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore

object BooheeMeasureHook{
    private const val MANAGER="com.heytap.device.ui.weight.scale.boohee.BHDeviceManager"
    fun install(cl:ClassLoader){val clazz=XposedHelpers.findClass(MANAGER,cl);val hooks=XposedBridge.hookAllMethods(clazz,"startMeasureReceive",object:XC_MethodHook(){override fun beforeHookedMethod(param:MethodHookParam){val listener=param.args.getOrNull(0)?:return;val mac=param.args.getOrNull(1) as? String?:return;val userTag=param.args.getOrNull(2) as? String;val context=OppoReflect.context()?:return;if(!AfuBindingStore.contains(context,mac))return;param.result=null;val body=OppoBodyComposition(cl,OppoObjectFactory(cl));body.prepare(param.thisObject,userTag);val profile=body.currentUserProfile(param.thisObject);BridgeLog.i("AFU measure start ${BridgeLog.maskedMac(mac)} age=${profile.age} male=${profile.male}");DeviceRegistry.afu().measure(context,mac,profile,{kg->OppoReflect.postMain{OppoReflect.callFirst(listener,listOf("onProcessWeight"),kg)}},{record->val scale=body.buildScaleModel(param.thisObject,mac,record);OppoReflect.postMain{if(!OppoReflect.callFirst(listener,listOf("onLockWeight"),record.weightKg,scale))BridgeLog.e("MeasureListener.onLockWeight not found")}},{e->BridgeLog.e("AFU measure failed",e);OppoReflect.postMain{OppoReflect.callFirst(listener,listOf("onFail"),e.message?:"AFU measure failed")}})}});check(hooks.isNotEmpty());HookInstallState.measure=true;BridgeLog.i("Boohee measure hook installed")}
}
