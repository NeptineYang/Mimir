package com.neptine.mimir.observation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.UiElement
import com.neptine.mimir.service.MimirAccessibilityService
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 控件树采集（设计文档 §2.3）。
 *
 * 唯一能按 displayId 取窗口树的入口是 AccessibilityService.getWindowsOnAllDisplays()（API 30+），
 * 且必须在这里做 displayId 白名单过滤：主屏窗口进入快照就等于把用户主屏内容送进模型（§4.6）。
 *
 * 元素筛选规则（宁多勿漏，但控制长度）：
 * - 保留：可点击 / 可编辑 / 语义类控件 / 有文本或无障碍描述；
 * - 剔除：短边小于 24dp 的噪声、完全在屏外、isVisibleToUser=false；
 * - 纯容器（不可交互且无文本）不列出，其子节点照常列出——避免大容器挤掉真正的小按钮；
 * - **可交互容器自身没有文本时，用子树文本作标签**：真实 App 里列表行的文字常在不可点击的
 *   子 TextView 中，只留一个"无文本"的 LinearLayout 会让模型和 Guard 都无从判断点的是什么。
 */
class AccessibilityTreeProvider : TreeProvider {

    /**
     * 功能：采集指定副屏的控件树
     * 参数：handle 建屏句柄
     * 返回：一帧控件树；无障碍未连接或该屏无窗口时抛异常，由上层降级为纯视觉
     */
    override suspend fun capture(handle: DisplayHandle): TreeFrame = withContext(Dispatchers.IO) {
        val service = MimirAccessibilityService.instance
            ?: throw IllegalStateException("无障碍服务未连接，无法读取控件树")

        val windows = service.windowsOnDisplay(handle.displayId)
        if (windows.isEmpty()) throw IllegalStateException("副屏 ${handle.displayId} 上没有可见窗口")

        // 副屏是 PUBLIC 显示，SystemUI 的通知栏/气泡等系统窗口也会镜像过来；
        // 只取应用窗口，否则可能对着通知栏读树、甚至把点击打到通知栏上。
        val appWindows = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (appWindows.isEmpty()) {
            throw IllegalStateException("副屏 ${handle.displayId} 上只有系统窗口，没有应用窗口")
        }

        // 前台窗口偶尔会短暂拿不到根节点（弹窗切换、窗口重建），退到其它应用窗口再试一次；
        // 全部拿不到才抛异常，由上层重采或转人工。
        val orderedWindows = appWindows.sortedByDescending { it.isFocused }
        val rootWindow = orderedWindows.firstOrNull { it.root != null }
            ?: throw IllegalStateException("窗口根节点不可读")
        val root = rootWindow.root ?: throw IllegalStateException("窗口根节点不可读")

        val minSidePx = 24f * handle.spec.dpi / 160f
        val collector = Collector(handle.spec.width, handle.spec.height, minSidePx.toInt())
        collector.visit(root, ancestorPath = "")
        Log.d(
            TAG,
            "display=${handle.displayId} windows=${windows.size} title=${rootWindow.title} " +
                "pkg=${root.packageName} children=${root.childCount} kept=${collector.kept.size} " +
                "visited=${collector.visited} sample=${collector.sample} dropped=${collector.dropped}",
        )

        return@withContext TreeFrame(
            packageName = root.packageName?.toString().orEmpty(),
            activityName = rootWindow.title?.toString().orEmpty(),
            treeHash = hashOf(collector.kept),
            layoutHash = layoutHashOf(collector.kept),
            elements = collector.kept,
            overflowCount = 0,
        )
    }

    /**
     * 功能：计算元素集合的结构哈希（含文本）
     * 参数：elements 已筛选的元素
     * 返回：16 位十六进制摘要
     *
     * 只纳入会影响"点哪里"的属性，避开纯展示内容带来的抖动。
     * 这个哈希用于"动作是否改变了页面内容"（后置条件），因此文本必须计入。
     */
    private fun hashOf(elements: List<UiElement>): String {
        val builder = StringBuilder()
        for (element in elements) {
            builder.append(element.className).append('|')
                .append(element.text).append('|')
                .append(element.contentDescription).append('|')
                .append(element.bounds.left).append(',').append(element.bounds.top).append(',')
                .append(element.bounds.right).append(',').append(element.bounds.bottom).append('|')
                .append(element.clickable).append('|')
                .append(element.editable).append('\n')
        }
        return digestOf(builder)
    }

    /**
     * 功能：计算元素集合的布局哈希（只含类名与位置）
     * 参数：elements 已筛选的元素
     * 返回：16 位十六进制摘要
     *
     * 一致观察（树 A → 截图 → 树 B）用它配对，而不是用含文本的结构哈希：
     * 开屏广告倒计时、轮播图页码、直播在线人数这类文本每帧都在变，但页面结构没动，
     * 用结构哈希会让这类页面永远"不一致"，观测层直接判任务失败。
     */
    private fun layoutHashOf(elements: List<UiElement>): String {
        val builder = StringBuilder()
        for (element in elements) {
            builder.append(element.className).append('|')
                .append(element.bounds.left).append(',').append(element.bounds.top).append(',')
                .append(element.bounds.right).append(',').append(element.bounds.bottom).append('|')
                .append(element.clickable).append('|')
                .append(element.editable).append('\n')
        }
        return digestOf(builder)
    }

    /**
     * 功能：把拼好的字符串摘要成 16 位十六进制
     * 参数：builder 已写入的属性串
     * 返回：SHA-256 前 8 字节的十六进制表示
     */
    private fun digestOf(builder: StringBuilder): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(builder.toString().toByteArray())
        return digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
    }

    /**
     * 递归遍历器：把节点树摊平成元素列表，并记录祖先路径用于同文本消歧。
     *
     * @property width 副屏原生宽度（像素）
     * @property height 副屏原生高度（像素）
     * @property minSidePx 噪声过滤的短边阈值（24dp 对应的像素）
     */
    private class Collector(
        private val width: Int,
        private val height: Int,
        private val minSidePx: Int,
    ) {
        /** 通过筛选的元素，尚未编号。 */
        val kept = mutableListOf<UiElement>()

        /** 遍历到的节点数，用于诊断。 */
        var visited = 0
            private set

        /** 前几个节点的摘要，用于诊断"树为什么是空的"。 */
        val sample = mutableListOf<String>()

        /** 前几个"有文本却被丢弃"的节点，用于诊断语义为什么丢失。 */
        val dropped = mutableListOf<String>()

        /**
         * 功能：访问一个节点并递归其子节点
         * 参数：node 当前节点；ancestorPath 祖先类名路径
         * 返回：该子树（含自身）的文本标签
         *
         * 子节点用完即 recycle：API 33 起它已是空操作，但 minSdk 30 上节点来自池，
         * 一次观测遍历数百个节点若不回收会持续占用池位。
         */
        @Suppress("DEPRECATION")
        fun visit(node: AccessibilityNodeInfo, ancestorPath: String): String {
            val className = node.className?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            val clickable = node.isClickable
            val editable = node.isEditable
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)

            visited++
            if (sample.size < SAMPLE_LIMIT) {
                sample += "$className|vis=${node.isVisibleToUser}|clk=$clickable|" +
                    "${rect.left},${rect.top},${rect.right},${rect.bottom}|$text"
            }

            val childPath = if (ancestorPath.isEmpty()) className else "$ancestorPath/$className"
            val childLabels = mutableListOf<String>()
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    childLabels += visit(child, childPath)
                } finally {
                    child.recycle()
                }
            }

            val ownLabel = text.ifEmpty { description }
            val subtreeLabel = childLabels.filter { it.isNotEmpty() }.joinToString(" ")
            val label = ownLabel.ifEmpty { subtreeLabel.take(LABEL_LIMIT) }

            if (!isRetained(node, className, text, description, clickable, editable, rect)) {
                if (ownLabel.isNotEmpty() && dropped.size < SAMPLE_LIMIT) {
                    dropped += "$className|vis=${node.isVisibleToUser}|" +
                        "${rect.width()}x${rect.height()}|$ownLabel"
                }
                return label
            }

            kept.add(
                UiElement(
                    index = 0,
                    className = className,
                    text = text.ifEmpty { subtreeLabel.take(LABEL_LIMIT) },
                    contentDescription = description,
                    bounds = Bounds(rect.left, rect.top, rect.right, rect.bottom),
                    clickable = clickable,
                    editable = editable,
                    resourceId = node.viewIdResourceName,
                    ancestorPath = ancestorPath.ifEmpty { null },
                    password = node.isPassword,
                ),
            )
            return label
        }

        /**
         * 功能：判断单节点是否满足保留规则
         * 参数：node 节点；className 类名；text 文本；description 无障碍描述；
         *       clickable/editable 可操作性；rect 屏幕范围
         * 返回：保留为 true
         */
        private fun isRetained(
            node: AccessibilityNodeInfo,
            className: String,
            text: String,
            description: String,
            clickable: Boolean,
            editable: Boolean,
            rect: android.graphics.Rect,
        ): Boolean {
            if (!node.isVisibleToUser) return false
            if (rect.isEmpty) return false
            // 完全在屏外
            if (rect.right <= 0 || rect.bottom <= 0 || rect.left >= width || rect.top >= height) return false

            val hasSemantics = text.isNotEmpty() || description.isNotEmpty()
            // 噪声过滤只针对"没有文本"的装饰节点（分割线、占位、极小的图标点）。
            // 有文本的节点不受短边限制：HyperOS 的「全部参数与信息」把每行渲染成 36px 高的
            // TextView，按 24dp 一刀切会把「Android 版本」这类关键行整行丢掉——
            // 元素表里没有它，后置条件就永远命中不了，任务只能反复驳回直到重规划上限。
            if (!hasSemantics && (rect.width() < minSidePx || rect.height() < minSidePx)) return false

            val semanticClass = SEMANTIC_CLASSES.any { className.endsWith(it) }
            return clickable || editable || hasSemantics || semanticClass
        }
    }

    private companion object {
        /** 诊断日志标签。 */
        const val TAG = "MimirTree"

        /** 摘要截断长度（字节）。 */
        const val HASH_BYTES = 8

        /** 即使没有文本也要保留的语义类控件。 */
        val SEMANTIC_CLASSES = listOf("Button", "EditText", "ImageButton", "CheckBox", "Switch", "RadioButton")

        /** 诊断采样上限。 */
        const val SAMPLE_LIMIT = 6

        /** 子树聚合文本的长度上限，避免整页容器把所有文案拼成一条。 */
        const val LABEL_LIMIT = 80
    }
}
