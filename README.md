# OHealthDeviceBridge

一个基于 Kotlin + Xposed/LSPosed 的 OPPO 健康第三方设备桥接模块。首个设备为 **AFU-BH-TZ-B1** 体脂秤。

> 当前目标宿主固定为 `com.heytap.health` / OPPO 健康 `6.9.39_9d6948f_260928`。厂商私有类和 synthetic 方法可能随版本变化，升级宿主后需要重新验证 Hook 点。

## 当前能力

- 在 OPPO 健康“添加设备”的体脂秤分类中注入 `AFU-BH-TZ-B1`
- 将 AFU 逻辑路由到 OPPO 原有 `BOOHEE / Connect Scale` 业务链
- 使用模块自己的 BLE scanner 扫描 `0xFC50 + AFU` 设备
- 生成 synthetic `BHDeviceModel` / `BindableScaleDevice`，复用 OPPO 原绑定 UI
- Hook `BHDeviceManager.bind()`，用 AFU GATT 私有协议替换 Boohee BLE transport
- Hook `BHDeviceManager.startMeasureReceive()`：`0x51` -> 实时重量；fresh `0x54` -> weight + impedance -> `BHScaleModel` -> OPPO ICOMON
- 本地保存已绑定 AFU MAC，避免误拦截真实 Boohee 设备
- 对 `BooheeDeviceLoader.convertBoohee()` 提供 best-effort 本地 overlay fallback
- JVM 单元测试覆盖文档中的 live/stored/impedance/time-sync/profile/ACK 样例

## 架构

```text
OPPO Health native UI
        |
        v
hook/oppo  ---------------------------+
  ProductCatalog / Routing / Binder   |
        |                              |
        v                              |
device/DeviceDriver                   |  future device drivers
        |                              |
        +--> device/afu/AfuB1Driver --+
                 |
                 +-- AfuBleScanner
                 +-- AfuGattSession
                 +-- AfuGattQueue
                 +-- AfuProtocol
```

设备协议层不依赖 OPPO 私有类；后续新增其他秤只需实现 `DeviceDriver`，再增加对应的 OPPO bridge/routing 策略。

## Xposed 框架

使用 YukiHookAPI `1.3.2` 作为 Kotlin Xposed 模块基础设施。LSPosed Scope 只需要 `com.heytap.health`，不需要勾选 Android Framework、Bluetooth 或 SystemUI。

## AFU BLE 主流程

```text
connect -> discover -> subscribe all NOTIFY/INDICATE
  -> 0x16 time sync -> 0x04/0x62 x2 -> 0x8E history dump
first 0x51 -> 0x3F profile -> onProcessWeight()
locked 0x51 -> ACK -> wait 2s silence
  -> re-sync -> 0x4B -> 0x5B -> 0x14
  -> fresh 0x54 -> ACK(sequence)
  -> weight + resistance -> OPPO BHScaleModel/ICOMON -> onLockWeight()
```

GATT descriptor/characteristic writes are serialized；不使用全局 Bluetooth Hook。

## 构建

CI 使用 Gradle 9.2.1 / AGP 8.13.1 / Kotlin 2.2.21 / JDK 21：

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

CI 额外检查 YukiHookAPI 的 Xposed entry 是否真正打入 APK。

## 已知需要真机确认

1. OPPO 云绑定是否接受真实 model `AFU-BH-TZ-B1`。
2. `BHDeviceManager.BindListener` 失败回调实际方法名。
3. 实际 AFU 固件的 Notify/Indicate GATT 树。
4. 两条 `0x62` sync request 中的 magic bytes 在其他固件上是否变化。
5. OPPO 内置 ICOMON 结果与 AFU 官方 App 是否一致。
6. OPPO 更新后 synthetic 产品列表方法名是否变化。

## License

MIT。AFU 协议实现根据公开 wire-format / 抓包事实重新实现，没有直接复制 openScale 的 GPL-3.0 Kotlin 源码。
