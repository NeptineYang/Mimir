package com.neptine.mimir.observation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 审计截图仓库（设计文档 §4.4）。
 *
 * 每步的前后帧以 WebP 原样落到应用私有目录，路径写进 `audit.screenshot_before/after`；
 * 接管页与审计页据此做"当时的画面"回放。图片不进 Room：SQLite 存二进制会把库撑大，
 * 备份与迁移也会变慢，路径 + 文件是更合适的粒度。
 *
 * 目录治理（v0.3.2）：私有目录天然不进系统相册；另放 `.nomedia` 作防御。
 * 过期清理与总量兜底见 [pruneExpired]，调用方负责传入"进行中任务"的保留集合。
 */
@Singleton
class AuditScreenshotStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * 功能：保存一帧审计截图
     * 参数：taskId 任务标识；stepIndex 步序；suffix before/after；bytes 图像字节
     * 返回：绝对路径；没有图像或写入失败时为 null
     *
     * 失败只返回 null，不抛异常：截图存不下来不应该让任务失败，回放时显示"未留存"即可。
     */
    suspend fun save(taskId: String, stepIndex: Int, suffix: String, bytes: ByteArray?): String? =
        withContext(Dispatchers.IO) {
            if (bytes == null || bytes.isEmpty()) return@withContext null
            runCatching {
                ensureRoot()
                val dir = taskDir(taskId)
                dir.mkdirs()
                prune(dir)
                val file = File(dir, "step_${stepIndex}_$suffix.webp")
                file.writeBytes(bytes)
                file.absolutePath
            }.getOrNull()
        }

    /**
     * 功能：读取一帧审计截图
     * 参数：path 截图绝对路径
     * 返回：解码后的位图；路径为空、文件不在或解码失败时为 null
     */
    suspend fun load(path: String?): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    /**
     * 功能：按缩略图尺寸读取一帧审计截图
     * 参数：path 截图绝对路径；maxWidth 期望的最大宽度（像素）
     * 返回：解码后的位图；路径为空、文件不在或解码失败时为 null
     *
     * 任务卡里只显示几十 dp 的画面，按原图解码（720×1600）会白占几 MB；
     * 先读边界算 inSampleSize，再按上限二次收缩。
     */
    suspend fun loadThumb(path: String?, maxWidth: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrEmpty()) return@withContext null
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2

            val bitmap = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@withContext null
            if (bitmap.width <= maxWidth) {
                bitmap
            } else {
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    maxWidth,
                    (bitmap.height * maxWidth / bitmap.width).coerceAtLeast(1),
                    true,
                )
                bitmap.recycle()
                scaled
            }
        }.getOrNull()
    }

    /**
     * 功能：删除某任务的全部审计截图
     * 参数：taskId 任务标识
     * 返回：无
     *
     * 只用于用户主动删除历史任务：截图与数据库记录同生共死，避免留下无法再被引用的文件。
     */
    suspend fun deleteTask(taskId: String) = withContext(Dispatchers.IO) {
        runCatching { taskDir(taskId).deleteRecursively() }
        Unit
    }

    /**
     * 功能：统计审计截图的占用
     * 参数：无
     * 返回：总字节数与文件数；读不到目录时为 0
     */
    suspend fun usage(): AuditStorageUsage = withContext(Dispatchers.IO) {
        val root = ensureRoot()
        val files = root.walkTopDown().filter { it.isFile && it.name != NOMEDIA }.toList()
        AuditStorageUsage(bytes = files.sumOf { it.length() }, files = files.size)
    }

    /**
     * 功能：按保留期与总量上限清理过期截图
     * 参数：keepTaskIds 需要保留的任务标识（进行中）；now 当前时间戳（毫秒）
     * 返回：删除的目录数与释放的字节数
     *
     * TTL 按目录内最新文件时间计算：截图只在任务执行期间写入，
     * 最新帧的时间近似等于任务结束时间，不需要为此回查数据库。
     */
    suspend fun pruneExpired(
        keepTaskIds: Set<String>,
        now: Long = System.currentTimeMillis(),
    ): AuditCleanupResult = withContext(Dispatchers.IO) {
        val root = ensureRoot()
        val targets = AuditStoragePruner.select(
            dirs = scan(root),
            keep = keepTaskIds,
            now = now,
            ttlMs = RETENTION_MS,
            maxBytes = MAX_TOTAL_BYTES,
            maxFiles = MAX_TOTAL_FILES,
        )
        deleteAll(targets)
    }

    /**
     * 功能：删除所有已完成任务的截图（设置页"清理截图"）
     * 参数：keepTaskIds 需要保留的任务标识（进行中）
     * 返回：删除的目录数与释放的字节数
     *
     * 与 [pruneExpired] 的差别只在判据：这里不看时间，直接清空可删部分。
     */
    suspend fun clear(keepTaskIds: Set<String>): AuditCleanupResult = withContext(Dispatchers.IO) {
        val root = ensureRoot()
        val targets = scan(root).filter { it.taskId !in keepTaskIds }
        deleteAll(targets)
    }

    /**
     * 功能：取某任务的截图目录
     * 参数：taskId 任务标识
     * 返回：目录对象
     *
     * 任务标识是 UUID，这里仍过滤一次特殊字符：任务标识来自数据库与其他模块，
     * 不能因为上游将来换了格式就把路径拼到目录之外。
     */
    private fun taskDir(taskId: String): File {
        val safe = taskId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "unknown" }
        return File(auditRoot(), safe)
    }

    /**
     * 功能：取截图根目录并确保 `.nomedia` 存在
     * 参数：无
     * 返回：根目录对象
     *
     * 根目录在应用私有 `filesDir` 下，媒体扫描器本来就不会收录；
     * `.nomedia` 是防御性标记：将来若迁到外部私有目录，也不会突然出现在相册里。
     */
    private fun ensureRoot(): File {
        val root = auditRoot()
        runCatching {
            root.mkdirs()
            val marker = File(root, NOMEDIA)
            if (!marker.exists()) marker.createNewFile()
        }
        return root
    }

    /** 截图根目录。 */
    private fun auditRoot(): File = File(context.filesDir, AUDIT_DIR)

    /**
     * 功能：扫描全部任务截图目录
     * 参数：root 根目录
     * 返回：目录统计列表；读不到时为空的列表
     *
     * 最新文件时间用 `lastModified`：目录内每帧写入都会刷新，足以代表任务活跃时间。
     */
    private fun scan(root: File): List<AuditTaskDir> =
        root.listFiles()?.filter { it.isDirectory }?.map { dir ->
            val files = dir.listFiles().orEmpty()
            AuditTaskDir(
                taskId = dir.name,
                dir = dir,
                newestAt = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                bytes = files.sumOf { it.length() },
                files = files.size,
            )
        }.orEmpty()

    /**
     * 功能：删除一批任务目录
     * 参数：targets 待删目录统计
     * 返回：实际删除的目录数与释放的字节数
     *
     * 单个目录删失败不影响其它目录；释放字节数只在删除成功后累计。
     */
    private fun deleteAll(targets: List<AuditTaskDir>): AuditCleanupResult {
        var deleted = 0
        var freed = 0L
        targets.forEach { target ->
            val ok = runCatching { target.dir.deleteRecursively() }.getOrDefault(false)
            if (ok) {
                deleted++
                freed += target.bytes
            }
        }
        return AuditCleanupResult(deletedDirs = deleted, freedBytes = freed)
    }

    /**
     * 功能：裁掉每任务过旧的截图
     * 参数：dir 任务截图目录
     * 返回：无
     *
     * 每步两张、单任务最多约 40 步，不裁剪会在长期使用后无限增长；
     * 只保留最近 120 个文件，回放仍够覆盖一整轮任务。
     */
    private fun prune(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES_PER_TASK) return
        files.take(files.size - MAX_FILES_PER_TASK).forEach { runCatching { it.delete() } }
    }

    private companion object {
        /** 截图根目录名，位于应用私有 files 下。 */
        const val AUDIT_DIR = "audit"

        /** 防御性媒体标记：告诉扫描器这个目录不是相册内容。 */
        const val NOMEDIA = ".nomedia"

        /** 单任务最多保留的截图文件数。 */
        const val MAX_FILES_PER_TASK = 120

        /** 保留期：30 天（用户要求"较长 TTL"）。 */
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        /** 全部截图的总字节上限：300MB。 */
        const val MAX_TOTAL_BYTES = 300L * 1024 * 1024

        /** 全部截图的文件数上限：2000。 */
        const val MAX_TOTAL_FILES = 2000
    }
}
