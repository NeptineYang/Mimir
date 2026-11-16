// vendor 自 Shizuku（github.com/RikkaApps/Shizuku，Apache-2.0）manager/adb/AdbProtocol.kt；
// 改动：包名换成 com.neptine.mimir.adblink、去掉 rikka/androidx 私有依赖、改用 Maven 版 Conscrypt、
// 增加读超时参数。其余协议实现与上游一致，便于日后对照排查。
package com.neptine.mimir.adblink

object AdbProtocol {

    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534C5453

    const val A_VERSION = 0x01000000
    const val A_MAXDATA = 4096

    const val A_STLS_VERSION = 0x01000000

    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}