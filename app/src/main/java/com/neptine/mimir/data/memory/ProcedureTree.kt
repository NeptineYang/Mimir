package com.neptine.mimir.data.memory

import com.neptine.mimir.core.contract.ProcedureAction
import com.neptine.mimir.core.contract.ProcedurePage
import com.neptine.mimir.core.contract.ProcedureStep
import com.neptine.mimir.data.db.entity.ProcedureActionEntity
import com.neptine.mimir.data.db.entity.ProcedurePageEntity
import java.util.UUID

/**
 * 配方树的组装与分组（设计文档 §5.6）。
 *
 * 只做"结构"这一件事：把两张节点表拼回契约对象，或把扁平步骤按页面切段。
 * 锚点与参数槽位的 JSON 编解码仍归 [ProcedureCodec]，避免出现第二套格式。
 */
object ProcedureTree {

    /**
     * 功能：把页面与动作行组装成配方树
     * 参数：pages 页面行（已按 sort_order 排序）；actions 动作行（已按 sort_order 排序）；
     *       anchorOf 锚点 JSON 解析器；slotsOf 参数槽位 JSON 解析器
     * 返回：按页面顺序排列的树；动作按 page_id 归位
     */
    fun assemble(
        pages: List<ProcedurePageEntity>,
        actions: List<ProcedureActionEntity>,
        anchorOf: (String?) -> com.neptine.mimir.core.contract.Anchor?,
        slotsOf: (String) -> Map<String, String>,
    ): List<ProcedurePage> {
        val actionsByPage = actions.groupBy { it.pageId }
        return pages.map { page ->
            ProcedurePage(
                id = page.id,
                title = page.title,
                pagePath = page.pagePath,
                signature = ProcedureCodec.decodeStringsForTree(page.signatureJson),
                actions = actionsByPage[page.id].orEmpty().map { action ->
                    ProcedureAction(
                        id = action.id,
                        actionKind = ProcedureCodec.actionKindOf(action.actionKind),
                        title = action.title,
                        anchor = anchorOf(action.anchorJson),
                        parameterSlots = slotsOf(action.slotsJson),
                    )
                },
            )
        }
    }

    /**
     * 功能：把扁平步骤按页面切段
     * 参数：steps 已确认的步骤；actions 与 steps 一一对应的动作节点（可空时按步骤顺序生成）
     * 返回：页面列表；pagePath 连续相同的步骤归入同一页
     *
     * 这是迁移旧配方时的分组规则：旧数据只有 pagePath，没有观察哈希，
     * 因此只能按"连续相同页面"切分；新写入走 [groupByPageBreaks]。
     */
    fun groupSteps(steps: List<ProcedureStep>): List<ProcedurePage> {
        if (steps.isEmpty()) return emptyList()
        val pages = mutableListOf<ProcedurePage>()
        var currentPath: String? = null
        var current = mutableListOf<ProcedureAction>()

        fun flush() {
            if (current.isEmpty()) return
            pages += ProcedurePage(
                id = UUID.randomUUID().toString(),
                title = pageTitleOf(currentPath, pages.size),
                pagePath = currentPath,
                actions = current.toList(),
            )
            current = mutableListOf()
        }

        steps.forEach { step ->
            if (current.isNotEmpty() && step.pagePath != currentPath) flush()
            currentPath = step.pagePath
            current += ProcedureAction(
                id = UUID.randomUUID().toString(),
                actionKind = step.actionKind,
                title = actionTitleOf(step),
                anchor = step.anchor,
                parameterSlots = step.parameterSlots,
            )
        }
        flush()
        return pages
    }

    /**
     * 功能：按页面边界切分动作
     * 参数：drafts 带页面路径与观察哈希的草稿动作
     * 返回：页面列表
     *
     * 两个切分信号：
     * 1. pagePath 变了——Activity 换了，必然是新页面；
     * 2. 同一 Activity 内观察哈希变了，且当前页已累计至少 2 个动作——Compose 单 Activity
     *    应用的多个页面 Activity 名相同，只能靠"页面内容已经变了"来判断。
     *    阈值取 2 是防止一次页面微调（时间、角标）把单动作拆成两页。
     */
    fun groupByPageBreaks(drafts: List<PageDraftAction>): List<ProcedurePage> {
        if (drafts.isEmpty()) return emptyList()
        val pages = mutableListOf<ProcedurePage>()
        var currentPath: String? = null
        var currentHash = ""
        var current = mutableListOf<ProcedureAction>()

        fun flush() {
            if (current.isEmpty()) return
            pages += ProcedurePage(
                id = UUID.randomUUID().toString(),
                title = pageTitleOf(currentPath, pages.size),
                pagePath = currentPath,
                signature = emptyList(),
                actions = current.toList(),
            )
            current = mutableListOf()
        }

        drafts.forEach { draft ->
            val pathChanged = current.isNotEmpty() && draft.pagePath != currentPath
            val hashChanged = current.isNotEmpty() &&
                draft.observationHash != currentHash &&
                current.size >= MIN_ACTIONS_BEFORE_SPLIT
            if (pathChanged || hashChanged) flush()
            currentPath = draft.pagePath
            currentHash = draft.observationHash
            current += draft.action
        }
        flush()
        return pages
    }

    /**
     * 功能：生成页面标题
     * 参数：path 页面路径（通常是 Activity 全名）；index 页面序号（从 0 开始）
     * 返回：用户可读的页面名；路径为空时用序号
     */
    fun pageTitleOf(path: String?, index: Int): String {
        val simple = path?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
            ?: return "第 ${index + 1} 页"
        return simple.removeSuffix("Activity").ifBlank { "第 ${index + 1} 页" }
    }

    /**
     * 功能：生成动作标题
     * 参数：step 步骤
     * 返回：锚点文本；没有文本时退回动作类型
     */
    private fun actionTitleOf(step: ProcedureStep): String =
        step.anchor?.text?.takeIf { it.isNotBlank() }
            ?: step.anchor?.resourceId?.takeIf { it.isNotBlank() }
            ?: step.actionKind.name

    /** 同一 Activity 内至少累计多少个动作后，才允许按观察哈希切分新页面。 */
    private const val MIN_ACTIONS_BEFORE_SPLIT = 2
}

/**
 * 写入期的动作草稿：动作本身 + 用于切分页面的两个信号。
 *
 * @property action 动作节点
 * @property pagePath 当时的 Activity 名
 * @property observationHash 当时一致观察的结构哈希
 */
data class PageDraftAction(
    val action: ProcedureAction,
    val pagePath: String?,
    val observationHash: String,
)
