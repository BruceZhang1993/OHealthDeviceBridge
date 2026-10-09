package io.github.brucezhang1993.ohealthdevicebridge.hook.oppo

import de.robv.android.xposed.*
import io.github.brucezhang1993.ohealthdevicebridge.BridgeLog
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuB1Driver
import io.github.brucezhang1993.ohealthdevicebridge.device.DeviceRegistry
import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.store.BridgeBindingStore

internal object BooheeKeepAliveHook {
    fun install(cl: ClassLoader) {
        val manager = XposedHelpers.findClass("com.heytap.device.ui.weight.scale.boohee.BHDeviceManager", cl)
        OppoAfuLifecycleHook.hook(manager, "startKeepAlive") { param ->
            val mac = param.args[0] as? String ?: return@hook
            val context = OppoReflect.context() ?: return@hook
            val account = try { OppoAccount.key(cl) } catch (_: Exception) { return@hook }
            val model = BridgeBindingStore.model(context, account, mac) ?: return@hook
            param.result = null
            if (model != BridgeConstants.AFU_MODEL) return@hook
            OppoReflect.postMain {
                val measure = OppoAfuRuntime.measure
                if (measure != null && measure.mac?.let { DeviceRegistry.byModel(measure.model)?.hasSession(it) } != true) {
                    measure.active = false
                    OppoAfuRuntime.measure = null
                }
                if (OppoAfuRuntime.measure?.active == true || OppoAfuRuntime.bind?.active == true ||
                    OppoAfuRuntime.scans.values.any { it.active }) return@postMain
                val current = OppoAfuRuntime.background
                if (current?.active == true && current.mac.equals(mac, true) && AfuB1Driver.hasSession(mac)) {
                    param.args.getOrNull(1)?.let { OppoReflect.callFirst(it, listOf("invoke"), AfuB1Driver.isConnected(mac)) }
                    return@postMain
                }
                OppoAfuRuntime.stopBackground()
                val ticket = OppoAfuRuntime.Ticket(account, mac)
                val onState = param.args.getOrNull(1)
                try {
                    // Stop original SDK ownership before installing the AFU background ticket.
                    OppoReflect.call(param.thisObject, "forceStopKeepAlive", "AFU history transport")
                    OppoAfuRuntime.background = ticket
                    val body = OppoBodyComposition(cl, OppoObjectFactory(cl))
                    body.prepare(param.thisObject, context, null)
                    AfuB1Driver.receiveHistory(context, mac, body.currentUserProfile(param.thisObject),
                        { record -> if (ticket.valid(cl)) body.importHistory(param.thisObject, mac, record) },
                        { connected -> if (ticket.valid(cl) && onState != null) OppoReflect.call(onState, "invoke", connected) },
                        { error -> if (ticket.valid(cl)) {
                            ticket.gaveUp = true
                            BridgeLog.e("AFU background history failed", error)
                            if (onState != null) OppoReflect.callFirst(onState, listOf("invoke"), false)
                        } })
                } catch (error: Exception) {
                    ticket.gaveUp = true
                    BridgeLog.e("AFU background preparation failed", error)
                    if (onState != null) OppoReflect.callFirst(onState, listOf("invoke"), false)
                }
            }
        }
        for (name in listOf("forceStopKeepAlive", "detachKeepAliveWithoutDisconnect"))
            OppoAfuLifecycleHook.hook(manager, name) { OppoReflect.postMain { OppoAfuRuntime.stopBackground() } }
        for (name in listOf("isKeepAliveConnected", "isSdkGattConnected", "isKeepAliveGiveUp")) {
            OppoAfuLifecycleHook.hook(manager, name) { param ->
                val mac = param.args.firstOrNull() as? String ?: return@hook
                val context = OppoReflect.context() ?: return@hook
                val account = try { OppoAccount.key(cl) } catch (_: Exception) { return@hook }
                if (BridgeBindingStore.contains(context, account, mac)) {
                    param.result = if (name == "isKeepAliveGiveUp")
                        OppoAfuRuntime.background?.let { it.mac.equals(mac, true) && it.gaveUp } == true
                    else BridgeBindingStore.model(context, account, mac)?.let { DeviceRegistry.byModel(it)?.isConnected(mac) } == true
                }
            }
        }
        HookInstallState.keepAlive = true
    }
}
