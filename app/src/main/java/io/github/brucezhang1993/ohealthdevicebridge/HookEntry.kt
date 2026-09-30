package io.github.brucezhang1993.ohealthdevicebridge

import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.OppoHookInstaller
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class HookEntry : XposedModule() {
    private var installed = false

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName != BridgeConstants.TARGET_PACKAGE || installed) return
        installed = true

        BridgeLog.i(
            "loading modern Xposed entry for ${param.packageName} target=${BridgeConstants.TARGET_VERSION}"
        )
        runCatching {
            OppoHookInstaller.install(param.classLoader)
        }.onFailure {
            installed = false
            BridgeLog.e("failed to install OPPO Health hooks", it)
        }
    }
}
