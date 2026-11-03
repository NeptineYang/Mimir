package com.neptine.mimir.agent

import android.util.Log
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.DisplayHandle
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TargetMode
import com.neptine.mimir.core.contract.TaskSpec
import com.neptine.mimir.core.contract.UiElement
import com.neptine.mimir.device.DeviceTransport
import com.neptine.mimir.observation.ObservationAssembler
import com.neptine.mimir.observation.TreeFrame
import com.neptine.mimir.observation.TreeProvider
import com.neptine.mimir.service.MimirAccessibilityService
import javax.inject.Inject
import kotlinx.coroutines.delay

/**
 * 独立结果页复验（设计文档 §4.4.1）。
 *
 * 初验通过后，用一条**与任务路径不同的只读导航**重新到达结果页，再取一份全新快照交给
 * Verifier 做第二路字段核对。这样 DONE 才有实际含义：两路证据来自两次独立读取，而不是
 * 把同一张成功页截图看两遍。
 *
 * 两条路线：
 * 1. 「关于手机/关于模拟设备」页：回设置首页 → 搜索页 → 点结果 → 新快照（原有路线）；
 * 2. 显示/声音/应用/通知/存储等设置页：回设置首页 → 由系统 action 在副屏直拉 →
 *    新快照。直达路线与用户路径不同，但仍属于同一 App 的只读重新进入。
 *
 * 其它页面或其它 App 一律返回不可达，由上层落 COMPLETED_UNVERIFIED，
 * 不为了凑 DONE 强行操作。
 */
class ResultReverifier @Inject constructor(
    private val transport: DeviceTransport,
    private val trees: TreeProvider,
    private val assembler: ObservationAssembler,
    private val applier: ActionApplier,
) {

    /**
     * 功能：尝试取第二路证据
     * 参数：spec 任务约束；handle 副屏句柄；primary 初验快照
     * 返回：复验快照与路线说明；不可达时 snapshot 为 null、note 写明卡在哪一步
     */
    suspend fun reach(
        spec: TaskSpec,
        handle: DisplayHandle,
        primary: ObservationSnapshot,
    ): ReachResult {
        if (spec.packageName != SETTINGS_PACKAGE) {
            return ReachResult(null, "没有为 ${spec.packageName} 注册独立复验导航")
        }
        val title = primary.activityName.trim()
        if (title.isEmpty()) return ReachResult(null, "初验页没有可搜索的标题")

        // 先看是否有"系统 action 直达"的设置页路线；这类页面不需要搜索
        settingsDirectRoute(title)?.let { route ->
            return reachDirect(handle, route, title)
        }
        if (!title.contains(ABOUT_TITLE_HINT)) {
            return ReachResult(null, "当前页「$title」不在设置复验路线的覆盖范围内")
        }

        val home = backToSettingsHome(handle)
            ?: return ReachResult(null, "按返回 $MAX_BACKS 次后仍未回到设置首页")
        val searchEntry = findSearchEntry(home)
            ?: return ReachResult(null, "设置首页没有找到搜索入口")
        clickSearchEntry(handle, searchEntry)
        val searchPage = awaitFrame(handle, SEARCH_PAGE_WAIT_MS)
            ?: return ReachResult(null, "打开搜索页后读不到控件树")

        var note = "搜索「$title」后没有点到可复验的结果页"
        for (query in queryCandidates(title)) {
            Log.d(TAG, "复验：输入搜索词「$query」")
            typeQuery(handle, searchPage, query)
            val result = awaitSearchResult(handle, query)
            if (result == null) {
                note = "搜索「$query」后没有出现可点的结果条目"
                Log.d(TAG, note)
                continue
            }
            Log.d(TAG, "复验：找到结果「${searchLabelOf(result)}」${result.bounds}")
            if (!clickResult(handle, result)) {
                note = "找到搜索结果「$query」但无法安全点击（无障碍 ACTION_CLICK 未生效）"
                Log.d(TAG, note)
                continue
            }
            val reached = awaitResultPageOrRelocate(handle)
            val page = reached.frame
            if (page == null) {
                note = "点了搜索结果后没有进入设置页"
                Log.d(TAG, note)
                continue
            }
            Log.d(TAG, "复验：结果页 ${page.packageName}/${page.activityName}")
            if (!page.activityName.contains(ABOUT_TITLE_HINT)) {
                note = "点开的结果页是「${page.activityName}」，不是关于页"
                Log.d(TAG, note)
                continue
            }
            val fresh = awaitSnapshot(handle)
                ?: return ReachResult(null, "已重新进入结果页，但取不到一致观察")
            if (fresh.packageName != SETTINGS_PACKAGE) {
                return ReachResult(null, "复验页落到了 ${fresh.packageName}，不是目标包")
            }
            val via = if (reached.relocated) {
                "搜索结果页逃到主屏，已在副屏直拉关于页并清掉主屏那份"
            } else {
                "结果页 ${fresh.activityName}"
            }
            Log.d(TAG, "独立复验成功：$title → 搜索「$query」→ $via")
            return ReachResult(fresh, "设置搜索路线：$title → 搜索「$query」→ $via")
        }
        return ReachResult(null, note)
    }

    /**
     * 功能：按初验页标题找一条系统 action 直达路线
     * 参数：title 初验页标题
     * 返回：匹配的路线；没有注册时为 null
     */
    private fun settingsDirectRoute(title: String): SettingsDirectRoute? =
        DIRECT_ROUTES.firstOrNull { route -> title.contains(route.titleHint) }

    /**
     * 功能：用系统 action 在副屏重新进入设置页，并取一份全新观察
     * 参数：handle 副屏句柄；route 直达路线；title 初验页标题
     * 返回：复验快照与路线说明；不可达时 snapshot 为 null
     *
     * 先退回设置首页再拉起 action：如果停在原结果页直接再拉一次，读到的可能还是同一份
     * Activity 实例，证据独立性不足。直达失败不判任务失败，只落 COMPLETED_UNVERIFIED。
     */
    private suspend fun reachDirect(
        handle: DisplayHandle,
        route: SettingsDirectRoute,
        title: String,
    ): ReachResult {
        val home = backToSettingsHome(handle)
            ?: return ReachResult(null, "直达复验前未能回到设置首页")
        Log.d(TAG, "复验：设置首页 ${home.packageName}/${home.activityName}，准备直达 ${route.action}")

        val launched = runCatching { transport.activities.launchAction(route.action, handle) }
            .getOrDefault(false)
        if (!launched) return ReachResult(null, "系统 action ${route.action} 未能拉起")
        delay(LAUNCH_SETTLE_MS)

        val page = awaitDirectPage(handle, route)
            ?: return ReachResult(null, "直达 ${route.action} 后没有进入「${route.titleHint}」页")

        // 二级页：落地页只是中转，还要再点一次目标文案
        if (route.followUpLabel != null) {
            val opened = openFollowUp(handle, route)
            if (!opened) {
                return ReachResult(null, "直达「${route.titleHint}」后没能在页面上点到「${route.followUpLabel}」")
            }
        }

        val fresh = awaitSnapshot(handle)
            ?: return ReachResult(null, "已直达「${route.titleHint}」页，但取不到一致观察")
        if (fresh.packageName != SETTINGS_PACKAGE) {
            return ReachResult(null, "直达页落到了 ${fresh.packageName}，不是目标包")
        }
        Log.d(TAG, "独立复验成功（直达）：$title → ${route.action} → ${fresh.activityName}")
        return ReachResult(
            fresh,
            "设置直达路线：$title → ${route.action} → ${fresh.activityName}",
        )
    }

    /**
     * 功能：在直达落地页上再点进二级页
     * 参数：handle 副屏句柄；route 带 followUpLabel 的路线
     * 返回：进入了 followUpTitle 指定的页面为 true
     *
     * 找不到目标文案就先向下滚一屏再找（小米把这些条目放在页面下半部分）；
     * 只走无障碍 ACTION_CLICK，不做坐标兜底——理由同搜索结果点击：
     * 坐标的 up 事件可能在翻页后落到新页面的同位置控件上。
     */
    private suspend fun openFollowUp(handle: DisplayHandle, route: SettingsDirectRoute): Boolean {
        val label = route.followUpLabel ?: return true
        val expectedTitle = route.followUpTitle ?: label
        repeat(FOLLOW_UP_SCROLLS) {
            val frame = awaitFrame(handle, 0) ?: return@repeat
            val target = frame.elements.firstOrNull {
                it.text.contains(label) || it.contentDescription.contains(label)
            }
            if (target != null) {
                val service = MimirAccessibilityService.instance ?: return false
                val clicked = service.clickNodeOnDisplay(handle.displayId, label)
                Log.d(TAG, "复验：二级页点击「$label」accepted=$clicked")
                if (!clicked) return false
                delay(TAP_SETTLE_MS)
                repeat(RESULT_PAGE_POLLS) {
                    val page = awaitFrame(handle, 0)
                    if (page != null && page.activityName.contains(expectedTitle)) return true
                    delay(RESULT_PAGE_POLL_MS)
                }
                // 点进去了但标题对不上：按失败处理，交给上层落 COMPLETED_UNVERIFIED
                return false
            }
            swipeUp(handle)
        }
        return false
    }

    /**
     * 功能：向前翻一屏（内容向上滚动）
     * 参数：handle 副屏句柄
     * 返回：无
     */
    private suspend fun swipeUp(handle: DisplayHandle) {
        val width = handle.spec.width
        val height = handle.spec.height
        val path = listOf(
            Point(width / 2, height * 75 / 100),
            Point(width / 2, height * 65 / 100),
            Point(width / 2, height * 50 / 100),
            Point(width / 2, height * 35 / 100),
        )
        transport.inputs.swipe(handle, path)
        delay(SCROLL_SETTLE_MS)
    }

    /**
     * 功能：轮询等待系统 action 直达的目标页
     * 参数：handle 副屏句柄；route 路线
     * 返回：目标页的树；超时仍不匹配为 null
     */
    private suspend fun awaitDirectPage(handle: DisplayHandle, route: SettingsDirectRoute): TreeFrame? {
        repeat(RESULT_PAGE_POLLS) { attempt ->
            val frame = awaitFrame(handle, 0)
            if (frame != null) {
                Log.d(TAG, "复验：等待直达页 ${frame.packageName}/${frame.activityName}")
                if (frame.packageName == SETTINGS_PACKAGE && route.matches(frame.activityName)) {
                    return frame
                }
            }
            if (attempt < RESULT_PAGE_POLLS - 1) delay(RESULT_PAGE_POLL_MS)
        }
        return null
    }

    /**
     * 功能：从结果页退回设置首页并滚到顶部
     * 参数：handle 副屏句柄
     * 返回：停在设置首页且能看到搜索入口的树；失败为 null
     *
     * 结果页可能是从首页列表底部点进去的，退回后列表仍停在底部，搜索入口在屏幕外，
     * 所以要向上滚几次再判断。按返回退回桌面时，用一次只读拉起回到设置首页。
     */
    private suspend fun backToSettingsHome(handle: DisplayHandle): TreeFrame? {
        repeat(MAX_BACKS) {
            apply(handle, AgentAction.Do(ActionKind.BACK, "", null))
            delay(NAVIGATE_SETTLE_MS)
            var frame = awaitFrame(handle, 0)
            // 任务如果是沿搜索页进入结果页的，按返回会先回到搜索页；继续按返回，不要重新拉起。
            if (frame != null && frame.packageName == SEARCH_PACKAGE) return@repeat
            if (frame != null && frame.packageName != SETTINGS_PACKAGE) {
                runCatching { transport.activities.launch(SETTINGS_PACKAGE, handle) }
                delay(LAUNCH_SETTLE_MS)
                frame = awaitFrame(handle, 0)
            }
            if (frame == null || frame.packageName != SETTINGS_PACKAGE) return@repeat
            if (findSearchEntry(frame) != null) return frame
            scrollToTop(handle)
            val top = awaitFrame(handle, 0) ?: return@repeat
            if (findSearchEntry(top) != null) return top
        }
        return null
    }

    /**
     * 功能：向下滑动列表回到顶部
     * 参数：handle 副屏句柄
     * 返回：无
     */
    private suspend fun scrollToTop(handle: DisplayHandle) {
        repeat(MAX_SCROLLS) {
            val width = handle.spec.width
            val height = handle.spec.height
            val path = listOf(
                Point(width / 2, height * 35 / 100),
                Point(width / 2, height * 50 / 100),
                Point(width / 2, height * 65 / 100),
                Point(width / 2, height * 75 / 100),
            )
            transport.inputs.swipe(handle, path)
            delay(SCROLL_SETTLE_MS)
        }
    }

    /**
     * 功能：在搜索页写查询词
     * 参数：handle 副屏句柄；page 搜索页树；query 查询词
     * 返回：无
     */
    private suspend fun typeQuery(handle: DisplayHandle, page: TreeFrame, query: String) {
        val field = page.elements.firstOrNull { it.editable }
        val action = AgentAction.Do(
            kind = ActionKind.TEXT,
            observationId = "",
            targetFingerprint = field?.let(::fingerprintOf),
            elementId = field?.index,
            text = query,
        )
        apply(handle, action)
        delay(SEARCH_RESULT_WAIT_MS)
    }

    /**
     * 功能：轮询搜索结果，找到可点击的目标条目
     * 参数：handle 副屏句柄；query 查询词
     * 返回：结果条目；超时或没有匹配为 null
     */
    private suspend fun awaitSearchResult(handle: DisplayHandle, query: String): UiElement? {
        repeat(RESULT_POLLS) { attempt ->
            val frame = awaitFrame(handle, 0)
            if (frame != null) {
                Log.d(TAG, "复验：搜索页 ${frame.packageName}/${frame.activityName} 元素=${frame.elements.size}")
                findResult(frame, query)?.let { return it }
            }
            if (attempt < RESULT_POLLS - 1) delay(RESULT_POLL_MS)
        }
        return null
    }

    /**
     * 功能：等待点开的结果页；没留在副屏时改在副屏直拉关于页
     * 参数：handle 副屏句柄
     * 返回：副屏上的关于页；直拉也失败为 null
     *
     * 设置搜索页在 com.google.android.settings.intelligence 里，点结果时 App 自己启动
     * settings 页面但不带 display，实测会落到主屏。这里先照常等；等不到就按系统 action
     * 在副屏直拉关于页，并清掉主屏那份——复验必须仍发生在副屏，且不能把用户屏幕占住。
     */
    private suspend fun awaitResultPageOrRelocate(handle: DisplayHandle): PageReach {
        awaitResultPage(handle)?.let { return PageReach(it, relocated = false) }

        // 搜索结果如果没留在副屏，先清掉可能落到主屏的那份；直拉失败也不能把用户屏幕占着。
        runCatching { transport.activities.removeTasksOutside(SETTINGS_PACKAGE, handle) }

        val launched = runCatching { transport.activities.launchAction(DEVICE_INFO_ACTION, handle) }
            .getOrDefault(false)
        Log.d(TAG, "复验：搜索结果未留在副屏，直拉关于页 launched=$launched")
        if (!launched) return PageReach(null, relocated = false)

        return PageReach(awaitResultPage(handle), relocated = true)
    }

    /**
     * 功能：轮询等待点开的结果页
     * 参数：handle 副屏句柄
     * 返回：已进入关于页的树；超时仍不是目标页为 null
     *
     * 页面切换动画期间会先读到搜索页或空树，只等固定一段再读一次容易把成功当成失败，
     * 所以要轮询到包名回到设置且标题是目标页为止。
     */
    private suspend fun awaitResultPage(handle: DisplayHandle): TreeFrame? {
        delay(RESULT_PAGE_WAIT_MS)
        repeat(RESULT_PAGE_POLLS) { attempt ->
            val frame = awaitFrame(handle, 0)
            if (frame != null) {
                Log.d(TAG, "复验：等待结果页 ${frame.packageName}/${frame.activityName}")
                if (frame.packageName == SETTINGS_PACKAGE && frame.activityName.contains(ABOUT_TITLE_HINT)) {
                    return frame
                }
            }
            if (attempt < RESULT_PAGE_POLLS - 1) delay(RESULT_PAGE_POLL_MS)
        }
        return null
    }

    /**
     * 功能：取一份全新的一致观察
     * 参数：handle 副屏句柄
     * 返回：新快照；多次重采仍失败为 null
     */
    private suspend fun awaitSnapshot(handle: DisplayHandle): ObservationSnapshot? {
        repeat(SNAPSHOT_ATTEMPTS) {
            runCatching { assembler.snapshot(handle) }
                .onSuccess { return it }
            delay(SNAPSHOT_RETRY_MS)
        }
        return null
    }

    /**
     * 功能：读取一帧控件树
     * 参数：handle 副屏句柄；delayMs 先等多久
     * 返回：控件树；重试用尽仍读不到为 null
     */
    private suspend fun awaitFrame(handle: DisplayHandle, delayMs: Long): TreeFrame? {
        if (delayMs > 0) delay(delayMs)
        repeat(FRAME_ATTEMPTS) {
            runCatching { trees.capture(handle) }
                .onSuccess { return it }
            delay(FRAME_RETRY_MS)
        }
        return null
    }

    /**
     * 功能：执行一个本地导航动作
     * 参数：handle 副屏句柄；action 动作
     * 返回：无
     */
    private suspend fun apply(handle: DisplayHandle, action: AgentAction.Do) {
        runCatching { applier.apply(handle, action) }
    }

    /**
     * 功能：点击设置首页的搜索入口
     * 参数：handle 副屏句柄；element 搜索入口元素
     * 返回：无
     *
     * 优先走无障碍 ACTION_CLICK；节点不可点时才退回坐标注入。入口本身是纯导航，
     * 坐标注入的副作用风险只在页面切换后才会出现，这里可以接受。
     */
    private suspend fun clickSearchEntry(handle: DisplayHandle, element: UiElement) {
        val service = MimirAccessibilityService.instance
        if (service != null && service.clickNodeOnDisplay(handle.displayId, searchLabelOf(element))) {
            delay(TAP_SETTLE_MS)
            return
        }
        tap(handle, element)
    }

    /**
     * 功能：点击搜索结果条目
     * 参数：handle 副屏句柄；element 结果条目元素
     * 返回：无障碍接受点击为 true
     *
     * 只走无障碍 ACTION_CLICK，不做坐标兜底：坐标注入的 up 事件可能在页面切换后落到
     * 新页面的同坐标控件上（实测会把关于页的「设备名称」行点开），那就不是只读复验了。
     */
    private suspend fun clickResult(handle: DisplayHandle, element: UiElement): Boolean {
        val service = MimirAccessibilityService.instance ?: return false
        val clicked = service.clickNodeOnDisplay(handle.displayId, searchLabelOf(element))
        Log.d(TAG, "复验：无障碍点击结果 accepted=$clicked")
        if (clicked) delay(TAP_SETTLE_MS)
        return clicked
    }

    /**
     * 功能：点击树里的一个元素
     * 参数：handle 副屏句柄；element 目标元素
     * 返回：无
     */
    private suspend fun tap(handle: DisplayHandle, element: UiElement) {
        apply(
            handle,
            AgentAction.Do(
                kind = ActionKind.TAP,
                observationId = "",
                targetFingerprint = fingerprintOf(element),
                elementId = element.index,
            ),
        )
        delay(TAP_SETTLE_MS)
    }

    /**
     * 功能：在设置首页找搜索入口
     * 参数：frame 当前树
     * 返回：可点击的搜索入口；没有为 null
     */
    private fun findSearchEntry(frame: TreeFrame): UiElement? =
        frame.elements.firstOrNull { element ->
            element.clickable && !element.editable && searchLabelOf(element).let { label ->
                label.contains(SEARCH_ENTRY_LABEL) || element.resourceId?.contains(SEARCH_RESOURCE_HINT, true) == true
            }
        }

    /**
     * 功能：在搜索结果页找目标条目
     * 参数：frame 当前树；query 查询词
     * 返回：文本命中的可点击条目；没有为 null
     */
    private fun findResult(frame: TreeFrame, query: String): UiElement? {
        val needle = normalize(query)
        return frame.elements.firstOrNull { element ->
            element.clickable && !element.editable &&
                normalize(searchLabelOf(element)).contains(needle)
        }
    }

    /**
     * 功能：取元素的全部可见文本
     * 参数：element 元素
     * 返回：文本；文本为空时用无障碍描述
     */
    private fun searchLabelOf(element: UiElement): String =
        element.text.ifEmpty { element.contentDescription }

    /**
     * 功能：由元素构造目标指纹
     * 参数：element 目标元素
     * 返回：树模式指纹，锚点级别按可用信息取 A1/A2/A3
     */
    private fun fingerprintOf(element: UiElement): TargetFingerprint {
        val level = when {
            !element.resourceId.isNullOrEmpty() -> AnchorLevel.A1
            element.text.isNotEmpty() || element.contentDescription.isNotEmpty() -> AnchorLevel.A2
            else -> AnchorLevel.A3
        }
        return TargetFingerprint(
            mode = TargetMode.TREE,
            anchorLevel = level,
            resourceId = element.resourceId,
            text = element.text.ifEmpty { null },
            contentDescription = element.contentDescription.ifEmpty { null },
            className = element.className.ifEmpty { null },
            ancestorPath = element.ancestorPath,
            bounds = element.bounds,
        )
    }

    /**
     * 功能：生成搜索词候选
     * 参数：title 初验页标题
     * 返回：先完整标题，再退到「关于」这类更宽的词
     */
    private fun queryCandidates(title: String): List<String> =
        listOf(title, ABOUT_TITLE_HINT).distinct().filter { it.isNotEmpty() }

    /**
     * 功能：把文本归一化成可比较的形式
     * 参数：text 原始文本
     * 返回：去掉全部空白并转为小写的文本
     */
    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() }.lowercase()

    private companion object {
        /** 日志标签，与 Orchestrator 保持一致。 */
        const val TAG = "MimirAgent"

        /** 当前唯一注册复验路线的目标包。 */
        const val SETTINGS_PACKAGE = "com.android.settings"

        /**
         * 系统 action 直达的设置页路线。
         *
         * 只登记标题稳定、action 明确且不需要二次选择的页面；搜索结果/深层子页不在其中。
         */
        val DIRECT_ROUTES = listOf(
            // 小米/HyperOS：「全部参数与信息」是二级页，先直达「我的设备」再点这一行。
            // 二级页与一级页共用 .SubSettings，只能靠标题区分，所以匹配全部按标题做。
            SettingsDirectRoute(
                titleHint = "全部参数与信息",
                action = DEVICE_INFO_ACTION,
                extraHints = listOf("我的设备", "MyDeviceInfoActivity"),
                followUpLabel = "全部参数与信息",
                followUpTitle = "全部参数与信息",
            ),
            SettingsDirectRoute(
                titleHint = "我的设备",
                action = DEVICE_INFO_ACTION,
                extraHints = listOf("MyDeviceInfoActivity"),
            ),
            SettingsDirectRoute("显示", "android.settings.DISPLAY_SETTINGS", listOf("显示")),
            SettingsDirectRoute("声音", "android.settings.SOUND_SETTINGS", listOf("声音")),
            SettingsDirectRoute("应用", "android.settings.APPLICATION_SETTINGS", listOf("应用")),
            SettingsDirectRoute("通知", "android.settings.NOTIFICATION_SETTINGS", listOf("通知")),
            SettingsDirectRoute("存储", "android.settings.INTERNAL_STORAGE_SETTINGS", listOf("存储")),
            SettingsDirectRoute("无障碍", "android.settings.ACCESSIBILITY_SETTINGS", listOf("无障碍")),
        )

        /** 设置搜索页所在的包；按返回时会先路过它。 */
        const val SEARCH_PACKAGE = "com.google.android.settings.intelligence"

        /** 只复验「关于手机 / 关于模拟设备」这类页面。 */
        const val ABOUT_TITLE_HINT = "关于"

        /** 系统里打开「关于手机」页的 action；用于搜索结果落到主屏后的副屏直拉。 */
        const val DEVICE_INFO_ACTION = "android.settings.DEVICE_INFO_SETTINGS"

        /** 设置首页搜索入口的文案（中文系统）。 */
        const val SEARCH_ENTRY_LABEL = "搜索"

        /** 搜索入口的 viewId 兜底关键字。 */
        const val SEARCH_RESOURCE_HINT = "search"

        /** 从结果页退回首页的最大按返回次数。 */
        const val MAX_BACKS = 3

        /** 回到首页后向上滚动的最大次数。 */
        const val MAX_SCROLLS = 3

        /** 二级页找目标文案时最多向下翻几屏。 */
        const val FOLLOW_UP_SCROLLS = 3

        /** 普通导航后的等待时间。 */
        const val NAVIGATE_SETTLE_MS = 600L

        /** 点击后的等待时间。 */
        const val TAP_SETTLE_MS = 700L

        /** 滑动后的等待时间。 */
        const val SCROLL_SETTLE_MS = 700L

        /** 打开搜索页后的等待时间。 */
        const val SEARCH_PAGE_WAIT_MS = 900L

        /** 输入查询词后等待搜索结果的时间。 */
        const val SEARCH_RESULT_WAIT_MS = 1200L

        /** 搜索结果轮询次数与间隔。 */
        const val RESULT_POLLS = 5
        const val RESULT_POLL_MS = 600L

        /** 点击结果后等待结果页加载的时间。 */
        const val RESULT_PAGE_WAIT_MS = 900L

        /** 结果页轮询次数与间隔。 */
        const val RESULT_PAGE_POLLS = 4
        const val RESULT_PAGE_POLL_MS = 600L

        /** 重新拉起设置后的等待时间。 */
        const val LAUNCH_SETTLE_MS = 1500L

        /** 取树重试次数与间隔。 */
        const val FRAME_ATTEMPTS = 3
        const val FRAME_RETRY_MS = 400L

        /** 一致观察重采次数与间隔。 */
        const val SNAPSHOT_ATTEMPTS = 3
        const val SNAPSHOT_RETRY_MS = 500L
    }
}

/**
 * 一次结果页等待的结果。
 *
 * @property frame 副屏上的结果页；没到达时为 null
 * @property relocated 是否因搜索结果逃到主屏而改用了副屏直拉
 */
private data class PageReach(
    val frame: TreeFrame?,
    val relocated: Boolean,
)

/**
 * 一条系统 action 直达的设置页复验路线。
 *
 * @property titleHint 初验页标题应包含的文案
 * @property action 在副屏拉起页面的系统 action
 * @property extraHints 直达页标题还可以匹配的别名（ROM 差异）
 */
private data class SettingsDirectRoute(
    /** 目标页标题（或标题的一部分）；同时用于"要不要走这条路线"的判断。 */
    val titleHint: String,
    /** 直达用的系统 action。 */
    val action: String,
    /** 直达后第一跳可能落到的别的标题（例如小米「全部参数与信息」要先落到「我的设备」）。 */
    val extraHints: List<String>,
    /** 二级页：直达落地后还要再点一次这个文案才到目标页。 */
    val followUpLabel: String? = null,
    /** 二级页点进去之后的标题。 */
    val followUpTitle: String? = null,
) {
    /**
     * 功能：判断直达后的页面是否是目标页
     * 参数：activity 直达页标题
     * 返回：标题包含任一别名时为 true
     */
    fun matches(activity: String): Boolean =
        activity.contains(titleHint) || extraHints.any { activity.contains(it) }
}

/**
 * 独立复验的尝试结果。
 *
 * @property snapshot 第二路证据；不可达时为 null
 * @property note 路线说明或卡在哪一步，进审计
 */
data class ReachResult(
    val snapshot: ObservationSnapshot?,
    val note: String,
)
