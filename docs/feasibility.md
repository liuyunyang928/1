# 免 root 可行性调研：能否在系统层按应用屏蔽运动传感器？

> 本文是 NoShake（摇一摇克星）立项前的技术调研摘要，用于解释项目为什么选择「root + LSPosed」路线，以及免 root 方案目前为什么走不通。
>
> 调研时间：2026-11-08，方式为只读 AOSP 源码 / 官方文档 / 公开资料，未在真机上逐项验证。
> 标注约定：**【已证实】**＝有源码、官方文档或可复现的 HTTP 响应为证；**【推测】**＝基于机理或社区经验的判断，需真机实测。凡标注「待验证」的内容均不作为事实使用。

## 0. 结论速览

| 问题 | 结论 |
| --- | --- |
| AOSP 有「按应用禁用运动传感器」的公开 API / AppOps / shell 命令吗？ | **没有**。`SensorPrivacyManager.Sensors` 只有 `MICROPHONE` / `CAMERA`；AOSP 传感器相关 AppOps 只有 `body_sensors`、`activity_recognition`，没有任何「运动传感器」op【已证实】 |
| `cmd sensor_privacy` 能按应用关运动传感器吗？ | **不能**。其 shell 命令只支持 `enable/disable <USER_ID> <microphone\|camera>`，粒度是「用户 + 传感器」，与单个应用无关【已证实（AOSP main 源码）】 |
| Android 12+ 的隐私开关管运动传感器吗？ | 不管。摄像头/麦克风开关是整机级；隐私面板只是访问记录展示【已证实】 |
| 唯一的免 root 粗粒度开关是什么？ | 开发者选项里的「Sensors off」快捷开关，整机停用全部传感器，副作用大、默认隐藏【已证实】 |
| 国产 ROM 的「获取设备动作与方向」权限存在吗？ | 存在（小米/澎湃、vivo OriginOS、OPPO ColorOS 等均有），但属厂商私有实现；底层 op 名没有公开证据，需逐 ROM 适配【存在=已证实；op 名=待验证】 |
| 免 root 的通用方案存在吗？ | **不存在**。免 root 的天花板是「国产 ROM 适配」，非国产 ROM 只能整机全关或走 root【已证实（负面结论，有源码依据）】 |
| 本项目为什么选 root + LSPosed？ | 只有注入目标进程、在 Java 层拦截 `SensorManager` 才是通用、按应用、可维护的方案（见第 5 节） |

## 1. AOSP 为什么没有「按应用禁用运动传感器」

### 1.1 没有公开 API：`SensorPrivacyManager` 只管摄像头与麦克风

`android.hardware.SensorPrivacyManager` 是 Android 12 引入的传感器隐私管理类，其内部传感器枚举只有两个有效常量：`MICROPHONE` 与 `CAMERA`。该类和 `MANAGE_SENSOR_PRIVACY` 权限都属于系统内部接口（`@SystemApi` / `@hide`，权限保护级别为 `internal|role|installer`），普通第三方应用既无法调用，也无法申请该权限。

引用：

- [`SensorPrivacyManager` API 参考](https://developer.android.com/reference/android/hardware/SensorPrivacyManager)
- [AOSP `SensorPrivacyManager.java`（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorPrivacyManager.java)
- [AOSP `AndroidManifest.xml` 中 `MANAGE_SENSOR_PRIVACY` 的定义](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)

### 1.2 `cmd sensor_privacy` 只支持麦克风/摄像头，且按用户不按应用

AOSP `SensorPrivacyService` 的 shell 命令实现（`ShellCommand#onCommand`）只有以下能力：

```text
enable  <USER_ID> <microphone|camera>
disable <USER_ID> <microphone|camera>
```

传感器字符串只识别 `microphone` / `camera`，其余会返回 `Invalid sensor`；参数顺序是「用户 ID 在前」。它的粒度是「某个用户 + 具体传感器」，不能表达「只禁止 A 应用的加速度计」。

引用：[AOSP `SensorPrivacyService.java`（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/sensorprivacy/SensorPrivacyService.java)

> 版本差异提醒：Android 12 时代该命令语义更粗，Android 13 起才拆成麦克风/摄像头两个独立开关。若要在真机使用，请先执行 `adb shell cmd sensor_privacy help` 对齐参数（**待验证**，不同版本输出可能不同）。

### 1.3 AppOps 里没有运动传感器相关的 op

对 AOSP `android14-release` 分支的 `AppOpsManager.java` 检索，与传感器相关的 op 只有：

| 常量 | op 字符串 | 管什么 | 能否用于「摇一摇」 |
| --- | --- | --- | --- |
| `OP_BODY_SENSORS` | `android:body_sensors` | 体感传感器（心率等），对应 `BODY_SENSORS` 运行时权限 | ❌ 与动作传感器无关 |
| `OPSTR_ACTIVITY_RECOGNITION` | `android:activity_recognition` | 活动识别（计步、显著运动等），对应 `ACTIVITY_RECOGNITION` 权限 | ⚠️ 只覆盖 `TYPE_STEP_*` / `TYPE_SIGNIFICANT_MOTION`，**不覆盖加速度计/陀螺仪** |
| `android:activity_recognition_source` | 活动识别数据源（内部使用） | ❌ |

**没有任何** `android:read_sensors` / `android:sensors` / `android:motion` 之类的 op。

引用：[AOSP `AppOpsManager.java`（android14-release）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/AppOpsManager.java)

这解释了一个常见疑问：**「摇一摇不需要权限」是真的**。加速度计、陀螺仪在常规采样率下属于无需权限的传感器（Android 12+ 仅对 `>200 Hz` 高频采样要求 `HIGH_SAMPLING_RATE_SENSORS` 权限），所以系统设置里根本没有对应的权限开关可关。

### 1.4 唯一存在的免 root 粗粒度开关：Sensors off

开发者选项中的「快捷设置开发者图块 → 传感器已关闭（Sensors Off）」可以整机停用所有传感器。AOSP 文档明确说明：它会阻止应用访问摄像头、麦克风以及**所有由 `SensorManager` 管理的传感器**；开关开启时应用仍可注册监听，但收不到数据。代价是摄像头、麦克风、自动亮度、自动旋转等一起失效，日常不可用，而且不区分应用。

引用：[AOSP Sensors off](https://source.android.com/docs/core/interaction/sensors/sensors-off)

## 2. 国产 ROM 的「获取设备动作与方向」权限

### 2.1 公开可见的事实

| ROM | 入口/名称 | 能力 | 可信度 |
| --- | --- | --- | --- |
| 小米 MIUI / HyperOS | 设置 → 隐私保护 → 特殊权限 → **获取设备动作与方向**（按应用） | 每应用开/关 | 媒体报道与厂商宣传可佐证【存在已证实】 |
| vivo OriginOS 5+ | **「仅开屏时禁止」** 三态选项（允许 / 仅开屏时禁止 / 禁止） | 按应用 + 按场景 | [IT之家报道](https://www.ithome.com/0/820/796.htm)【已证实】 |
| OPPO ColorOS / 一加 | 「设备动作与方向」（名称随版本略有差异） | 按应用 | LSPosed 模块 motionshield 自述「仅支持 ColorOS」【旁证】 |
| 荣耀 MagicOS / 华为 HarmonyOS | 设置内提供类似的方向/动作管控 | 按应用 | 仅社区提及，具体命名与行为**待验证** |

**这类权限是 ROM 私有实现**，不在 AOSP、也不在 CDD 要求中，因此跨 ROM 的名称、入口、语义、存储方式都不同。vivo 的「仅开屏时禁止」尤其说明它不是简单的 AppOps 三态开关，而是带场景判定的自定义状态机。

### 2.2 底层 op 名没有公开证据（待验证）

公开资料中没有找到可信的「国产 ROM 动作与方向权限」底层 op 名。可行但需真机验证的探测思路（**以下命令为调研建议，未在目标 ROM 上验证，请自行测试并注意风险**）：

```bash
# 1) 查看某个应用的全部 AppOps（重点找带厂商前缀的非 AOSP op）
adb shell cmd appops get <包名>

# 2) 搜索系统设置里的候选开关
adb shell settings list secure | grep -iE "sensor|motion|orient"
adb shell settings list global | grep -iE "sensor|motion|orient"

# 3) 对照实验：在设置里手动切换该权限，前后各导出一次 appops 并 diff
adb shell cmd appops get <包名> > /sdcard/before.txt
# （手动切换权限）
adb shell cmd appops get <包名> > /sdcard/after.txt
```

如果确认了 op 名，理论上可以用 `appops set <包名> <OP> ignore`（经 shell/Shizuku）写入，但**该写入是否被 ROM 真正采纳仍需逐机型验证**（有的 ROM 只允许在设置 UI 中修改）。

【推测】部分 ROM 的实现可能不是 AppOps，而是在框架层对 `SensorManager` / `SensorService` 做 uid 判定并维护自定义权限数据库——理由是「仅开屏时禁止」这类场景状态无法用 AppOps 的 allow/ignore/deny/default 四态表达。**该推测必须靠真机反查证实，不能当作事实。**

### 2.3 一个已验证的旁证：motionshield 模块

`me.benxp.motionshield` 是一个 LSPosed 模块，功能是「安装新应用后自动关闭设备动作与方向权限」。它的 README 自述「**仅支持 ColorOS 系统**」「目前仅在一加手机上测试过」「后续有精力了再对澎湃或其他系统做支持」。

引用：[me.benxp.motionshield](https://github.com/Xposed-Modules-Repo/me.benxp.motionshield)

这条信息的价值在于：它是「不 hook、纯改 ROM 权限」路线的现实样本，同时说明**跨 ROM 复用性极差**——一个完整模块也只敢宣称支持一个 ROM。

## 3. Shizuku 能做什么，不能做什么

Shizuku 的定位是「让应用直接使用系统 API」：它通过 root 或 adb 启动一个高权限进程，应用借助 Shizuku 的 Binder 包装，以该进程的身份（通常为 shell UID 2000）调用系统服务。引用：[Shizuku 官网](https://shizuku.rikka.app/)、[Shizuku 仓库](https://github.com/RikkaApps/Shizuku)。

| 能力 | 是否可行 | 说明 |
| --- | --- | --- |
| 以 shell 身份执行 `appops set` / `settings put` | ✅ 可行 | shell 持有 `MANAGE_APP_OPS_MODES`、`WRITE_SECURE_SETTINGS` 等权限 |
| 执行 `cmd sensor_privacy enable/disable` | ✅ 大概率可行【推测】 | shell 在 AOSP 中申请了 `MANAGE_SENSOR_PRIVACY`；但若改用 binder 直调且服务端校验 `callingPackage`，可能失败（待验证） |
| 关闭运动传感器 | ❌ 仅能整机且仅摄像头/麦克风 | `cmd sensor_privacy` 粒度是「用户 + 麦克风/摄像头」，与运动传感器无关 |
| 写入国产 ROM 的动作与方向权限 | ⚠️ 条件可行 | 前提是先知道该 ROM 的 op 名；不同 ROM 不通用，需逐个适配 |
| 凭空创造 AOSP 中不存在的「按应用运动传感器开关」 | ❌ | 没有对应 API 可调用 |

**关键点：Shizuku 只是「换一个更高权限的身份去调用已有 API」，不能创造系统能力。** AOSP 没有「按应用禁用运动传感器」的接口，Shizuku 就无从下手；国产 ROM 有私有接口，但需要逐 ROM 探测 op 名并适配。

## 4. 明确结论：免 root 通用方案不存在

论证链（每一条都有源码或官方文档依据）：

1. AOSP 没有「按应用 + 运动传感器」的 AppOps / 权限 / API（第 1 节）；
2. 官方传感器隐私服务只覆盖麦克风/摄像头，且按用户不按应用（第 1.2 节）；
3. adb/Shizuku 能执行的操作最终都要落到某个 op 或 setting 上，而 AOSP 里没有这个 op；
4. 国产 ROM 有类似能力，但私有、跨机型/版本碎片化，且 op 名不公开（第 2 节）；
5. 因此免 root 方案的天花板是：
   - **国产 ROM：适配式**（探测 op 名 → 经 Shizuku/权限自动化写入），覆盖率取决于适配工作量；
   - **非国产 ROM：只能整机全关**（Sensors off），或走 root。

> 产品结论：**免 root 不是「通用降级方案」，而是「国产 ROM 适配功能」。** v1 主力必须是 root + LSPosed；免 root 只作为后期可选的 ROM 适配方向，不承诺支持范围与时间。

## 5. 为什么选择 root + LSPosed

只有把代码注入目标应用进程，才能做到「只拦某个应用」：

1. **粒度正确**：LSPosed 按作用域注入，用户在 LSPosed 中勾选应用、再在管理界面中开关，天然就是按应用生效。
2. **拦截点稳定**：`SensorManager` 的公开方法签名从 API 1 至今几乎未变（4 个 `SensorEventListener` 重载 + 2 个已废弃 `SensorListener` 位掩码重载 + `requestTriggerSensor`），hook 它不依赖任何私有字段/隐藏 API。
3. **语义最干净**：对动作传感器直接让注册返回 `false`（文档定义的「传感器不可用」语义），不注册即不采样、不耗电，广告 SDK 收不到任何数据；相比「伪造数据」「在事件回调里丢包」，实现更简单、跨版本更稳。
4. **代价明确**：需要 root + LSPosed；只覆盖 Java 层 `SensorManager`；NDK 通道、WebView 的 `devicemotion`/`deviceorientation` 事件不在覆盖范围内（见第 6.3 节）。

### 5.1 两种拦截策略对比（设计依据）

| 策略 | 做法 | 优点 | 缺点 |
| --- | --- | --- | --- |
| **A. 拒绝注册**（本项目采用） | before-hook `registerListener` / `requestTriggerSensor`，命中动作传感器则 `setResult(false)` | 不碰私有 API；不采样不耗电；语义就是「传感器不可用」 | 少数不检查返回值的 SDK 会「一直等不到回调」；应用可感知传感器不可用 |
| B. 事件级丢包 | hook `SystemSensorManager$SensorEventQueue.dispatchSensorEvent` 丢弃事件 | 注册仍然「成功」，对必须注册成功的 SDK 更友好 | 需反射多个私有字段，系统升级易失效；仍在持续采样耗电 |

本项目选择 A：hook 点少、完全基于公开 API，维护成本最低；B 只适合作为未来的「兼容模式」选项。`getDefaultSensor()` 返回 `null` 也不作为默认策略——AOSP 侧安全（原生实现会判空返回），但应用侧常在拿到 `Sensor` 后直接调 `sensor.getStringType()` 等方法，可能因此崩溃，所以只在「严格模式」下启用。

### 5.2 竞品参考与合规

- [AppGuard](https://github.com/imkamaran/AppGuard)：LSPosed 模块，hook 点与本项目高度相似（其 `SensorHooks.kt` 注释即设计说明），也明确承认未覆盖 NDK、WebView、`getDynamicSensorList` 与已废弃的 `SensorListener` 位掩码 API。
  **注意：AppGuard 使用 GPLv3 协议，本项目仅参考其公开思路（hook 方法清单、批量 hook + 命中数量断言、审计模式等），未复制其代码**；本项目为 MIT 协议。
- [motionshield](https://github.com/Xposed-Modules-Repo/me.benxp.motionshield)：走的是「改 ROM 权限」路线，仅支持 ColorOS，可作为免 root 路线的参考。
- [LSPosed Wiki: New XSharedPreferences](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)：模块 App 与被 hook 进程共享配置的官方说明。

## 6. 本项目 v1 技术选型（与代码一致）

### 6.1 拦截范围

只拦截与「摇一摇/动作方向」直接相关的**最小集**（9 种）：

| 值 | 常量 |
| --- | --- |
| 1 | `TYPE_ACCELEROMETER` |
| 4 | `TYPE_GYROSCOPE` |
| 9 | `TYPE_GRAVITY` |
| 10 | `TYPE_LINEAR_ACCELERATION` |
| 11 | `TYPE_ROTATION_VECTOR` |
| 15 | `TYPE_GAME_ROTATION_VECTOR` |
| 16 | `TYPE_GYROSCOPE_UNCALIBRATED` |
| 20 | `TYPE_GEOMAGNETIC_ROTATION_VECTOR` |
| 35 | `TYPE_ACCELEROMETER_UNCALIBRATED` |

**不拦截**：光线（5）、距离（8）、磁力（2/14）、显著运动（17）、计步（18/19）、心率（21/31）、气压/温度/湿度、铰链角（36）、佩戴检测（34）等。这样自动亮度、通话息屏、系统级计步/活动识别、折叠屏形态等功能不受影响。

### 6.2 Hook 点

| 方法 | 时机 | 动作 |
| --- | --- | --- |
| `registerListener(SensorEventListener, Sensor, ...)` 的 4 个重载 | before | 传感器类型命中拦截集合 → `setResult(false)` |
| `registerListener(SensorListener, int[, int])` 的 2 个已废弃重载 | before | 位掩码包含方向/加速度位 → `setResult(false)`（容易遗漏的绕过面） |
| `requestTriggerSensor(TriggerEventListener, Sensor)` | before | 同样按 `Sensor` 类型判定（当前拦截集合不含 `TYPE_SIGNIFICANT_MOTION`，该入口实际放行） |
| `getDefaultSensor(...)` | before（严格模式） | 命中拦截集合 → 返回 `null` |
| `getSensorList(...)` / `getDynamicSensorList(...)` | after（严格模式） | 从返回列表过滤拦截类型 |

实现上使用 `XposedBridge.hookAllMethods(...)` 按方法名批量 hook，覆盖所有重载；安装完成后统计 hook 命中数量，若为 0 则写 `NoShake: WARNING ...` 日志（便于发现 ROM 改名/魔改 `SensorManager` 的情况）。

### 6.3 已知覆盖边界

| 通道 | 是否覆盖 | 原因 |
| --- | --- | --- |
| Java `SensorManager`（常规应用、主流广告 SDK） | ✅ | 本模块的 hook 层面 |
| NDK `ASensorManager` | ❌ | 原生通道不经过 Java `SensorManager`，Java hook 无效 |
| WebView 内 `devicemotion` / `deviceorientation` | ❌ | 由 Chromium/WebView 自行读取传感器，与本模块的 hook 无关 |
| 系统应用 / 本模块自身进程 | ❌（主动跳过） | UID 判断：`android`、自身包名、system UID 一律跳过 |

### 6.4 配置通道

- **写入**：管理界面通过 `ConfigStore` 以 `MODE_WORLD_READABLE` 打开 `SharedPreferences`（`noshake_config`）。启用模块时 LSPosed 会拦截该模式、把文件变为被 hook 进程可读；未启用 LSPosed 时该调用抛 `SecurityException`，代码自动回退 `MODE_PRIVATE`。
- **主读取通道**：被 hook 进程通过 `ContentProvider`（authority `io.github.liuyunyang928.noshake.config`，URI `content://.../config`）查询单行配置；`AndroidManifest.xml` 中的 `xposedsharedprefs=true` 同时启用 LSPosed 的 XSharedPreferences 桥。
- **兜底通道**：Provider 不可用时，用反射构造 `XSharedPreferences` 直接读 prefs 文件。
- **兜底失败**：两条通道都失败时 fail-closed（按「已启用」处理）。
- **缓存**：配置在目标进程内缓存 30 秒；hook 只在进程启动时装载，因此**首次把某应用纳入拦截后需要重启/强制停止该应用**，配置变更则在最多 30 秒内对新注册生效。

## 7. 待真机验证清单

| # | 待验证项 | 影响 |
| --- | --- | --- |
| 1 | 小米 / OPPO / vivo / 荣耀「动作与方向」权限的底层 op 名 | 决定免 root「国产 ROM 适配」是否可做 |
| 2 | `appops set` 在该 ROM 上是否真正生效（有些 ROM 会忽略 appops 写入） | 同上 |
| 3 | `cmd sensor_privacy` 在 Android 12/13/14 上的确切参数与行为 | 仅影响文档准确性，不影响主方案 |
| 4 | Shizuku `newProcess` 执行 `cmd sensor_privacy` 是否被 `callingPackage` 校验拦截 | 免 root 能力边界 |
| 5 | 目标应用是否存在广告子进程、广告 SDK 实际读哪些传感器 | 校准默认拦截集合 |
| 6 | `registerListener` 返回 `false` 后各应用是否出现卡死/崩溃 | 是否需要未来的兼容模式选项 |
| 7 | 是否存在走 NDK 的真实样本 | 决定 v2 是否做 native hook |

## 8. 参考链接

**AOSP 源码**

- [SensorManager.java（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorManager.java)
- [SensorPrivacyManager.java（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/hardware/SensorPrivacyManager.java)
- [SensorPrivacyService.java（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/sensorprivacy/SensorPrivacyService.java)
- [AppOpsManager.java（android14-release）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android14-release/core/java/android/app/AppOpsManager.java)
- [AndroidManifest.xml（main，`MANAGE_SENSOR_PRIVACY`）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)
- [packages/Shell/AndroidManifest.xml（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/packages/Shell/AndroidManifest.xml)

**AOSP / Android Developers 文档**

- [Sensors off](https://source.android.com/docs/core/interaction/sensors/sensors-off)
- [SensorManager API 参考](https://developer.android.com/reference/android/hardware/SensorManager)
- [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [Android 12 行为变更（摄像头/麦克风开关）](https://developer.android.com/about/versions/12/behavior-changes-all)
- [SensorPrivacyManager API 参考](https://developer.android.com/reference/android/hardware/SensorPrivacyManager)

**LSPosed / Xposed**

- [LSPosed 仓库](https://github.com/LSPosed/LSPosed)
- [LSPosed Wiki: New XSharedPreferences](https://github.com/LSPosed/LSPosed/wiki/New-XSharedPreferences)
- [LSPosed Wiki: Develop Xposed Modules Using Modern Xposed API](https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API)
- [Xposed API 文档](https://api.xposed.info/)

**竞品 / 社区资料**

- [AppGuard（GPLv3，仅思路参考）](https://github.com/imkamaran/AppGuard)｜[LIMITATIONS.md](https://github.com/imkamaran/AppGuard/blob/main/docs/LIMITATIONS.md)
- [motionshield（ColorOS 专用）](https://github.com/Xposed-Modules-Repo/me.benxp.motionshield)
- [某地图 App 摇一摇广告触发流程分析（52pojie，含反编译样本）](https://www.52pojie.cn/forum.php?mod=viewthread&tid=2130000)
- [vivo OriginOS 5 新增「仅开屏禁止」相关报道（IT之家）](https://www.ithome.com/0/820/796.htm)
- [Shizuku 官网](https://shizuku.rikka.app/)｜[Shizuku 仓库](https://github.com/RikkaApps/Shizuku)
- [GKD 官网](https://gkd.li/)

**监管背景（供理解需求来源）**

- [工信部《关于进一步提升移动互联网应用服务能力的通知》（工信部信管〔2023〕26 号）](https://wap.miit.gov.cn/zwgk/zcwj/wjfb/tz/art/2023/art_261ee9a52be545c09dc4323a45e8631f.html)
- [光明网/人民日报：整治 App 广告乱跳转新标准出台](https://m.gmw.cn/toutiao/2023-02/01/content_1303269092.htm)

## 9. 事实核实状态

| 事实 | 状态 |
| --- | --- |
| `SensorPrivacyManager.Sensors` 仅有 `MICROPHONE` / `CAMERA` | ✅ AOSP main 源码核实 |
| `MANAGE_SENSOR_PRIVACY` 为系统内部权限，第三方不可申请 | ✅ AOSP main `AndroidManifest.xml` 核实（`internal\|role\|installer`） |
| `cmd sensor_privacy` 仅支持麦克风/摄像头、按用户粒度 | ✅ AOSP main `SensorPrivacyService` 源码核实 |
| AOSP 无运动传感器相关 AppOps | ✅ `android14-release` `AppOpsManager.java` 核实 |
| Android 12 摄像头/麦克风开关为整机级 | ✅ Android Developers 文档核实 |
| Android 10「Sensors off」为整机级、隐藏于开发者选项 | ✅ AOSP 文档核实 |
| 运动传感器常规采样率无需权限（>200Hz 才需 `HIGH_SAMPLING_RATE_SENSORS`） | ✅ Android 文档与 AOSP 源码核实 |
| 国产 ROM「获取设备动作与方向」权限存在 | ✅ 多来源佐证（媒体/厂商/模块 README） |
| 国产 ROM 该权限的底层 op 名 | ❓ 未找到公开证据，待真机验证 |
| Shizuku 可通过 shell 执行 appops/settings | ✅ 权限模型可推定 |
| Shizuku 执行 `cmd sensor_privacy` 是否受 `callingPackage` 限制 | ⚠️ 推测可行，待真机验证 |
| Android 12–16 均无按应用运动传感器开关 | ✅ 基于 AOSP main 源码与文档；逐版本穷举未做，未来版本以源码为准 |
