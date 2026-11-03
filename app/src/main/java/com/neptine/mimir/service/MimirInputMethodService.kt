package com.neptine.mimir.service

import android.inputmethodservice.InputMethodService
import android.view.View

/**
 * 自带输入法服务（设计文档 §2.2 / §4.7）。
 *
 * 存在的原因是 `input text` 不支持中文、空格与特殊字符；commitText 可提交任意 Unicode 字符串。
 * 它只提供"提交文本"的能力，不绘制自绘键盘。
 *
 * 硬门提醒：`ime set` 改的是系统默认输入法。P5 未通过（主屏键盘、候选、焦点、剪贴板均无变化）
 * 之前，禁止在真实任务中自动输入文本，也不允许改用全局剪贴板降级。
 */
class MimirInputMethodService : InputMethodService() {

    /**
     * 功能：服务创建时登记实例，供 app 进程内的 ImePort 取用
     * 参数：无
     * 返回：无
     */
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    /**
     * 功能：服务销毁时清理实例引用
     * 参数：无
     * 返回：无
     */
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * 功能：提供输入视图（返回 null 表示不自绘键盘）
     * 参数：无
     * 返回：恒为 null
     */
    override fun onCreateInputView(): View? = null

    /**
     * 功能：向当前焦点输入框提交文本
     * 参数：text 待提交的 Unicode 文本
     * 返回：是否提交成功；无输入连接时返回 false
     */
    fun commitText(text: String): Boolean {
        val connection = currentInputConnection ?: return false
        return connection.commitText(text, 1)
    }

    companion object {
        /** 当前运行中的输入法实例；未启动时为 null。 */
        @Volatile
        var instance: MimirInputMethodService? = null
            private set
    }
}
