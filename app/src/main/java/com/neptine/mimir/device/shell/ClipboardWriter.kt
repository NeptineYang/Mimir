package com.neptine.mimir.device.shell

import android.content.ClipData
import android.os.IBinder
import android.os.Process
import android.os.ServiceManager
import android.util.Log

/**
 * 系统剪贴板写入（shell 通道）。
 *
 * 为什么不能直接用 Context 的 ClipboardManager：shell 进程的上下文是从
 * ActivityThread.systemMain() 派生出来的，`createPackageContext` 会**继承父上下文的
 * opPackageName**，真机日志里就是 `packageName=com.android.shell opPackage=android`。
 * 而 ClipboardManager 把 `getOpPackageName()`（= android）当作 callingPackage 传给系统，
 * 与调用者 uid（2000）不匹配，剪贴板服务做 checkPackage 校验时会直接拒绝。
 *
 * 这里显式把 callingPackage 传成 com.android.shell，走与 `adb shell` 相同的 binder 路径。
 * 它是中文输入最后一条"不切输入法"的通路：写剪贴板 → 注入粘贴键。
 */
internal class ClipboardWriter {

    /**
     * 功能：把文本写进系统剪贴板
     * 参数：text 待写入的文本
     * 返回：写入被系统接受为 true
     *
     * 方法签名按参数类型逐位填，不写死：Android 各版本给 IClipboard.setPrimaryClip
     * 加过 attributionTag 与 userId，后续还可能继续加，写死某个签名会在升级后直接失效。
     */
    fun write(text: String): Boolean = try {
        val binder: IBinder = ServiceManager.getService(SERVICE_NAME)
            ?: throw IllegalStateException("剪贴板服务不存在")
        val stub = Class.forName(STUB_CLASS)
        val service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw IllegalStateException("剪贴板代理为空")
        val method = service.javaClass.methods
            .filter { it.name == SET_PRIMARY_CLIP }
            .firstOrNull { it.parameterTypes.firstOrNull() == ClipData::class.java }
            ?: throw IllegalStateException("找不到 setPrimaryClip(ClipData, …)")

        val args = method.parameterTypes.mapIndexed { index, type ->
            when {
                index == 0 -> ClipData.newPlainText(LABEL, text)
                type == String::class.java && index == 1 -> SHELL_PACKAGE
                type == String::class.java -> null
                type == Int::class.javaPrimitiveType && index == USER_ID_INDEX -> currentUserId()
                type == Int::class.javaPrimitiveType -> 0
                else -> null
            }
        }.toTypedArray()

        method.invoke(service, *args)
        Log.d(TAG, "写剪贴板成功：字符=${text.length} 形参=${method.parameterTypes.size}")
        true
    } catch (error: Throwable) {
        Log.d(TAG, "写剪贴板失败：${error.javaClass.simpleName} ${error.message}")
        false
    }

    /**
     * 功能：取当前进程所属用户
     * 参数：无
     * 返回：userId；Android 用 uid / 100000 表达
     */
    private fun currentUserId(): Int = Process.myUid() / PER_USER_RANGE

    private companion object {
        /** 诊断日志标签。 */
        const val TAG = "MimirShell"

        /** 剪贴板服务名。 */
        const val SERVICE_NAME = "clipboard"

        /** 系统剪贴板 AIDL 接口（@hide，只能按名字反射）。 */
        const val STUB_CLASS = "android.content.IClipboard\$Stub"

        /** 写入方法名。 */
        const val SET_PRIMARY_CLIP = "setPrimaryClip"

        /** 调用方包名：必须与 shell uid 匹配，否则被 checkPackage 拒绝。 */
        const val SHELL_PACKAGE = "com.android.shell"

        /** 剪贴板条目的标签，便于用户辨识来源。 */
        const val LABEL = "mimir"

        /** setPrimaryClip 里 userId 参数的下标（第 4 个形参）。 */
        const val USER_ID_INDEX = 3

        /** Android 多用户模型的 uid 步长。 */
        const val PER_USER_RANGE = 100000
    }
}
