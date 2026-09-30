package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants

class OppoObjectFactory(private val classLoader: ClassLoader) {
    fun createBhDevice(mac: String): Any {
        val clazz = classLoader.loadClass(BH_DEVICE_MODEL)
        val raw = clazz.getConstructor(String::class.java, String::class.java)
            .newInstance(mac, BridgeConstants.AFU_MODEL)
        OppoReflect.call(raw, "setDeviceId", BridgeConstants.AFU_MODEL)
        OppoReflect.call(raw, "setDeviceMac", mac)
        OppoReflect.call(raw, "setDeviceModel", BridgeConstants.AFU_MODEL)
        OppoReflect.call(raw, "setDeviceName", BridgeConstants.AFU_MODEL)
        // The SDK algorithm's connect flag also sends B3 to the global Boohee transport.
        // The OPPO route uses BindableScaleDevice.isConnectScale, independently of this flag.
        OppoReflect.set(raw, "isConnectScale", false)
        return raw
    }

    fun createBindable(mac: String): Any {
        val vendorClass = classLoader.loadClass(SCALE_VENDOR)
        val boohee = vendorClass.getField("BOOHEE").get(null)
        return classLoader.loadClass(BINDABLE_SCALE_DEVICE)
            .getConstructor(
                vendorClass,
                String::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Any::class.java
            )
            .newInstance(
                boohee,
                mac,
                BridgeConstants.AFU_DISPLAY_NAME,
                BridgeConstants.AFU_MODEL,
                true,
                createBhDevice(mac)
            )
    }

    fun createWeightDevice(mac: String, connected: Boolean, deviceType: Int): Any {
        val item = classLoader.loadClass(WEIGHT_DEVICE_INFO).getConstructor().newInstance()
        OppoReflect.set(item, "deviceName", BridgeConstants.AFU_DISPLAY_NAME)
        OppoReflect.set(item, "deviceUniqueId", mac)
        OppoReflect.set(item, "id", mac)
        OppoReflect.set(item, "mac", mac)
        OppoReflect.set(item, "connectScale", true)
        OppoReflect.set(item, "connected", connected)
        OppoReflect.set(item, "model", BridgeConstants.AFU_MODEL)
        OppoReflect.set(item, "deviceType", deviceType)
        OppoReflect.set(item, "manufacturer", "薄荷健康")
        return item
    }

    companion object {
        const val BH_DEVICE_MODEL = "com.boohee.scale_sdk.device.BHDeviceModel"
        const val BH_SCALE_MODEL = "com.boohee.scale_sdk.data.BHScaleModel"
        const val BH_USER_MODEL = "com.boohee.scale_sdk.user.BHUserModel"
        const val BH_SCALE_MANAGER = "com.boohee.scale_sdk.BHScaleManager"
        const val BINDABLE_SCALE_DEVICE = "com.heytap.device.ui.weight.scale.BindableScaleDevice"
        const val SCALE_VENDOR = "com.heytap.device.ui.weight.scale.ScaleVendor"
        const val WEIGHT_DEVICE_INFO =
            "com.heytap.health.devicemanager.third_device.weightscale.WeightScaleDeviceInfo"
    }
}
