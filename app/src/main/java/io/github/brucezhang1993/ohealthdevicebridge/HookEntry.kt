package io.github.brucezhang1993.ohealthdevicebridge

import com.highcapable.yukihookapi.YukiHookAPI
import com.highcapable.yukihookapi.annotation.xposed.InjectYukiHookWithXposed
import com.highcapable.yukihookapi.hook.xposed.proxy.IYukiHookXposedInit
import io.github.brucezhang1993.ohealthdevicebridge.hook.oppo.OppoHookInstaller

@InjectYukiHookWithXposed
object HookEntry:IYukiHookXposedInit{
    override fun onInit()=YukiHookAPI.configs{isDebug=BuildConfig.DEBUG}
    override fun onHook()=YukiHookAPI.encase{loadApp(name=BridgeConstants.TARGET_PACKAGE){BridgeLog.i("loading for $packageName process=$processName target=${BridgeConstants.TARGET_VERSION}");OppoHookInstaller.install(appClassLoader)}}
}
