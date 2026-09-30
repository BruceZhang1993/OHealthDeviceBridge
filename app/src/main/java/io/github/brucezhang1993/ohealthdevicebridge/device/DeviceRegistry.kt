package io.github.brucezhang1993.ohealthdevicebridge.device

import io.github.brucezhang1993.ohealthdevicebridge.BridgeConstants
import io.github.brucezhang1993.ohealthdevicebridge.device.afu.AfuB1Driver

object DeviceRegistry {
    private val drivers: Map<String, DeviceDriver> = listOf(
        AfuB1Driver,
    ).associateBy { it.model }

    fun byModel(model: String?): DeviceDriver? = model?.let(drivers::get)

    fun afu(): DeviceDriver = requireNotNull(drivers[BridgeConstants.AFU_MODEL])

    fun supportedModels(): Set<String> = drivers.keys
}
