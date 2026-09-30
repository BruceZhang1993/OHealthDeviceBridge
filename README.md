# OHealthDeviceBridge

一个基于 Kotlin + Xposed/LSPosed 的 OPPO 健康第三方设备桥接模块。首个设备为 **薄荷健康B1智能体脂秤（蚂蚁阿福联名款）**，设备代号 `AFU-BH-TZ-B1`。

> 当前目标宿主固定为 `com.heytap.health` / OPPO 健康 `6.9.37_a7d98c1_260920`。厂商私有类和 synthetic 方法可能随版本变化，升级宿主后需要重新验证 Hook 点。

## 当前能力

- 在 OPPO 健康“添加设备”的体脂秤分类中注入薄荷健康B1智能体脂秤（蚂蚁阿福联名款），副标题使用 `AFU-BH-TZ-B1`
- 将 AFU 逻辑路由到 OPPO 原有 `BOOHEE / Connect Scale` 业务链
- 使用模块自己的 BLE scanner 扫描 `0xFC50 + AFU` 设备
- 生成 synthetic `BHDeviceModel` / `BindableScaleDevice`，复用 OPPO 原绑定 UI
- Hook `BHDeviceManager.bind()`，用 AFU GATT 私有协议替换 Boohee BLE transport
- Hook `BHDeviceManager.startMeasureReceive()`：`0x51` -> 实时重量；fresh `0x54` -> weight + impedance -> `BHScaleModel` -> OPPO ICOMON
- 按当前 OPPO 账户保存已绑定 AFU MAC，解绑/退出账户时取消会话，避免误拦截真实 Boohee 设备
- `BooheeDeviceLoader.convertBoohee()` 在空设备列表也可补齐本地 AFU，按 MAC 去重
- 旧历史走 OPPO 未认领导入，连接状态和后台历史由 AFU transport 提供
- GATT 操作串行执行，设 5 秒操作超时、64 项队列上限及 120 秒会话上限
- 测量状态、取消、去重、账户隔离及宿主反射适配均有 JVM 回归测试

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

模块入口使用 Modern Xposed / libxposed API `101`，通过 `META-INF/xposed/java_init.list` 声明入口，并使用：

```properties
minApiVersion=101
targetApiVersion=101
staticScope=true
```

静态作用域由 `META-INF/xposed/scope.list` 固定为：

```text
com.heytap.health
```

因此模块只作用于 OPPO 健康，不需要也不能额外扩展到 Android Framework、Bluetooth、SystemUI 或其他 App。当前 OPPO Hook 实现仍复用 legacy `XposedBridge/XposedHelpers`，所以 `targetApiVersion` 固定在 101；后续若整体迁移到 libxposed interceptor API，再升级到 API 102。

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

AFU 在 `BindableScaleDevice`/设备列表中仍是连接型秤；交给 ICOMON 的 synthetic `BHDeviceModel.isConnectScale=false`，用于避开 SDK 在计算后向全局 Boohee GATT 写 B3 的副作用。历史读取在排空后结束，不维持无期限后台连接。

## 构建

CI 使用 Gradle 9.2.1 / AGP 8.13.1 / Kotlin 2.2.21 / JDK 21：

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

CI 额外检查 Modern Xposed 的 `java_init.list`、`module.prop`、`scope.list` 是否正确打入 APK，并确保不会重新打包 legacy `assets/xposed_init`。

## 已知需要真机确认

1. OPPO 云绑定是否接受真实 model `AFU-BH-TZ-B1`。
2. LSPosed 实际加载、产品显示、取消/解绑及原生保存；`BindListener.onFail(String)` 已由 6.9.37 APK 核实。
3. 实际 AFU 固件的 Notify/Indicate GATT 树。
4. 两条 `0x62` sync request 中的 magic bytes 在其他固件上是否变化。
5. OPPO 内置 ICOMON 结果与 AFU 官方 App 是否一致。
6. OPPO 更新后 synthetic 产品列表方法名是否变化。

## 验收与升级

验收清单见 [docs/AFU_ACCEPTANCE.md](docs/AFU_ACCEPTANCE.md)。本地回归测试使用检查过的 6.9.37 类型签名作为测试替身，不执行专有 ICOMON 算法，也不能替代真实 BLE/数据库保存验收。

未知账户归属的旧 `afu_bound_macs` 不自动迁移。云端已保存的当前账户 AFU 会恢复本地记录；此前仅本地绑定的设备需要重新绑定一次。

用原始宿主 APK 检查 DEX 声明：

```bash
python scripts/verify_host_contract.py /path/to/OPPO-health.apk
```

本机 Gradle 9.7.1 与 AGP 8.13.1 不兼容；使用 CI 的 Gradle 9.2.1 或兼容的 9.5.0。构建时仍需遵循运行环境要求的 systemd 内存限制。

## License

MIT。AFU 协议实现根据公开 wire-format / 抓包事实重新实现，没有直接复制 openScale 的 GPL-3.0 Kotlin 源码。
