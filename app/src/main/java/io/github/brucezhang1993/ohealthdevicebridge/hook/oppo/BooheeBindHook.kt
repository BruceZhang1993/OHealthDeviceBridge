package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.*
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.store.AfuBindingStore

object BooheeBindHook{
    private const val MANAGER="com.heytap.device.ui.weight.scale.boohee.BHDeviceManager"
    fun install(cl:ClassLoader){val clazz=XposedHelpers.findClass(MANAGER,cl);val hooks=XposedBridge.hookAllMethods(clazz,"bind",object:XC_MethodHook(){override fun beforeHookedMethod(param:MethodHookParam){val device=param.args.getOrNull(0)?:return;val model=OppoReflect.readString(device,"getModel","model");if(!model.equals(BridgeConstants.AFU_MODEL,true))return;val mac=OppoReflect.readString(device,"getMac","mac")?:return;val listener=param.args.getOrNull(1)?:return;val context=OppoReflect.context()?:return;param.result=null;DeviceRegistry.afu().verifyForBind(context,mac,{AfuBindingStore.add(context,mac);OppoReflect.postMain{runCatching{OppoReflect.call(param.thisObject,"onBindSucceededOnThisPhone",mac)}.onFailure{BridgeLog.e("failed to mark AFU bound on this phone",it)};if(!OppoReflect.callFirst(listener,listOf("onSuccess"),device))BridgeLog.e("BindListener.onSuccess not found")}}, {e->BridgeLog.e("AFU bind verification failed",e);OppoReflect.postMain{OppoReflect.callFirst(listener,listOf("onFail","onFailure","onError","onFailed"),e.message?:"AFU bind failed")}})}});check(hooks.isNotEmpty());HookInstallState.bind=true;BridgeLog.i("Boohee bind hook installed")}
}
