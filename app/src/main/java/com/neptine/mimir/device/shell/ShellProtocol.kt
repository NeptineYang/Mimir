package com.neptine.mimir.device.shell

/**
 * 自建特权通道的交付协议常量。
 *
 * 协议号 [PROTOCOL] 承担 Shizuku `UserServiceArgs.version` 的角色：AIDL 或壳进程实现
 * 的语义一变就 +1，app 收到不匹配的 binder 会直接要求对方退出，避免用到旧实现。
 * 变更记录：
 * - 1：首个版本，含 `shutdown()` 与 20 个设备层方法。
 */
object ShellProtocol {

    /** 交付协议版本。 */
    const val PROTOCOL = 1

    /**
     * 自建壳进程的进程名。
     *
     * 刻意与 Shizuku UserService 的 `:shell` 区分：两者同属 shell uid，而 `ShellJanitor`
     * 按进程名清理残留进程——同名会让两个宿主互相把对方清掉（实测过一次）。
     * 分开之后各清各的，两套宿主可以并存。
     */
    const val PROCESS_NAME = "com.neptine.mimir:privileged"

    /** provider authority 的后缀；完整值是 `<applicationId>.shell`。 */
    const val AUTHORITY_SUFFIX = ".shell"

    /** 交付方法名。 */
    const val METHOD_ATTACH = "attach"

    /** 载荷键：壳进程的 binder。 */
    const val KEY_BINDER = "binder"

    /** 载荷键：一次性握手令牌。 */
    const val KEY_TOKEN = "token"

    /** 载荷键：协议版本。 */
    const val KEY_PROTOCOL = "proto"

    /** 载荷键：壳进程启动时使用的 APK 路径。 */
    const val KEY_APK = "apk"

    /** 载荷键：壳进程 pid。 */
    const val KEY_PID = "pid"

    /** 载荷键：壳进程启动时刻，用于并发拉起时仲裁。 */
    const val KEY_STARTED = "started"

    /** 回执键：是否接受。 */
    const val KEY_ACCEPTED = "accepted"

    /** 回执键：拒绝原因。 */
    const val KEY_REJECT = "reject"
}
