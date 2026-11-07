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
     * 功能：取某任务的截图目录
     * 参数：taskId 任务标识
     * 返回：目录对象
     *
     * 任务标识是 UUID，这里仍过滤一次特殊字符：任务标识来自数据库与其他模块，
     * 不能因为上游将来换了格式就把路径拼到目录之外。
     */
    private fun taskDir(taskId: String): File {
        val safe = taskId.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "unknown" }
        return File(File(context.filesDir, AUDIT_DIR), safe)
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

        /** 单任务最多保留的截图文件数。 */
        const val MAX_FILES_PER_TASK = 120
    }
}
