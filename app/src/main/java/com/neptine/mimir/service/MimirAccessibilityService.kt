package com.neptine.mimir.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.neptine.mimir.core.contract.Bounds

/**
 * 副屏控件树采集服务（设计文档 §2.2 / §2.3）。
 *
 * 这是唯一能按 displayId 拿到窗口树的入口（getWindowsOnAllDisplays，API 30+）；
 * 实现时必须按本任务副屏的 displayId 白名单过滤，主屏窗口不得进入快照、prompt 或日志。
 *
 * 骨架期只维护实例引用与最小回调，取树逻辑待设备层实现后接入。
 */
class MimirAccessibilityService : AccessibilityService() {

    /**
     * 功能：服务连接成功后登记实例
     * 参数：无
     * 返回：无
     */
    override fun onServiceConnected() {
        instance = this
    }

    /**
     * 功能：接收无障碍事件（骨架期不处理）
     * 参数：event 事件对象
     * 返回：无
     *
     * 观测走主动轮询而非事件驱动：一致快照要求"树 A → 截图 → 树 B"，
     * 事件流只会带来竞态。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    /**
     * 功能：处理系统中断请求（骨架期无操作）
     * 参数：无
     * 返回：无
     */
    override fun onInterrupt() = Unit

    /**
     * 功能：取指定显示上的窗口列表
     * 参数：displayId 目标显示
     * 返回：该显示上的窗口；无障碍未提供该显示时为空列表
     *
     * 这里是一次性的白名单过滤点：只有本任务副屏的窗口才允许进入观测，
     * 主屏窗口在数据入口就被丢弃（§4.6 隐私要求）。
     */
    fun windowsOnDisplay(displayId: Int): List<AccessibilityWindowInfo> =
        windowsOnAllDisplays[displayId].orEmpty()

    /**
     * 功能：列出无障碍框架当前能看到的显示编号
     * 参数：无
     * 返回：displayId 列表
     *
     * 只返回编号、不返回任何窗口内容：用于回答"副屏到底有没有被无障碍收录"（§5.1 S1 子项），
     * 不构成对主屏内容的读取。
     */
    fun visibleDisplayIds(): List<Int> {
        val displays = windowsOnAllDisplays
        return (0 until displays.size()).map { displays.keyAt(it) }
    }

    /**
     * 功能：读取主屏前台应用的包名与 Activity
     * 参数：无
     * 返回：主屏最上层应用窗口；没有可读的应用窗口时字段为 null
     *
     * 只取包名、不读根节点的任何文本或子节点：唯一用途是判断"用户是否在主屏打开了同一个 App"
     * （§4.6 第 2 条的共享状态冲突），不构成对主屏内容的读取。
     *
     * 不能只认 isFocused：任务执行期间全局焦点在**副屏**上，主屏的应用窗口一律
     * focused=false，只认焦点会让这个守卫在任务正常执行时永远读不到东西（真机实测：
     * 主屏明明是 com.android.settings，这里返回 null）。因此退化为按 window layer 取
     * 最上层的应用窗口；不能直接取列表第一个——列表顺序里可能混着已不在前台的残留窗口。
     */
    fun mainScreenForeground(): MainScreenForeground {
        val windows = windowsOnAllDisplays[DISPLAY_MAIN].orEmpty()
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // 先认聚焦，再按 layer 取最上层；直接取 firstOrNull 会被列表顺序坑到：
        // 主屏上残留的非前台设置窗口可能排在前面，把"主屏正在用目标 App"误判成真。
        val top = apps.filter { it.isFocused }.maxByOrNull { it.layer }
            ?: apps.maxByOrNull { it.layer }
        val pkg = top?.root?.packageName?.toString()
        val activity = top?.title?.toString()
        Log.d(
            TAG,
            "主屏窗口 windows=${windows.size} apps=${apps.size} focused=${top?.isFocused} " +
                "layer=${top?.layer} pkg=$pkg activity=$activity",
        )
        return MainScreenForeground(pkg, activity)
    }

    /**
     * 功能：把文本写入指定显示上的输入框
     * 参数：displayId 目标显示；text 待写入的 Unicode 文本；prefer 目标输入框的原生像素范围（可空）
     * 返回：写入被系统接受为 true；该屏没有可写的可编辑节点时为 false
     *
     * 走无障碍 ACTION_SET_TEXT 而不是切换输入法：`ime set` 改的是**全局**默认输入法，
     * 任务执行期间主屏会失去键盘，这正是 P5 一票否决的原因；而 ACTION_SET_TEXT 只作用于
     * 目标节点，不改输入法、不抢主屏焦点（§4.7 的替代路径）。
     *
     * 节点选择按可信度排序，不能随手取第一个——同一页面可能同时有多个输入框：
     * 1. 已聚焦的可编辑节点（用户或模型刚把光标放进去，最可信）；
     * 2. 与 prefer 范围重叠最多的可编辑节点（目标指纹来自元素表，能唯一定位搜索框）；
     * 3. 整屏只有一个可编辑节点时用它（页面刚打开、输入框还没拿到焦点也能写入）。
     *
     * 真机 B 站搜索页实测：输入框是标准 EditText（`tv.danmaku.bili:id/search_src_text`），
     * 但页面切换动画期间 `isFocused` 与节点可读性都会短暂缺失，只试一次会把"正在加载"
     * 误判成"拒绝写入"。重试由调用方控制，这里只保证单次选择尽量准。
     */
    fun setTextOnDisplay(displayId: Int, text: String, prefer: Bounds? = null): Boolean {
        val apps = windowsOnAllDisplays[displayId].orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        var editableCount = 0

        for (window in apps) {
            val root = window.root ?: continue
            val nodes = collectEditables(root)
            editableCount += nodes.size
            val target = pickEditable(nodes, prefer) ?: continue
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.d(TAG, "SET_TEXT 窗口=${window.id} 可编辑=${nodes.size} 目标=${describe(target)} 接受=$accepted")
            if (accepted) return true
        }

        Log.d(TAG, "SET_TEXT 未写入：窗口=${apps.size} 可编辑=$editableCount 偏好框=${prefer?.let(::describe) ?: "无"}")
        return false
    }

    /**
     * 功能：在指定显示上把剪贴板内容粘贴进输入框
     * 参数：displayId 目标显示；prefer 目标输入框的原生像素范围（可空）
     * 返回：系统接受粘贴为 true；没有可编辑节点时为 false
     *
     * 与 ACTION_SET_TEXT 的区别：SET_TEXT 是"覆盖写"，部分自绘输入框会拒绝；
     * ACTION_PASTE 走的是输入框自己的粘贴实现，只依赖剪贴板内容，两条路互补。
     * 有控件树时优先用它，没有（微信这类）才需要注入粘贴键。
     */
    fun pasteOnDisplay(displayId: Int, prefer: Bounds? = null): Boolean {
        val apps = windowsOnAllDisplays[displayId].orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }

        for (window in apps) {
            val root = window.root ?: continue
            val target = pickEditable(collectEditables(root), prefer) ?: continue
            val accepted = target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.d(TAG, "PASTE 窗口=${window.id} 目标=${describe(target)} 接受=$accepted")
            if (accepted) return true
        }
        return false
    }

    /**
     * 功能：收集子树里的全部可编辑节点
     * 参数：root 子树根节点
     * 返回：可编辑节点列表；超过扫描上限时提前结束
     */
    private fun collectEditables(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo) {
            visited++
            if (visited > NODE_SCAN_LIMIT || found.size >= EDITABLE_SCAN_LIMIT) return
            if (node.isEditable) found += node
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(::visit)
            }
        }

        visit(root)
        return found
    }

    /**
     * 功能：从候选输入框里挑一个最可信的
     * 参数：nodes 可编辑节点列表；prefer 目标输入框的原生像素范围（可空）
     * 返回：选中的节点；无法确定时为 null（宁可不写，也不写错格子）
     */
    private fun pickEditable(nodes: List<AccessibilityNodeInfo>, prefer: Bounds?): AccessibilityNodeInfo? {
        nodes.firstOrNull { it.isFocused }?.let { return it }
        if (prefer != null) {
            val best = nodes.maxByOrNull { overlapArea(it, prefer) }
            if (best != null && overlapArea(best, prefer) > 0) return best
        }
        return nodes.singleOrNull()
    }

    /**
     * 功能：算节点范围与目标范围的重叠面积
     * 参数：node 候选节点；prefer 目标范围
     * 返回：重叠面积（平方像素）；不相交为 0
     */
    private fun overlapArea(node: AccessibilityNodeInfo, prefer: Bounds): Int {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val width = minOf(rect.right, prefer.right) - maxOf(rect.left, prefer.left)
        val height = minOf(rect.bottom, prefer.bottom) - maxOf(rect.top, prefer.top)
        return if (width > 0 && height > 0) width * height else 0
    }

    /**
     * 功能：把节点压成一行日志
     * 参数：node 无障碍节点
     * 返回：类名、范围与文本的描述
     */
    private fun describe(node: AccessibilityNodeInfo): String {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString().orEmpty().take(LOG_TEXT_LIMIT)
        return "${node.className}[${rect.left},${rect.top},${rect.right},${rect.bottom}] text=$text"
    }

    /**
     * 功能：把像素范围压成一行日志
     * 参数：bounds 原生像素范围
     * 返回：范围描述
     */
    private fun describe(bounds: Bounds): String =
        "[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]"

    /**
     * 功能：在指定副屏上按文本点击一个可点击节点
     * 参数：displayId 目标显示；label 目标节点子树里应包含的文本
     * 返回：系统接受点击为 true；找不到匹配节点或节点拒绝点击为 false
     *
     * 复验路线专用：坐标注入在页面切换时可能把 up 事件落到新页面的同坐标控件上
     * （实测点搜索结果会把结果页的「设备名称」行一起点开），因此这里走无障碍 ACTION_CLICK，
     * 只作用于当前页面上的节点，不会穿透到正在打开的新页面。
     * 优先选子树里包含目标文本、且不是输入框的最低层可点击节点，避免误点搜索框本身。
     */
    fun clickNodeOnDisplay(displayId: Int, label: String): Boolean {
        val needle = normalize(label)
        if (needle.isEmpty()) return false
        val apps = windowsOnAllDisplays[displayId].orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        for (window in apps) {
            val root = window.root ?: continue
            val target = findClickableByLabel(root, needle) ?: continue
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /**
     * 功能：在子树里找最低层、可点击且子树文本包含目标串的节点
     * 参数：root 子树根；needle 已归一化的目标文本
     * 返回：匹配节点；找不到为 null
     *
     * 后序遍历：先看子节点，再看自己。这样选中的是包含目标文本的最低层可点击容器，
     * 而不是它上面的大列表。输入框（可编辑）跳过，避免把「点结果」变成「点搜索框」。
     */
    private fun findClickableByLabel(root: AccessibilityNodeInfo, needle: String): AccessibilityNodeInfo? {
        var visited = 0
        var found: AccessibilityNodeInfo? = null

        fun visit(node: AccessibilityNodeInfo): String {
            visited++
            if (visited > NODE_SCAN_LIMIT || found != null) return ""
            val own = node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty()
            val subtree = StringBuilder(own)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                subtree.append(' ').append(visit(child))
            }
            val text = subtree.toString()
            if (found == null && node.isClickable && !node.isEditable &&
                normalize(text).contains(needle)
            ) {
                found = node
            }
            return text
        }

        visit(root)
        return found
    }

    /**
     * 功能：把文本归一化成可比较的形式
     * 参数：text 原始文本
     * 返回：去掉全部空白并转为小写的文本
     */
    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() }.lowercase()

    /**
     * 功能：服务销毁时清理实例引用
     * 参数：无
     * 返回：无
     */
    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        /** 诊断日志标签。 */
        const val TAG = "MimirA11y"

        /** 主屏的显示编号；Android 上默认显示恒为 0。 */
        const val DISPLAY_MAIN = 0

        /** 单次按文本查找节点时最多遍历的节点数，防止异常页面把主线程拖住。 */
        const val NODE_SCAN_LIMIT = 400

        /** 单次写入文本时最多收集的候选输入框数量。 */
        const val EDITABLE_SCAN_LIMIT = 8

        /** 日志里回显节点文本的长度上限。 */
        const val LOG_TEXT_LIMIT = 24

        /** 当前连接的实例；未连接时为 null。 */
        @Volatile
        var instance: MimirAccessibilityService? = null
            private set
    }
}

/**
 * 主屏最上层应用窗口。
 *
 * @property packageName 包名；窗口根节点不可读时为 null
 * @property activityName 窗口标题（通常是 Activity 名）；系统窗口可能为 null
 */
data class MainScreenForeground(
    val packageName: String?,
    val activityName: String?,
)
