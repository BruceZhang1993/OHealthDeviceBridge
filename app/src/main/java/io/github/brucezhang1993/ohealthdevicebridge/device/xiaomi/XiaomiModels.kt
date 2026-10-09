package io.github.brucezhang1993.ohealthdevicebridge.device.xiaomi

object XiaomiModels {
    const val V1 = "XIAOMI-MI-SCALE-V1"
    const val V2 = "XIAOMI-MI-SCALE-V2"
    const val S400 = "XIAOMI-S400"
    const val S800 = "XIAOMI-S800"
    val names = linkedMapOf(V1 to "小米体重秤（一代）", V2 to "小米体脂秤（二代）", S400 to "小米体脂秤 S400（从米家读取）", S800 to "小米体脂秤 S800（从米家读取）")
    fun needsKey(model: String) = model == S400 || model == S800
    fun cloudModel(model: String): String? = when (model) {
        "yunmai.scales.ms103", "yunmai.scales.ms104" -> S400
        "xiaomi.scales.ms116" -> S800
        else -> null
    }
}
