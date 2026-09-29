package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry

object BooheeSearchHook{
    private const val BINDER="com.heytap.device.ui.weight.scale.boohee.BooheeScaleBinder"
    fun install(cl:ClassLoader){val clazz=XposedHelpers.findClass(BINDER,cl);val hooks=XposedBridge.hookAllMethods(clazz,"startSearch",object:XC_MethodHook(){override fun beforeHookedMethod(param:MethodHookParam){val target=OppoReflect.readString(param.thisObject,"getTargetModel","targetModel");if(!target.equals(BridgeConstants.AFU_MODEL,true))return;val context=OppoReflect.context()?:return;val onDevice=param.args.getOrNull(1)?:return;val onTimeout=param.args.getOrNull(2)?:return;val onError=param.args.getOrNull(3);param.result=null;DeviceRegistry.afu().scan(context,15000L,{found->val bindable=OppoObjectFactory(cl).createBindable(found.mac);OppoReflect.postMain{OppoReflect.callFirst(onDevice,listOf("invoke"),bindable)}},{OppoReflect.postMain{OppoReflect.callFirst(onTimeout,listOf("invoke"))}},{e->BridgeLog.e("AFU scan failed",e);if(onError!=null)OppoReflect.postMain{OppoReflect.callFirst(onError,listOf("invoke"),e.message?:"AFU scan failed")}})}});check(hooks.isNotEmpty());HookInstallState.scan=true;BridgeLog.i("Boohee scan hook installed")}
}
