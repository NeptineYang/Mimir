// vendor 自 Shizuku（github.com/RikkaApps/Shizuku，Apache-2.0）manager/adb/AdbException.kt；
// 改动：包名换成 com.neptine.mimir.adblink、去掉 rikka/androidx 私有依赖、改用 Maven 版 Conscrypt、
// 增加读超时参数。其余协议实现与上游一致，便于日后对照排查。
package com.neptine.mimir.adblink

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
