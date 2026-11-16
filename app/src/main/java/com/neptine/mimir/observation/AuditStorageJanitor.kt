package com.neptine.mimir.observation

import com.neptine.mimir.data.db.dao.TaskDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 审计截图的清理调度（v0.3.2）。
 *
 * 截图仓库只管文件，这里补上"哪些任务不能删"：进行中（含等人处理）的任务
 * 仍需要回放与缩略图，不能因为超过 TTL 被清掉。调用时机是应用启动与打开设置页，
 * 不挂在每一步截图上——30 天的保留期不需要分钟级精度。
 */
@Singleton
class AuditStorageJanitor @Inject constructor(
    private val store: AuditScreenshotStore,
    private val taskDao: TaskDao,
) {

    /**
     * 功能：按保留期与总量上限清理一次
     * 参数：无
     * 返回：删除的目录数与释放的字节数
     */
    suspend fun sweep(): AuditCleanupResult = store.pruneExpired(activeTaskIds())

    /**
     * 功能：清空所有已完成任务的截图
     * 参数：无
     * 返回：删除的目录数与释放的字节数
     */
    suspend fun clear(): AuditCleanupResult = store.clear(activeTaskIds())

    /**
     * 功能：统计当前截图占用
     * 参数：无
     * 返回：总字节数与文件数
     */
    suspend fun usage(): AuditStorageUsage = store.usage()

    /**
     * 功能：取出必须保留截图的任务标识
     * 参数：无
     * 返回：所有未进入终态的任务标识
     */
    private suspend fun activeTaskIds(): Set<String> =
        taskDao.findUnfinished().mapTo(HashSet()) { it.taskId }
}
