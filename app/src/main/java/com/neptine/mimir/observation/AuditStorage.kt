package com.neptine.mimir.observation

import java.io.File

/**
 * 审计截图的占用统计（字节数与文件数）。
 *
 * 设置页只读这两个数：直接展示目录大小比"估算每步多少 KB"可信，
 * 也避免把截图数量写死成与步数绑定的公式。
 */
data class AuditStorageUsage(
    /** 目录内所有截图的总字节数。 */
    val bytes: Long,
    /** 目录内截图文件数（不含 .nomedia）。 */
    val files: Int,
)

/**
 * 一次清理的结果。
 *
 * @property deletedDirs 被删除的任务目录数
 * @property freedBytes 按删除前统计口径释放的字节数
 */
data class AuditCleanupResult(
    val deletedDirs: Int,
    val freedBytes: Long,
)

/**
 * 单个任务截图目录的统计快照；清理决策只依赖这几个数与任务标识。
 *
 * @property taskId 目录名（即任务标识）
 * @property dir 目录对象
 * @property newestAt 目录内最新文件的修改时间；空目录回落到目录自身
 * @property bytes 目录内文件总字节数
 * @property files 目录内文件数
 */
internal data class AuditTaskDir(
    val taskId: String,
    val dir: File,
    val newestAt: Long,
    val bytes: Long,
    val files: Int,
)

/**
 * 审计截图的清理策略（纯文件系统逻辑，不依赖 Android，可直接单测）。
 *
 * 先按 TTL 删过期任务，再按"总量上限"从最旧的任务开始淘汰；
 * 进行中的任务由 [keep] 保护，任何一条规则都不会动它。
 */
internal object AuditStoragePruner {

    /**
     * 功能：计算本轮要删除的目录
     * 参数：dirs 全部目录统计；keep 需保留的任务标识；now 当前时间戳（毫秒）；
     *       ttlMs 保留期（毫秒）；maxBytes 总字节上限；maxFiles 总文件数上限
     * 返回：按删除顺序排列的目录列表：先过期的，再按最旧优先淘汰的
     *
     * 空目录也会随 TTL 一起清掉：它的 newestAt 取目录自身时间，不会永远留存。
     */
    fun select(
        dirs: List<AuditTaskDir>,
        keep: Set<String>,
        now: Long,
        ttlMs: Long,
        maxBytes: Long,
        maxFiles: Int,
    ): List<AuditTaskDir> {
        val expired = dirs.filter { it.taskId !in keep && it.newestAt < now - ttlMs }
        val expiredIds = expired.mapTo(HashSet()) { it.taskId }

        var bytes = dirs.filterNot { it.taskId in expiredIds }.sumOf { it.bytes }
        var files = dirs.filterNot { it.taskId in expiredIds }.sumOf { it.files }
        val evicted = mutableListOf<AuditTaskDir>()

        // 只淘汰已完成的任务；进行中的任务即使超限也保留，由 TTL 在任务结束后处理。
        for (dir in dirs.filter { it.taskId !in keep && it.taskId !in expiredIds }.sortedBy { it.newestAt }) {
            if (bytes <= maxBytes && files <= maxFiles) break
            evicted += dir
            bytes -= dir.bytes
            files -= dir.files
        }
        return expired + evicted
    }
}
