package com.neptine.mimir.device

import com.neptine.mimir.core.contract.DisplayHandle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 活动副屏登记表（设计文档 §1.2 / §4.3）。
 *
 * 同一时刻只有一块副屏：运行中归 Orchestrator 使用；任务挂起时屏不销毁，
 * 由接管页轮询取帧显示实时画面。登记表是这两者之间唯一的交接点。
 *
 * 用 @Volatile 而不是加锁：读到的一定是某个完整句柄，读写各自是原子的，
 * 最坏情况只是预览多轮询一帧旧屏，下一轮就会拿到新句柄。
 */
@Singleton
class ActiveDisplayRegistry @Inject constructor() {

    @Volatile
    private var handle: DisplayHandle? = null

    /**
     * 当前副屏的归属任务。
     *
     * 强制打断一个**没有**持有副屏的挂起任务时，绝不能顺手拆掉别人正在用的屏：
     * 多个 NEED_HUMAN 任务可以同时存在，但活动副屏只有一块，必须按归属精确释放。
     */
    @Volatile
    private var ownerTaskId: String? = null

    /**
     * 功能：登记当前活动副屏及其归属任务
     * 参数：handle 建屏句柄；taskId 归属任务标识
     * 返回：无
     */
    fun bind(handle: DisplayHandle, taskId: String) {
        this.handle = handle
        this.ownerTaskId = taskId
    }

    /**
     * 功能：判断副屏是否归某任务所有
     * 参数：taskId 任务标识
     * 返回：是当前副屏的归属任务为 true
     */
    fun isOwnedBy(taskId: String): Boolean = ownerTaskId == taskId

    /**
     * 功能：读取当前活动副屏
     * 参数：无
     * 返回：活动副屏句柄；没有时为 null
     */
    fun current(): DisplayHandle? = handle

    /**
     * 功能：取出活动副屏并清空登记
     * 参数：无
     * 返回：被取出的句柄；没有时为 null
     *
     * 用于接管后复用同一块屏：调用方负责后续销毁或重新登记。
     */
    fun take(): DisplayHandle? {
        val taken = handle
        handle = null
        ownerTaskId = null
        return taken
    }

    /**
     * 功能：清除登记（仅当登记的正是这块屏）
     * 参数：handle 待清除的句柄
     * 返回：无
     *
     * 按 displayId + 代次比对，避免上一块屏的收尾把新屏的登记清掉。
     */
    fun clear(handle: DisplayHandle) {
        if (this.handle?.displayId == handle.displayId &&
            this.handle?.generation == handle.generation
        ) {
            this.handle = null
            ownerTaskId = null
        }
    }

    /**
     * 功能：是否已有副屏被占用
     * 参数：无
     * 返回：有活动副屏为 true
     *
     * 队列据此暂停：有任务挂起并保留画面时，不能给下一个任务再建一块屏。
     */
    fun isOccupied(): Boolean = handle != null
}
