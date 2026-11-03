# Mimir

> 用户正常用手机的同时，Agent 在一块不投屏的隐藏虚拟屏上操作真实 App，完成自然语言任务；用户的前台画面、焦点与输入法都不受影响。

Mimir 是一个 Android 手机 Agent。用户说一句话，它在系统级虚拟屏上打开真实 App、观察界面、逐步操作；遇到支付、登录、验证码等必须本人完成的步骤，通过通知或应用内接管页请求介入。任务的最终结果真实落在用户自己的 App 与账号里，而不是停在一个"演示页面"上。

| 项 | 说明 |
|---|---|
| 形态 | 单 APK（Kotlin + Jetpack Compose），无需 Root / 系统签名 / 改 ROM |
| 隔离方式 | `DisplayManager.createVirtualDisplay()`，公开 API + shell 权限 |
| 提权路径 | Shizuku（shell uid 2000）+ 无障碍服务；不使用悬浮窗 |
| 观测 | 无障碍控件树定几何 + 截图定语义 + 端侧 OCR 兜底 |
| 模型 | 任意 OpenAI 兼容 `chat/completions`，设备侧可配置 |
| 版本 | 0.2.1（versionCode 12），minSdk 30 / targetSdk 36 / compileSdk 37 |
| 演示包 | `build/artifacts/mimir-0.2.1-demo-debug.apk`（含端侧 OCR 模型；`*.apk` 不入库，需本地构建） |

**真机已跑通的任务（小米 13 / HyperOS 4.0 / glm-5.3-flash）**

| 任务 | 终态 | 落点 |
|---|---|---|
| 设置：打开「关于手机 / 全部参数与信息」 | `DONE` | 双路独立复验；记忆可跨任务激活 |
| B 站：搜索中文关键词并播放首个视频 | `DONE_READBACK` | 点结果通知直接回到视频详情页，视频在播 |
| 美团外卖：选餐并加购 | `COMPLETED_UNVERIFIED`（结果页交付） | 点结果通知直达购物车（`GlobalCartActivity`） |
| 淘宝：搜索商品 | `DONE_READBACK` | 搜索结果页保留 |
| 微信：给文件传输助手发消息 | `DONE_READBACK` | 视觉通道（控件树被 App 封锁） |

诚实边界：微信只能走视觉通道；美团风控验证、支付与登录必须由本人完成；P5「输入法零干扰」仍未验证，因此 IME 回退路径被硬门隔离。完整清单见 §6。

---

## 1. 问题与判断

题目有三个互相牵制的约束：**真并发**（不是轮流用）、**真 Agent**（不是脚本回放）、**不打扰**（不抢焦点、不抢输入法）。三者同时成立只有一条路：

> **把 Agent 的执行环境从物理屏搬到系统级私有虚拟显示上。**

只要 Agent 与用户共用同一个 display，就必然争抢焦点与输入法。任何"快速切换""后台偷偷点""等用户空闲"的方案，都只是把冲突延后，并且违反"不是等用户放下手机再做"这条明文要求。因此本项目的**第一性决策是 display 层面的解耦**，其余设计都由它推导出来。

与现有开源实现的差别不在"谁用了虚拟屏"，而在解耦之后怎么让 Agent 真的稳：

| 维度 | 常见做法 | 本项目的做法 |
|---|---|---|
| 观测 | 仅截图，模型直出坐标 | UI 树定几何 + 截图定语义；树不可用时降级纯视觉 |
| 动作定位 | 模型输出 0–1000 坐标 | 模型只选元素编号 `#N`，本地映射 bounds 并加抖动 |
| 界面稳定 | 固定 `sleep(1s)` | 布局哈希 + 像素差轮询；**安静只代表可读下一帧，不代表动作成功** |
| 失败处理 | 解析失败即终止 / 写条错误文本后空转 | 动作结果三态化；写操作禁止盲目重试，未决写入只读核对 |
| 状态 | 内存状态，重启不可恢复 | 显式状态机 + Room checkpoint，恢复靠重新观测 |
| 人机边界 | 阻塞式 `input()` | 通知栏动作 + 应用内接管页；L2B 资金/身份步骤硬阻断 |
| 跨任务记忆 | 无 | 配方 + 锚点断言 + 失效机制，只认独立复验通过的成功 |

## 2. 技术选型

| 决策点 | 选择 | 否决了什么 | 理由 |
|---|---|---|---|
| 执行环境 | 系统级虚拟屏（PUBLIC / TRUSTED / 销毁即清内容） | 物理主屏、云手机、`overlay_display_devices` | 只有 display 层解耦能同时满足真并发与不打扰；云手机要求用户把账号放到云端 |
| 提权路径 | Shizuku（shell uid） | Root、系统签名、改 ROM | 普通用户用无线调试即可授权，可随时撤销 |
| 观测 | 无障碍树 + PixelCopy 截图；树空时端侧 OCR | 纯视觉坐标、云端 OCR、shell 内 `UiAutomation` | 树提供几何与文本，截图提供语义；`UiAutomation` 的接口不带 displayId，拿不到副屏树 |
| 动作 | 元素编号 + bounds + 抖动；纯视觉才用归一化坐标 | 模型直出坐标 | 坐标漂移无法根除，且无法校验"点的是不是同一个东西" |
| 文本输入 | 无障碍 `ACTION_SET_TEXT` 主路径；纯视觉按键注入回退 | `ime set` 全局切换输入法 | 改全局 IME 会抢走主屏键盘，属一票否决 |
| 状态与恢复 | 显式状态机 + Room（v4）保存计划、游标、会话计数 | 内存状态、重放固定序列 | 进程随时可能被杀；未决写入只能只读核对，不能重放 |
| 模型 | OpenAI 兼容接口，planner / executor / reviewer 可分别配置 | 端侧推理 | 9B 级端侧模型 40–130 秒/步，交互上不可用 |
| 形态取舍 | 真机为主，模拟器只作开发回路 | 云手机作为主线 | 结果必须作用于用户自己的账号；网络往返也让 1–2 分钟演示的观感变差 |

完整论证见 [`设计文档.md`](设计文档.md) §0 与附录 A，选型否决与源码审计见 [`技术评估报告.md`](技术评估报告.md)。

## 3. 系统架构

### 3.1 分层

| 层 | 职责 | 关键实现 |
|---|---|---|
| 设备层 `device/` | 虚拟屏、取帧、注入、拉起、IME 切换、任务搬运 | `shizuku/` 内的 AIDL UserService（shell uid）：`VirtualDisplayController` / `InputInjector` / `AppLauncher` / `TaskMover` |
| 观测层 `observation/` | 一致观察、元素编号、安静判定、元素 diff、遮挡分类、OCR | `ConsistentObservationAssembler` / `ElementNumberer` / `ScreenStability` / `ElementDiff` / `TransientOverlayClassifier` / `OcrProvider` |
| Agent 层 `agent/` | 规划、执行、分级、授权、后置条件、验证、记忆、凭据 | `Planner` / `Executor` / `Guard` / `GrantBroker` / `PostCondition` / `Verifier` / `MemoryRetriever` / `MemoryWriter` / `CredentialBroker` |
| 数据层 `data/` | Room 八张表与六个 DAO、模型客户端、记忆、加密凭据 | `db/`、`remote/`、`memory/`、`credential/` |
| 服务与 UI `service/` `ui/` | 无障碍、输入法、前台服务、通知；四标签页与接管页 | `MimirAccessibilityService` / `HandoffNotifier` / Compose 七个页面 |

分层只用包边界，不做多模块：窗口期短，多模块的 Gradle 成本大于收益。

### 3.2 一次任务的执行流

1. **TaskSpec**：用户确认任务边界（目标、允许的包、参数、完成边界、预算）。
2. **规划**：Planner 产出 `TaskPlan`（子任务 ≤6，每任务步骤 ≤8，每步前后置条件 ≤3 条）；开局把检索到的偏好注入 Planner。
3. **建屏 / 复用副屏**：拉起目标 App，校验它确实落在副屏；落在主屏则清场重拉。
4. **一致观察**：树 A → 截图 → 树 B，两次树哈希与 display 代次全一致才生成 `ObservationSnapshot`。
5. **执行当前步骤**：Executor 只处理当前 `PlanStep`；Guard 判 L0–L2B；需要确认时签发一次性授权或挂起交接。
6. **注入与判定**：定向注入后按后置条件做三态判定（`CONFIRMED` / `NO_EFFECT` / `OUTCOME_UNKNOWN`）；模型声明 `step_done` 时本地用新帧复核，命中才推进游标。
7. **验证**：计划走完进入 Verifier；先初验，再走独立复验路线或同页二次读取。
8. **交付**：查看 / 购物类任务保留结果页，终态通知可点击直达；只有 `DONE` / `DONE_READBACK` 才进入记忆写入闸门。

### 3.3 四份核心契约

| 契约 | 回答什么问题 | 关键约束 |
|---|---|---|
| `TaskSpec` | 这个任务允许做什么 | 创建时确认，子目标与模型输出都不能覆盖；授权绑定动作 + 参数 + 包名 + 完成边界，一次性 |
| `ObservationSnapshot` | 模型看到的到底是哪一帧 | 树 A → 截图 → 树 B，两次树哈希与 display 代次一致才给 observationId |
| `TargetFingerprint` | 点的是不是同一个东西 | 树模式用 resourceId / text / 路径；视觉模式用 OCR 文本 + 归一化框 + 局部指纹 |
| `ActionIntent` | 这个写入到底做没做 | 生命周期（PREPARED / INJECTING / SETTLED）与判定（CONFIRMED / NO_EFFECT / UNKNOWN）分离 |

`ActionIntent` 的分离不是洁癖：进程死在 `INJECTING` 会留下 `outcome = null` 的记录，这条记录就是"未决写入"，恢复时只能只读核对，禁止重放。

### 3.4 状态机与恢复

状态机覆盖 `CREATED → PLANNING → EXECUTING → VERIFYING → 终态`，旁路 `RECONCILING`、`NEED_HUMAN`，终态含 `DONE` / `DONE_READBACK` / `COMPLETED_UNVERIFIED` / `VERIFIED_BY_HUMAN` / `FAILED` / `ABORTED`。`EXECUTING → EXECUTING` 合法，表示单步循环。

- **进程被杀**：启动时扫描未完成任务，落 `NEED_HUMAN` 并写 `PROCESS_INTERRUPTED` 审计；不自动续跑。
- **未决写入**：先落 `RECONCILING`，再转人工；`outcome` 一次性判成 `OUTCOME_UNKNOWN`（判的是"没核对过"，不是"没执行"）。
- **续跑**：计划、游标、已消耗步数与模型调用都在任务行上，点「继续」不重新规划。
- **强制打断**：任何未结束的任务都可从任务页或历史页立即终止，先落 `ABORTED`，再后台清理副屏。

### 3.5 风险分级与人机边界

| 级别 | 含义 | 处置 |
|---|---|---|
| L0 | 只读或可完全回滚 | 直接执行 |
| L1 | 有外部副作用但可撤销 | 执行并留审计；图标类按钮按页面语境分级 |
| L2A | 有明确后果、需要当次确认 | 一次性授权（绑定动作 + 参数摘要 + 包名），原子消费 |
| L2B | 资金、身份、验证码、人脸/指纹 | 硬阻断，整页交人；Agent 不代做 |

交接方式：通知栏动作（继续一次 / 放弃 / 我来处理 / 主屏打开）+ 应用内接管页（实时镜像、接管触控、暂停、单步回放、继续 / 放弃）。接管页的画面只来自副屏，不拿主屏截图冒充。

### 3.6 记忆与凭据

- **记忆读**：按包名 + 任务类型取 `ACTIVE` 配方 → 页面签名 Jaccard ≥ 0.60 → 入口锚点逐条断言 → 置信度降序取 top-2；命中才写 `MEMORY_INJECTED`，prompt 里注明"仅供参考，必须与当前界面核对"。
- **记忆写**：只认 `DONE` / `DONE_READBACK`；首次写 `CANDIDATE`（不参与检索），同一稳定键在两个不同任务上第二次成功才转 `ACTIVE`；失败累计达阈值置 `STALE`。遮挡、凭据、单动作轨迹一律不生成配方，并写 `MEMORY_SKIPPED`。
- **凭据**：独立 `EncryptedSharedPreferences` + Android Keystore；模型只拿到凭据编号和账号别名，密码在用户当次授权后由本地 Broker 解密并 `ACTION_SET_TEXT` 填充；明文不进 prompt、审计与日志。

## 4. 已实现的能力

| 模块 | 内容 |
|---|---|
| 设备层 | Shizuku UserService 内完成建屏 / PixelCopy 取帧 / `injectInputEvent` 定向注入 / `launchDisplayId` 拉起 / IME 切换 / 跨屏搬运；五个设备端口适配器；能力探针 P1–P5 |
| 观测层 | 按 displayId 白名单取树（只取 `TYPE_APPLICATION`）、元素筛选与编号（上限 60）、16×16 灰度指纹、树 A→截图→树 B 一致观察、元素级 diff、瞬时遮挡分类、纯视觉降级、端侧 OCR |
| Agent 层 | 结构化计划与受限重规划、六段式 prompt、动作协议（含 `step_done` / `replan` / `credential`）、L0–L2B 分级、一次性授权、动作后置条件三态、步骤声明复核、进程中断恢复、独立结果复验、终态分层 |
| 记忆 | 配方检索与锚点断言、prompt 注入、白盒编辑（列表 / 新建 / 编辑 / 启停 / 删除）、JSON 导入导出、立即重验、失败降级 |
| 凭据 | Keystore 加密存储、本地 Broker、规则校验（包名精确匹配、必须密码框、拒绝遮罩/纯视觉）、当次授权、结果未知即交人 |
| 人机交接 | 通知栏四动作、交接通知副屏缩略图、接管页实时画面 / 触控转发 / 暂停 / 单步回放、挂起任务通用「继续」、结果通知点击直达结果页 |
| UI | 四标签（任务 / 历史 / 审计 / 设置）+ 全屏接管页；任务进度卡、强制打断、历史删除、首次四步引导、按 App 能力矩阵、模型连通性自检、记忆与凭据管理。不可用状态都写明原因，空态不伪装成有数据。 |

## 5. 代码结构

```
app/src/main/java/com/neptine/mimir/
├── core/contract/   核心契约与纯数据类型（TaskSpec / TaskPlan / 状态机 / 风险分级 / 记忆模型）
├── device/          设备能力端口；shizuku/ 是 AIDL 实现，shell/ 是 UserService 内的真实动作
├── observation/     树、截图、编号、安静判定、一致观察、diff、遮挡分类、OCR
├── agent/           编排器与 Planner / Executor / Guard / Verifier / Audit / Prompt / 记忆 / 凭据
├── data/
│   ├── db/          Room 八张表与六个 DAO
│   ├── remote/      OpenAI 兼容客户端与 DTO
│   ├── memory/      记忆检索与写入
│   ├── credential/  Keystore 加密凭据库
│   └── config/      模型与设备配置
├── service/         无障碍服务、输入法服务、前台服务、交接通知
├── di/              Hilt 模块（数据库、设备、网络、Agent）
└── ui/
    ├── theme/       设计令牌：配色、排版、圆角、间距
    ├── component/   可复用组件
    ├── viewmodel/   页面状态持有者
    ├── permission/  权限状态读取与跳转
    ├── onboarding/  首次四步引导
    ├── voice/       按住说话
    ├── screen/      七个页面
    └── MimirApp.kt / MimirTab.kt / MimirNavHost.kt
```

## 6. 能力边界

### 6.1 终态口径

| 终态 | 含义 | 计入记忆 |
|---|---|---|
| `DONE` | 两条不同路径各读一次都通过（已注册复验路线） | ✅ |
| `DONE_READBACK` | 同一页面隔 1.5 s 重读一次也通过（未注册路线的自动兜底） | ✅ |
| `VERIFIED_BY_HUMAN` | 机器没验成，用户点「我已核对」 | ❌ |
| `COMPLETED_UNVERIFIED` | 初验通过，但没有独立复验 / 重读也不通过 | ❌ |
| `MISMATCH → NEED_HUMAN` | 字段不符，或完成契约没有可核对文本 | ❌ |

`DONE_READBACK` 挡得住单帧幻读和模型自述，但挡不住"这页本身是缓存的成功页"，所以它是独立状态，不和 `DONE` 混为一谈。

### 6.2 App 兼容性（真机实测）

| App | 现状 | 阻塞点 |
|---|---|---|
| 设置 | ✅ 全链路 `DONE` | — |
| B 站 | ✅ `DONE_READBACK` | — |
| 微信 | ✅ 视觉通道 `DONE_READBACK` | 控件树被整体封锁（`children=0`）；纯视觉下写入受限 |
| 淘宝 | ⚠️ 搜索链路 `DONE_READBACK` | 购物车/支付链路遇滑块验证，需本人 |
| 美团外卖 | ⚠️ 加购到购物车，结果页交付 | 风控验证周期性出现；「拼好饭」无购物车 |
| 高德 | ⚠️ 部分 | 模型单次视觉请求长尾（ttfb 数十秒至 200 s 级） |
| DeepSeek | ⚠️ 输入/发送已通 | 初验因问题原文含标点失败，归一化已修，未重跑 |

### 6.3 未验证与已知限制（诚实清单）

1. **P5 输入法零干扰未验证**（需主屏持续打字的人工配合）。因此 `ime set` 仅用于回退路径，且被硬门挡住；文本输入主路径是无障碍 `ACTION_SET_TEXT`，全程不改主屏输入法。
2. **私有虚拟屏默认不被无障碍收录**：`flags=0` 时 `getWindowsOnAllDisplays()` 只返回 display [0]，改用 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 后通过。AOSP 内部为何跳过私有屏只验证了行为，未验证源码路径。
3. **`screencap -d` 在虚拟屏上不成立**（只接受物理 displayId），取帧实际只有 PixelCopy 与无障碍 `takeScreenshot` 两条路径。
4. **`RECONCILING` 只能把结果未知的写入交人**：恢复时只读核对"目标是否仍在当前页"，无法证明"已执行一次"，因此这类任务不会自动续跑。
5. **纯视觉写入 fail closed**：没有控件节点就构造不出目标，`TEXT` / `CREDENTIAL` 不可用；端侧 OCR 目前只支撑只读点击与后置条件，不支撑元素动作。
6. **副屏资源会退化**：一天内反复建屏/销毁约 30 次后，出现过"建屏返回 id 但 `am start --display` 被忽略、App 落到主屏"的现象，重启手机恢复。演示前建议重启一次，并把验证集中在一轮里跑完。
7. **平台限制**：Android 16 已移除 `moveTaskToDisplay`；App 内部新起的 task 仍可能落到主屏。美团通过 `VIRTUAL_DISPLAY_FLAG_TRUSTED` + 结果页交付绕过，但不是通用解。
8. **设备覆盖有限**：真机只有小米 13 / HyperOS 4.0，模拟器为 AVD API 37；AGP 9 + 内置 Kotlin + KSP 的组合只在本机复现过，首次构建需联网。
9. **模型质量仍是主要不确定性**：完成契约字段可能挑到页面上不存在的文案（系统会如实判 `MISMATCH` 或只交付结果页，不会假报成功）；`glm-5.3-flash` 存在 ttfb 长尾；`autoglm-phone` 不输出 JSON，未做专门适配。
10. **微信的中文输入**走"点聚焦 + 粘贴/按键"通路，未在真机复验；有控件树的 App 已由 `ACTION_SET_TEXT` 覆盖。

过程与证据见 [`实测记录.md`](实测记录.md)；工程侧的坑与复验命令见 [`handoff.md`](handoff.md)。

## 7. 环境与构建

环境**自包含在项目内**，不改本机全局设置。

| 项 | 位置 / 值 | 说明 |
|---|---|---|
| JDK | `.toolchain/jdk` | 复制自 Android Studio 自带 JBR，OpenJDK 25.0.3；**PATH 上的 `java` 是 JRE 1.8，不可用** |
| Gradle | `.gradle-home`（`GRADLE_USER_HOME`） | 9.3.1，wrapper 锁定；caches 与 wrapper 已随项目复制 |
| Android SDK | `D:\coding-env\android-sdk` | 经 `local.properties` 只读引用，不复制；platforms 35/36/37，build-tools 36.0.0 |
| 隐藏 API | `hidden-api/` | compileOnly 桩模块，只声明用到的 `@hide` 常量与方法签名，不参与打包 |
| 统一入口 | `scripts/gradle.ps1 <task>` / `scripts/gradle.sh <task>` | 注入 `JAVA_HOME` / `GRADLE_USER_HOME` / `ANDROID_HOME` 后转调 `gradlew` |

```powershell
# 首次在新机器上准备（幂等：已存在则跳过，只复制本地既有文件，不联网下载）
scripts\setup-env.ps1

# 构建
scripts\gradle.ps1 :app:assembleDebug
```

**禁止裸调 `gradlew.bat`**：PATH 上的 `java` 是 JRE 1.8，只会报出误导性的 `Unsupported class file major version`。Git Bash 下用 `scripts/gradle.sh`。

没有模型密钥时，可用 `scripts/fake-llm-server.py`（本地假后端）+ `adb reverse` 走完端到端；具体命令与剧本开关见 [`handoff.md`](handoff.md) §12。

### 版本矩阵

| 项 | 值 | 约束来源 |
|---|---|---|
| Gradle | 9.3.1 | AGP 9.1.1 要求 ≥ 9.3.1 |
| AGP | 9.1.1 | 2026 年 AndroidX 主线（Compose 1.12.x、navigation 2.10.x、lifecycle 2.11）要求 ≥ 9.1.0 |
| Kotlin | 2.2.10（**AGP 内置**） | 不 apply `org.jetbrains.kotlin.android`，不写 `kotlin{}` 块 |
| KSP | 2.2.10-2.0.2 | 被 AGP 9.1.1 的依赖锁定，不可单独提升 |
| Hilt | 2.60.1 | AGP 9 要求 ≥ 2.59；`@AndroidEntryPoint` 要求宿主继承 `ComponentActivity` |
| Room | 2.8.5 | 当前 schema 版本 4，1→4 迁移齐全 |
| Compose BOM | 2026.09.00 | Compose 编译器插件版本须等于 Kotlin 2.2.10 |
| kotlinx.serialization | 1.9.0 | 1.12.0 由 Kotlin 2.4 编译，metadata 超出 2.2.10 可读上限 |
| compileSdk / targetSdk | 37 / 36 | 编译期需要 37，运行期行为对齐演示机 HyperOS 4.0 |
| minSdk | 30 | `getWindowsOnAllDisplays()` 是唯一能按 displayId 取窗口树的入口 |

四条实测踩到的坑，改动前请先读：

1. **仓库里写死了镜像**（`settings.gradle.kts`），不依赖 `~/.gradle/init.gradle.kts`，否则换 `GRADLE_USER_HOME` 就解析不了依赖；Gradle 发行版走腾讯云镜像。
2. **`android.disallowKotlinSourceSets=false` 是必需的兼容开关**：KSP 2.2.10-2.0.2 仍通过 `kotlin.sourceSets` 注册生成目录，AGP 9 内置 Kotlin 默认拒绝。待 KSP 适配后可删。
3. **Android Studio 首次打开需改两处**：`Gradle JDK` 选 `GRADLE_LOCAL_JAVA_HOME`、`Gradle user home` 设为 `<repo>/.gradle-home`，否则 Studio 会在 `~/.gradle` 另建一套缓存。
4. **`scripts/*.ps1` 必须带 UTF-8 BOM**：Windows PowerShell 5.1 在无 BOM 时按 GBK 读取，中文注释会被打乱并导致解析失败。

## 8. 权限与普通用户落地

| 权限 | 用途 | 获取方式 |
|---|---|---|
| Shizuku 授权 | 拿 shell uid 执行建屏 / 取帧 / 注入 / 拉起 | 无线调试一次性配对 |
| 无障碍服务 | 读取副屏控件树、执行 `ACTION_SET_TEXT` | 应用内引导，先弹说明页（写明"不读主屏"） |
| `POST_NOTIFICATIONS` | 人工交接与完成报告 | 运行时申请 |
| `RECORD_AUDIO` | 任务页按住说话；录音交系统语音识别，本应用不落盘 | 运行时申请 |
| `FOREGROUND_SERVICE(_SPECIAL_USE)` | 执行期保活 | 声明即用 |
| `QUERY_ALL_PACKAGES` | 把"打开美团"解析成包名 | 声明 |
| `INTERNET` | 模型调用 | 声明 |

**普通用户手机上的落地步骤**（不需要 Root、不需要系统签名）：

1. 安装 APK（Android 11+），打开应用。
2. 按引导开启「无线调试」，配对并启动 Shizuku。重启手机后 shell server 不会自动拉起，需要重新启动一次。
3. 在系统设置里开启 Mimir 的无障碍服务（应用内引导会跳转，并说明只读取副屏）。
4. 允许通知；需要用语音输入时再授权麦克风。
5. HyperOS / MIUI 建议把省电策略设为「无限制」并允许自启动，否则前台服务与 Shizuku 容易被系统清掉。
6. 在设置页填 `base_url`、API Key 与模型名，点「测试」确认连通。

v1 明确不用：Root、系统签名、修改 ROM、悬浮窗（`SYSTEM_ALERT_WINDOW`）、全局剪贴板降级。**界面内容一律是不可信数据**：App 文案、广告、OCR 文本都不得被当作系统指令；模型只能输出固定动作 schema，写操作一律由本地授权与证据协议裁决。


## 9. AI 协作方式

项目规范见 [`项目必读文档.md`](项目必读文档.md)，落到流程上是四条：

1. **先定契约再写实现**：AIDL 接口、`ObservationSnapshot`、动作协议由人先定，AI 只做实现。
2. **每一处系统 API 调用都要人工事实核对**。参考实现里就有现成反面教材：整个文件被注释掉却留在仓库、无调用方的死代码——这类"看起来在跑其实没跑"的东西只能靠核对调用图发现。
3. **每完成一个可运行增量即 commit + push**，保持小步可回滚。
4. **AI 不做架构决策**：虚拟屏而非真屏、元素编号而非坐标、硬阻断而非复核放行，这三条由人负责并承担答辩压力。

## 11. 下一步

- **补设备覆盖**：在第二台 ROM（ColorOS / OriginOS）上跑 P1–P4 与一条真实任务，确认 `am start --display` 与窗口枚举的差异。
- **降低模型长尾**：按可视区域截断元素表、压缩 prompt，或换更快的视觉模型；把单步 ttfb 纳入预算调度。
- **扩大复验路线**：为微信、B 站注册只读复验路线，把 `DONE_READBACK` 提升到 `DONE`，让记忆写入覆盖更多 App。
- **纯视觉写入**：把 OCR 元素表接入目标指纹与后置条件，解除 `TEXT` / `CREDENTIAL` 在视觉通道下的硬门。
