package com.neptine.mimir.device

/**
 * 文本输入端口（设计文档 §2.2 / §4.7）。
 *
 * 存在的原因：`input text` 不支持中文、空格与特殊字符，自带 InputMethodService 用
 * commitText 才能提交任意 Unicode 字符串。
 *
 * 硬门：`ime set` 改的是系统默认输入法，一旦影响主屏键盘、候选、焦点或剪贴板即违反零干扰。
 * P5 未通过前禁止在真实任务中自动输入文本，也不允许用全局剪贴板降级。
 *
 * 另外用途：任务执行期临时把默认输入法换成自带的“无键盘”输入法，避免目标 App 的
 * 输入框聚焦时在主屏弹出可见键盘；任务结束或转人工时 [restore] 切回。这个接管会
 * 让任务执行期间主屏没有键盘，是刻意接受的代价（真机实测主屏弹键盘更干扰）。
 */
interface ImePort {

    /**
     * 功能：把自带输入法切为系统默认并绑定到副屏
     * 参数：无
     * 返回：切换是否成功
     */
    suspend fun select(): Boolean

    /**
     * 功能：向当前焦点输入框提交文本
     * 参数：text 待提交的 Unicode 文本
     * 返回：无
     */
    suspend fun commitText(text: String)

    /**
     * 功能：自带输入法当前是否已经是系统默认
     * 参数：无
     * 返回：是则为 true；不需要 Shizuku，读 secure 设置即可
     */
    fun isActive(): Boolean

    /**
     * 功能：恢复用户原本的输入法
     * 参数：无
     * 返回：无
     */
    suspend fun restore()

    /**
     * 功能：进程重启后清理上次任务遗留的输入法接管
     * 参数：无
     * 返回：无；当前输入法不是自己时什么都不做
     */
    suspend fun restoreIfLeftOver()
}
