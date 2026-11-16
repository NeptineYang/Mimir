package com.neptine.mimir.observation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审计截图清理策略的边界（v0.3.2）。
 *
 * 只测纯策略，不碰文件系统：TTL、总量上限与"进行中任务不可删"是本轮的三条硬约束，
 * 其中任何一条失效都可能让正在跑的任务在回放时丢帧。
 */
class AuditStoragePrunerTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    /**
     * 功能：构造一条目录统计
     * 参数：name 任务标识；ageDays 距今天数；bytes 占用字节；files 文件数
     * 返回：目录统计
     */
    private fun dir(name: String, ageDays: Long, bytes: Long, files: Int = 1): AuditTaskDir =
        AuditTaskDir(
            taskId = name,
            dir = File(name),
            newestAt = now - ageDays * day,
            bytes = bytes,
            files = files,
        )

    /**
     * 超过 30 天的目录被选中，未过期的不动。
     */
    @Test
    fun expiredDirsAreSelected() {
        val targets = AuditStoragePruner.select(
            dirs = listOf(dir("old", ageDays = 31, bytes = 100), dir("fresh", ageDays = 1, bytes = 100)),
            keep = emptySet(),
            now = now,
            ttlMs = 30 * day,
            maxBytes = 1024,
            maxFiles = 100,
        )
        assertEquals(listOf("old"), targets.map { it.taskId })
    }

    /**
     * 进行中的任务即使过期也不能删。
     */
    @Test
    fun activeTaskIsNeverSelected() {
        val targets = AuditStoragePruner.select(
            dirs = listOf(dir("old", ageDays = 31, bytes = 100), dir("active", ageDays = 31, bytes = 100)),
            keep = setOf("active"),
            now = now,
            ttlMs = 30 * day,
            maxBytes = 1024,
            maxFiles = 100,
        )
        assertEquals(listOf("old"), targets.map { it.taskId })
    }

    /**
     * 总量超限时从最旧的开始淘汰，直到回到上限以内。
     */
    @Test
    fun sizeCapEvictsOldestFirst() {
        val targets = AuditStoragePruner.select(
            dirs = listOf(
                dir("a", ageDays = 3, bytes = 100),
                dir("b", ageDays = 2, bytes = 100),
                dir("c", ageDays = 1, bytes = 100),
            ),
            keep = emptySet(),
            now = now,
            ttlMs = 30 * day,
            maxBytes = 250,
            maxFiles = 100,
        )
        assertEquals(listOf("a"), targets.map { it.taskId })
    }

    /**
     * 进行中的任务计入总量，但不能为了回到上限把它删掉。
     */
    @Test
    fun activeTaskBytesDoNotTriggerItsOwnDeletion() {
        val targets = AuditStoragePruner.select(
            dirs = listOf(dir("active", ageDays = 1, bytes = 1000)),
            keep = setOf("active"),
            now = now,
            ttlMs = 30 * day,
            maxBytes = 250,
            maxFiles = 100,
        )
        assertTrue(targets.isEmpty())
    }

    /**
     * 文件数上限同样生效。
     */
    @Test
    fun fileCapEvictsOldestFirst() {
        val targets = AuditStoragePruner.select(
            dirs = listOf(
                dir("a", ageDays = 3, bytes = 1, files = 2),
                dir("b", ageDays = 2, bytes = 1, files = 2),
            ),
            keep = emptySet(),
            now = now,
            ttlMs = 30 * day,
            maxBytes = 1024,
            maxFiles = 3,
        )
        assertEquals(listOf("a"), targets.map { it.taskId })
    }
}
