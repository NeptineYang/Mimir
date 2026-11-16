package com.neptine.mimir.core

/**
 * 本次进程的时间窗。
 *
 * 任务页的"本次运行"只显示这个进程里结束的任务：进程死亡后时间窗重置，
 * 记录自然清空，不需要额外建表或手动清理。
 */
object ProcessWindow {

    /** 进程启动时间；未显式标记时回落到首次访问时间。 */
    private var start: Long = System.currentTimeMillis()

    /** 时间窗起点（毫秒）。 */
    val startedAt: Long get() = start

    /**
     * 功能：在进程入口固定时间窗起点
     * 参数：无
     * 返回：无
     *
     * 由 Application.onCreate 调用：定时任务可能在用户打开任务页之前就执行完，
     * 若等到首次访问才记起点，这些任务会被漏掉。
     */
    fun markStart() {
        start = System.currentTimeMillis()
    }
}
