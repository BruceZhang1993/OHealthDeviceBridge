package io.github.brucezhang1993.ohealthdevicebridge

import de.robv.android.xposed.XposedBridge

object BridgeLog {
    fun i(message: String) = XposedBridge.log("${BridgeConstants.LOG_TAG} $message")

    fun e(message: String, error: Throwable? = null) {
        XposedBridge.log("${BridgeConstants.LOG_TAG} ERROR $message")
        if (error != null) XposedBridge.log(error)
    }

    fun maskedMac(mac: String): String {
        val p = mac.split(':')
        return if (p.size == 6) "${p[0]}:${p[1]}:**:**:${p[4]}:${p[5]}" else "**:**:**:**:**:**"
    }
}
