package com.neptine.mimir.agent

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.data.db.dao.ConfigDao
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.service.MimirAccessibilityService
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * 文本输入的路径选择与执行（设计文档 §2.2 / §4.7）。
 *
 * 四条路，按可靠性排序，前一条失败才走后一条：
 *
 * 1. **无障碍 ACTION_SET_TEXT（首选）**：只改目标输入框的内容，不切换输入法、不抢主屏焦点。
 *    设计文档原方案是"自带 IME + commitText"，但 `ime set` 改的是**全局**默认输入法——
 *    任务期间用户主屏会失去键盘，这正是 P5 一票否决要防的事。ACTION_SET_TEXT 从机制上
 *    就不会碰主屏，因此不需要过 P5。
 * 2. **剪贴板 + 粘贴键**：SET_TEXT 被自绘输入框拒绝，而中文又没有 KeyCharacterMap 映射时
 *    的唯一通路。写剪贴板走 shell uid，不切换输入法，同样不碰 P5。
 * 3. **逐字符按键注入**：控件树被整体封锁（微信）时没有可写节点，只能靠按键。
 *    只对 ASCII 有效——中文一个字符都映射不出来。
 * 4. **自带 IME + commitText（回退）**：会切全局输入法，仍然受 P5 硬门约束。
 *
 * 四条都不通时返回 false，由 Guard 拦成人工接管，而不是静默失败。
 */
class TextInput @Inject constructor(
    private val transport: DeviceTransport,
    private val configDao: ConfigDao,
) {

    /**
     * 功能：判断当前可用的文本输入路径
     * 参数：无
     * 返回：无障碍优先，其次是通过 P5 验证的输入法，都没有则为 NONE
     */
    suspend fun availability(): TextPath {
        if (MimirAccessibilityService.instance != null) return TextPath.ACCESSIBILITY
        return if (p5Passed()) TextPath.IME else TextPath.NONE
    }

    /**
     * 功能：向副屏的目标输入框写入文本
     * 参数：handle 建屏句柄；action 输入动作（含目标指纹与正文）；
     *       visionOnly 当前观察是否只有截图（控件树不可读）
     * 返回：写入是否被接受
     */
    suspend fun type(
        handle: DisplayHandle,
        action: AgentAction.Do,
        visionOnly: Boolean = false,
    ): Boolean {
        if (action.kind != ActionKind.TEXT) return false
        val text = action.text.orEmpty()
        if (text.isEmpty()) return false

        if (typeByAccessibility(handle, action, text)) return logged(text, "SET_TEXT")
        // 坐标目标（模型只给了 point）在"有控件树"的 App 里也能 SET_TEXT：
        // 先按坐标点一下把输入框聚焦，再用无障碍直接写文本。比逐字符注键可靠得多——
        // 按键链路会丢 SHIFT，大写与 ? 之类的符号会退化（DeepSeek 实测 "France?" → "france/"）。
        if (typeByAccessibilityAfterPoint(handle, action, text)) return logged(text, "SET_TEXT(坐标)")
        // 任务期输入法已经由 drive 接管成 Mimir 的无键盘 IME：此时 commitText 走
        // InputConnection，是自绘输入框最认的一条通路，也不会切全局输入法。
        // 必须排在粘贴之前：自绘输入框没有可编辑节点时，"粘贴键被系统接受"只代表
        // 按键发出去了，不代表文本落地（DeepSeek/美团实测页面完全没变，
        // 却按成功继续，模型只好反复重输）。先走 IME，粘贴只留作回退。
        if (transport.ime.isActive() && typeByIme(handle, action, text)) {
            return logged(text, if (visionOnly) "输入法(纯视觉)" else "输入法")
        }
        // 粘贴通路：SET_TEXT 被自绘输入框拒绝、按键注入又不支持中文时，
        // 写剪贴板 + 注入 KEYCODE_PASTE 是不切输入法的最后通路。
        if (typeByPaste(handle, action, text)) return logged(text, "粘贴")
        // 控件树被封锁（微信这类）时没有可写的节点，ACTION_SET_TEXT 必然失败：
        // 退到逐字符按键注入。它不碰输入法，只受"字符能否用按键表达"限制。
        if (typeByKeyEvents(handle, action, text)) return logged(text, "按键")
        if (!visionOnly && !transport.ime.isActive() && typeByIme(handle, action, text)) {
            return logged(text, "输入法")
        }
        logged(text, "无（全部路径失败）")
        return false
    }

    /**
     * 功能：记录实际生效的输入路径
     * 参数：text 正文；path 路径名
     * 返回：恒为 true，便于在调用处直接 return
     *
     * 真机上"系统接受=否"看不出是哪条路走到哪一步失败的，这一行是唯一的判据来源。
     */
    private fun logged(text: String, path: String): Boolean {
        android.util.Log.d("MimirText", "输入路径=$path 字符=${text.length}")
        return true
    }

    /**
     * 功能：坐标目标先聚焦、再走无障碍 SET_TEXT
     * 参数：handle 建屏句柄；action 输入动作；text 正文
     * 返回：是否写入成功；没有控件树（纯视觉）时返回 false，交给按键注入
     */
    private suspend fun typeByAccessibilityAfterPoint(
        handle: DisplayHandle,
        action: AgentAction.Do,
        text: String,
    ): Boolean {
        val service = MimirAccessibilityService.instance ?: return false
        if (action.targetFingerprint?.bounds != null) return false
        val focusPoint = pointOf(handle, action) ?: return false
        transport.inputs.tap(handle, focusPoint, FOCUS_PRESS_MS)
        // 点完到输入框真正拿到焦点之间有一段窗口期（搜索页常带转场动画），
        // 一次不成就判死会把"正在聚焦"当成"不支持写入"。
        repeat(SET_TEXT_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) FOCUS_SETTLE_MS else RETRY_GAP_MS)
            if (service.setTextOnDisplay(handle.displayId, text, null)) return true
        }
        return false
    }

    /**
     * 功能：写剪贴板后触发粘贴
     * 参数：handle 建屏句柄；action 输入动作；text 正文
     * 返回：剪贴板与粘贴请求都被系统接受为 true（是否真粘贴由后置条件核对）
     *
     * 两条粘贴方式：有控件树时用无障碍 ACTION_PASTE（直接作用在输入框上）；
     * 控件树被封锁（微信）时退回"点一下 + 注入粘贴键"。
     */
    private suspend fun typeByPaste(
        handle: DisplayHandle,
        action: AgentAction.Do,
        text: String,
    ): Boolean {
        if (!transport.inputs.setClipboard(text)) {
            android.util.Log.d("MimirText", "粘贴跳过：写剪贴板被拒绝")
            return false
        }
        val bounds = action.targetFingerprint?.bounds
        val service = MimirAccessibilityService.instance
        if (service != null && service.pasteOnDisplay(handle.displayId, bounds)) {
            android.util.Log.d("MimirText", "粘贴注入：无障碍 ACTION_PASTE 字符=${text.length}")
            delay(TYPE_SETTLE_MS)
            return true
        }
        val focusPoint = bounds?.center ?: pointOf(handle, action) ?: return false
        transport.inputs.tap(handle, focusPoint, FOCUS_PRESS_MS)
        delay(FOCUS_SETTLE_MS)
        val accepted = transport.inputs.keyWithMeta(handle, android.view.KeyEvent.KEYCODE_PASTE, 0)
        android.util.Log.d("MimirText", "粘贴注入：粘贴键 字符=${text.length} 接受=$accepted 焦点=$focusPoint")
        if (accepted) delay(TYPE_SETTLE_MS)
        return accepted
    }

    /**
     * 功能：把动作里的归一化坐标点换算成副屏原生坐标
     * 参数：handle 建屏句柄；action 动作
     * 返回：坐标点；动作没有坐标时为 null
     */
    private fun pointOf(handle: DisplayHandle, action: AgentAction.Do): Point? {
        val nx = action.normX ?: return null
        val ny = action.normY ?: return null
        return Point(
            x = (nx * handle.spec.width / NORM_SCALE).coerceIn(0, handle.spec.width - 1),
            y = (ny * handle.spec.height / NORM_SCALE).coerceIn(0, handle.spec.height - 1),
        )
    }

    /**
     * 功能：用按键事件逐字符键入
     * 参数：handle 建屏句柄；action 输入动作（用来先聚焦目标）；text 正文
     * 返回：全部字符都注入成功为 true
     *
     * 只能表达 KeyCharacterMap 认得的字符（ASCII 为主）。中文一个字符都映射不出来，
     * 这里会直接返回 false，交给 IME 回退路径（P5 未通过时即被拦成人工）——不静默丢弃。
     *
     * 为什么先点一下再打字：按键事件只进入当前焦点输入框；模型给的输入动作常常是在
     * 未聚焦的框上，直接注键会打给页面本身（甚至触发快捷键）。
     */
    private suspend fun typeByKeyEvents(
        handle: DisplayHandle,
        action: AgentAction.Do,
        text: String,
    ): Boolean {
        val keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val events = runCatching { keyMap.getEvents(text.toCharArray()) }.getOrNull()
        if (events.isNullOrEmpty()) return false
        // 目标可能是"有编号的控件"，也可能是纯视觉下的一个坐标点（空输入框没有编号）。
        val focusPoint = action.targetFingerprint?.bounds?.center
            ?: pointOf(handle, action)
            ?: return false
        transport.inputs.tap(handle, focusPoint, FOCUS_PRESS_MS)
        delay(FOCUS_SETTLE_MS)
        android.util.Log.d(
            "MimirText",
            "按键注入：字符=${text.length} 事件=${events.size} 焦点=${focusPoint}",
        )
        // 按 getEvents 给的**完整序列**回放（含 SHIFT 的按下/抬起）。
        // 之前只取 ACTION_DOWN 再成对注入，等于丢掉了"按住修饰键"的语义：
        // 真机上大写与需要 Shift 的符号全部退化（"France?" 变成 "france/"）。
        events.forEach { event ->
            val accepted = transport.inputs.keyAction(
                handle, event.keyCode, event.metaState, event.action,
            )
            if (!accepted) return false
            delay(KEY_GAP_MS)
        }
        // 键全部发完后再等一拍：紧接着的那次观察常常在文字渲染前进场，
        // 会把"已经打进去了"判成"结果未知"，白挂一次人工（真机上就是这样）。
        delay(TYPE_SETTLE_MS)
        return true
    }

    /**
     * 功能：走无障碍 ACTION_SET_TEXT 写入
     * 参数：handle 建屏句柄；action 输入动作；text 正文
     * 返回：是否写入成功
     *
     * 模型给的 input 动作常常落在**未聚焦**的输入框上，第一次写入会失败；
     * 这时先点一下目标把它聚焦，再重试。重试要跨过页面切换动画：
     * 真机 B 站搜索页在转场期间 `isFocused` 与节点可读性都会短暂缺失，
     * 只试一次会把"正在加载"误判成"拒绝写入"，进而把可用页面推给人工。
     */
    private suspend fun typeByAccessibility(
        handle: DisplayHandle,
        action: AgentAction.Do,
        text: String,
    ): Boolean {
        val service = MimirAccessibilityService.instance ?: return false
        val bounds = action.targetFingerprint?.bounds

        // 先直接写：页面已经把输入框聚焦时不必再点，少一次点击就少一次误触
        if (service.setTextOnDisplay(handle.displayId, text, bounds)) return true

        if (bounds != null) {
            transport.inputs.tap(handle, bounds.center, FOCUS_PRESS_MS)
        }
        repeat(SET_TEXT_ATTEMPTS) { attempt ->
            delay(if (attempt == 0) FOCUS_SETTLE_MS else RETRY_GAP_MS)
            if (service.setTextOnDisplay(handle.displayId, text, bounds)) return true
        }
        return false
    }

    /**
     * 功能：走自带输入法提交文本
     * 参数：handle 建屏句柄；action 输入动作；text 正文
     * 返回：是否提交成功；既没通过 P5、输入法也不在自己手上时直接 false
     *
     * P5 的硬门针对的是"为了输入去改用户默认输入法"。任务执行期 drive 已经把默认输入法
     * 接管成无键盘的自带 IME，这时 commitText 不会带来新的主屏影响，不需要再过 P5。
     */
    private suspend fun typeByIme(
        handle: DisplayHandle,
        action: AgentAction.Do,
        text: String,
    ): Boolean {
        if (!p5Passed() && !transport.ime.isActive()) return false
        // commitText 只作用于当前输入连接：先把目标点聚焦，否则输入法拿不到连接
        val focusPoint = action.targetFingerprint?.bounds?.center ?: pointOf(handle, action) ?: return false
        transport.inputs.tap(handle, focusPoint, FOCUS_PRESS_MS)
        delay(FOCUS_SETTLE_MS)
        if (!transport.ime.select()) return false
        return runCatching { transport.ime.commitText(text) }.isSuccess
    }

    /**
     * 功能：查询 P5 探针是否通过
     * 参数：无
     * 返回：通过为 true
     */
    private suspend fun p5Passed(): Boolean =
        configDao.findCapability(P5_PROBE_ID)?.passed == true

    private companion object {
        /** 逐字符键入时每个键之间的间隔：太快会被部分输入框合并或丢弃。 */
        const val KEY_GAP_MS = 35L

        /** 全部键发完后的静置时间，保证随后的观察能读到刚输入的文字。 */
        const val TYPE_SETTLE_MS = 300L

        /** P5 探针在 device_capability 表里的编号。 */
        const val P5_PROBE_ID = "P5_ZERO_INTERFERENCE_TEXT"

        /** 聚焦点击的按压时长（毫秒）。 */
        const val FOCUS_PRESS_MS = 60L

        /** 点击后等焦点落到输入框的时间（毫秒）。 */
        const val FOCUS_SETTLE_MS = 350L

        /** SET_TEXT 的额外重试次数：跨过页面转场与输入框聚焦的窗口期。 */
        const val SET_TEXT_ATTEMPTS = 2

        /** 重试之间的间隔（毫秒）。 */
        const val RETRY_GAP_MS = 500L
    }
}

/** 文本输入路径。 */
enum class TextPath {
    /** 无障碍 ACTION_SET_TEXT：不切输入法，不需要 P5。 */
    ACCESSIBILITY,

    /** 自带 IME + commitText：会切全局输入法，必须先过 P5。 */
    IME,

    /** 两条都不可用。 */
    NONE,
}
