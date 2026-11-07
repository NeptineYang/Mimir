# Mimir

Mimir 是一个 Android 手机 Agent：用户继续使用主屏的同时，Agent 在独立的虚拟屏上观察并操作真实 App，完成自然语言任务。支付、登录、验证码等需要本人完成的步骤会交给用户处理。

项目使用 Kotlin、Jetpack Compose、Shizuku 和无障碍服务，不需要 Root、系统签名或修改 ROM。

## 主要能力

- **主屏与 Agent 隔离**：通过 Shizuku shell 权限创建虚拟显示，避免 Agent 抢占用户前台画面和焦点。
- **多通道界面观测**：结合无障碍控件树、截图和端侧 OCR；模型优先选择元素编号，而不是直接猜测屏幕坐标。
- **任务执行与复核**：显式任务状态机、动作后置条件、结果复验和 Room 检查点。
- **风险分级与人工接管**：外部副作用按风险分级处理，资金、身份、验证码等操作硬阻断并交人。
- **本地记忆与凭据**：保存经复核的操作配方和用户确认的偏好；密码由 Android Keystore 保护，不发送给模型。
- **定时任务**：按设定时间自动执行；到点点亮屏幕但保持锁屏，需要确认的操作可选择自动批准或挂起。
- **端侧语音输入**：使用 sherpa-onnx 在设备上识别语音，指令音频不因语音识别而上传。
- **OpenAI 兼容模型**：可在应用内分别配置规划、执行和复核模型接口。

## 下载与安装

### 环境要求

- Android 11（API 30）或更高版本，推荐 arm64 真机。
- [Shizuku](https://github.com/RikkaApps/Shizuku) 可用，并能通过无线调试或现有 Shizuku 启动方式获得 shell 权限。
- 一个 OpenAI 兼容的 `chat/completions` 接口及其 API Key。

从 [GitHub Releases](https://github.com/NeptineYang/Mimir/releases/latest) 下载已签名的 `mimir-<version>.apk`，允许系统安装该 APK 后打开 Mimir。

> APK 支持 `arm64-v8a` 和 `x86_64`。不同 ROM 对虚拟显示、后台运行和无障碍服务的限制不同，模拟器可用于开发构建，但完整功能建议在真机上验证。

### 首次使用

1. 启动 Shizuku，并在 Mimir 中授予 Shizuku 权限。手机重启后可能需要重新启动 Shizuku。
2. 按应用内引导开启 Mimir 的无障碍服务。
3. 允许通知；需要语音输入时再授权麦克风。
4. 在设置页填写模型 `base_url`、API Key 和模型名，执行连通性测试。
5. 在任务页输入任务并确认任务边界。

HyperOS / MIUI 等系统可能需要将 Mimir 和 Shizuku 的省电策略设为“无限制”，以减少后台进程被系统终止的情况。

## 从源码构建

### 准备环境

- 支持 AGP 9.1.1 的 Android Studio。
- JDK 17 或项目构建工具支持的更新版本。
- Android SDK Platform 37、Build-Tools 36.0.0 和 Platform-Tools。
- Python 3，用于下载端侧语音识别依赖。

克隆仓库：

```bash
git clone https://github.com/NeptineYang/Mimir.git
cd Mimir
```

下载 sherpa-onnx AAR 和中文流式识别模型。这些文件约 120 MB，不提交到 Git；脚本会校验文件大小和 SHA-256，中断后可重复执行：

```bash
python scripts/fetch-asr-assets.py
```

### 使用 Android Studio

1. 用 Android Studio 打开仓库根目录，而不是只打开 `app/`。
2. 在 SDK Manager 中安装 Android SDK Platform 37 和对应构建工具。
3. 在 Gradle 设置中选择兼容的 Gradle JDK。Android Studio 会在本机生成 `local.properties`，不要提交该文件。
4. 等待 Gradle Sync 完成，选择 `app` 运行配置。
5. 连接 Android 11+ 设备并启用 USB 调试，然后运行 `app`；也可以先执行下面的命令行构建。

### 使用命令行

Windows：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS / Linux：

```bash
./gradlew :app:assembleDebug
```

调试 APK 输出到：

```text
app/build/outputs/apk/debug/app-debug.apk
```

首次构建需要联网下载 Gradle 和 Maven 依赖。直接修改代码后重新执行构建即可；普通贡献者不需要发布签名。

## 代码结构

```text
app/src/main/java/com/neptine/mimir/
├── agent/         任务规划、执行、风险判断、复核与编排
├── core/          任务、动作、观察和状态等核心契约
├── data/          Room、模型客户端、记忆、配置与凭据
├── device/        虚拟显示、输入、启动和 Shizuku 适配
├── observation/   控件树、截图、OCR 与一致观察
├── service/       无障碍、输入法、前台服务与通知
└── ui/            Compose 页面、组件和 ViewModel

hidden-api/       仅编译期使用的 Android 隐藏 API 桩
scripts/          语音依赖下载、本地假模型与设备调试脚本
```

## 修改与贡献

1. Fork 仓库并从最新默认分支创建功能分支。
2. 保持改动聚焦，避免提交与功能无关的格式化或重构。
3. 在提交前完成 `:app:assembleDebug`；涉及设备能力时，在真机上验证相关路径。
4. 不要提交 API Key、签名文件、`local.properties`、数据库、截图、日志或构建产物。
5. 提交 Pull Request 时说明行为变化、验证步骤和未覆盖的设备或场景。

本地假模型可用于不消耗真实模型额度的开发回路：

```bash
python scripts/fake-llm-server.py
```

它会监听本机 `8799` 端口；根据运行环境配置 `adb reverse` 后，将应用中的模型地址指向该本地服务。

## 安全边界

- Mimir 需要 Shizuku 和无障碍等高权限。请只安装自己信任的构建，并在授权前审查源码。
- App 文案、广告、OCR 结果和模型输出都按不可信输入处理；高风险动作不能仅凭模型声明执行。
- 支付、身份验证、验证码、人脸或指纹等步骤必须由用户本人完成。
- API Key 在设备上使用加密存储，但模型请求仍会发往你配置的模型服务；请确认该服务的隐私策略。
- 项目不承诺所有 App、ROM 或 Android 版本均可自动操作，失败时应以应用内状态和审计信息为准。

## Release 发布

以 `v*` 标签触发的 GitHub Actions 会下载语音依赖、使用仓库 Secrets 中的独立发布密钥签名 APK，并将 APK 上传到对应 Release。

维护者需要配置以下 Secrets：

- `MIMIR_KEYSTORE_BASE64`
- `MIMIR_STORE_PASSWORD`
- `MIMIR_KEY_ALIAS`
- `MIMIR_KEY_PASSWORD`

密钥库、密码和 `keystore.properties` 均不得提交到仓库。

## 许可证

项目源码采用 [Apache License 2.0](LICENSE)。

Inter、Space Grotesk 和 Space Mono 字体采用 SIL Open Font License 1.1；相关版权和许可证见 [NOTICE](NOTICE) 与 [licenses/Fonts-OFL-1.1.txt](licenses/Fonts-OFL-1.1.txt)。构建时下载的 sherpa-onnx、模型及其他依赖仍遵循各自许可证。
