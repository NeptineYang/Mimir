package com.neptine.mimir.agent

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 待查看结果登记表。
 *
 * 查看类任务成功时副屏不立刻销毁：结果页留在副屏上，等用户点结果通知。
 * 点击时把整个根任务搬到主屏（用户主动操作，允许置顶）；超时未点则释放副屏。
 * 只存任务标识，不存系统 taskId：系统任务在点击时才搬，避免提前占用户屏幕。
 */
@Singleton
class ResultViewRegistry @Inject constructor() {

    private val pending = ConcurrentHashMap<String, String>()

    /**
     * 功能：登记一个等待用户查看的结果页
     * 参数：mimirTaskId Mimir 任务标识；packageName 目标包名
     * 返回：无
     */
    fun markPending(mimirTaskId: String, packageName: String) {
        pending[mimirTaskId] = packageName
    }

    /**
     * 功能：判断某任务是否还有待查看的结果页
     * 参数：mimirTaskId Mimir 任务标识
     * 返回：待查看为 true
     */
    fun isPending(mimirTaskId: String): Boolean = pending.containsKey(mimirTaskId)

    /**
     * 功能：清除登记
     * 参数：mimirTaskId Mimir 任务标识
     * 返回：无
     */
    fun clear(mimirTaskId: String) {
        pending.remove(mimirTaskId)
    }
}
