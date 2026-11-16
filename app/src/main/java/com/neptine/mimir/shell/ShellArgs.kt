package com.neptine.mimir.shell

/**
 * 壳进程的启动参数。
 *
 * 由 adb shell（经应用内 adb 通道）下发，全部是 `--key=value` 形式，
 * 解析失败一律按"参数不合法"退出，不做兜底猜测。
 *
 * @property mode [MODE_SELFTEST] 只打印一行结果就退出；[MODE_SERVE] 常驻并交付 binder
 * @property protocol 交付协议版本，与 app 侧 [com.neptine.mimir.device.shell.ShellProtocol.PROTOCOL] 对齐
 * @property token 一次性握手令牌，app 生成并随命令下发，用于证明"这次启动是我们发起的"
 * @property appUid 调用方 uid，壳进程只用它做日志与校验提示
 * @property startedAt 启动时刻（毫秒），并发拉起时用较大的那个占位
 * @property apk 启动时使用的 APK 路径，用于 app 侧判断"进程是否还跑在旧版本上"
 * @property pkg 应用包名；壳进程的 Context 属于 com.android.shell，拼 provider authority 只能靠它
 */
data class ShellArgs(
    val mode: String,
    val protocol: Int,
    val token: String,
    val appUid: Int,
    val startedAt: Long,
    val apk: String,
    val pkg: String,
) {

    companion object {
        /** 只做自检：打印 uid 与 dex 可加载性后退出。 */
        const val MODE_SELFTEST = "selftest"

        /** 常驻：实例化服务并交付 binder。 */
        const val MODE_SERVE = "serve"

        /**
         * 功能：解析 `--key=value` 形式的启动参数
         * 参数：args 命令行参数（不含程序名与类名）
         * 返回：解析结果；缺必需字段时抛 IllegalArgumentException
         */
        fun parse(args: Array<String>): ShellArgs {
            val values = args.filter { it.startsWith("--") && it.contains('=') }
                .associate { it.substring(2).substringBefore('=') to it.substringAfter('=') }

            val mode = values["mode"] ?: error("缺少 --mode（selftest/serve）")
            require(mode == MODE_SELFTEST || mode == MODE_SERVE) { "未知 --mode=$mode" }

            return ShellArgs(
                mode = mode,
                protocol = values["proto"]?.toIntOrNull() ?: 0,
                token = values["token"].orEmpty(),
                appUid = values["app-uid"]?.toIntOrNull() ?: -1,
                startedAt = values["started"]?.toLongOrNull() ?: 0L,
                apk = values["apk"].orEmpty(),
                pkg = values["pkg"].orEmpty(),
            )
        }
    }
}
