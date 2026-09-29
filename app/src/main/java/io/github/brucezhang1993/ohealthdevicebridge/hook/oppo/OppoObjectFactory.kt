package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.XposedHelpers
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants

class OppoObjectFactory(private val classLoader:ClassLoader){
    fun createBhDevice(mac:String):Any{val clazz=XposedHelpers.findClass(BH_DEVICE_MODEL,classLoader);val raw=XposedHelpers.newInstance(clazz,mac,BridgeConstants.AFU_MODEL);OppoReflect.tryCall(raw,"setDeviceId",BridgeConstants.AFU_MODEL);OppoReflect.tryCall(raw,"setDeviceMac",mac);OppoReflect.tryCall(raw,"setDeviceModel",BridgeConstants.AFU_MODEL);OppoReflect.tryCall(raw,"setDeviceName",BridgeConstants.AFU_MODEL);runCatching{XposedHelpers.setBooleanField(raw,"isConnectScale",true)};return raw}
    fun createBindable(mac:String):Any{val bindable=XposedHelpers.findClass(BINDABLE_SCALE_DEVICE,classLoader);val vendorClass=XposedHelpers.findClass(SCALE_VENDOR,classLoader);val boohee=XposedHelpers.getStaticObjectField(vendorClass,"BOOHEE");return XposedHelpers.newInstance(bindable,boohee,mac,BridgeConstants.AFU_MODEL,BridgeConstants.AFU_MODEL,true,createBhDevice(mac))}
    companion object{const val BH_DEVICE_MODEL="com.boohee.scale_sdk.device.BHDeviceModel";const val BH_SCALE_MODEL="com.boohee.scale_sdk.data.BHScaleModel";const val BH_USER_MODEL="com.boohee.scale_sdk.user.BHUserModel";const val BH_SCALE_MANAGER="com.boohee.scale_sdk.BHScaleManager";const val BINDABLE_SCALE_DEVICE="com.heytap.device.ui.weight.scale.BindableScaleDevice";const val SCALE_VENDOR="com.heytap.device.ui.weight.scale.ScaleVendor"}
}
