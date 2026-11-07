package com.neptine.mimir.agent

import com.neptine.mimir.core.contract.Bounds
import com.neptine.mimir.core.contract.Point
import com.neptine.mimir.core.contract.TargetFingerprint
import com.neptine.mimir.core.contract.TargetMode
import com.neptine.mimir.core.contract.AnchorLevel
import com.neptine.mimir.core.contract.AgentAction
import com.neptine.mimir.core.contract.ActionKind
import com.neptine.mimir.core.contract.ObservationSnapshot
import com.neptine.mimir.core.contract.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 动作协议：模型输出的 JSON 与 [AgentAction] 之间的唯一转换点（设计文档 §1.3 / §2.4）。
 *
 * 模型只回答"操作 #7"，不产出坐标；编号到 bounds 的映射在本地完成，
 * 这一步把"坐标漂移"从模型能力问题降级为本地查表问题（§1.3 第 1 条）。
 */
object ActionProtocol {

    /** 宽松解析：模型常在 JSON 外包代码块或补一句解释。 */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 功能：把模型原始输出解析成动作
     * 参数：raw 模型原始输出；snapshot 决策依据的观察
     * 返回：绑定 observationId 与目标指纹的动作
     * 抛出：ActionParseException 表示这一轮输出不可用，由上层同 prompt 重试一次
     */
    fun parse(raw: String, snapshot: ObservationSnapshot): AgentAction {
        val payload = extractJsonObject(raw)
            ?: throw ActionParseException("输出里没有 JSON 对象：${raw.take(RAW_LIMIT)}")

        val action = payload["action"]?.jsonPrimitive?.content?.lowercase()
            ?: throw ActionParseException("缺少 action 字段")

        // 「边做边声明」：动作与步骤完成写在同一个 JSON 里，省掉一次纯声明的视觉请求
        // （实测 36% 的调用只用来推进游标）。是否真的推进仍由本地后置条件复核决定。
        val declaresStepDone = payload["step_done"]?.jsonPrimitive?.booleanOrNull == true

        val parsed = when (action) {
            "tap" -> tapAction(payload, snapshot)
            "input" -> inputAction(payload, snapshot)
            "credential" -> credentialAction(payload, snapshot)
            "swipe" -> swipeAction(payload, snapshot)
            "back" -> AgentAction.Do(ActionKind.BACK, snapshot.observationId, targetFingerprint = null)
            "home" -> AgentAction.Do(ActionKind.HOME, snapshot.observationId, targetFingerprint = null)
            "launch" -> launchAction(payload, snapshot)
            "wait" -> AgentAction.Do(ActionKind.WAIT, snapshot.observationId, targetFingerprint = null)
            "finish" -> AgentAction.Finish(
                observationId = snapshot.observationId,
                summary = payload["summary"]?.jsonPrimitive?.content.orEmpty(),
            )
            "step_done" -> AgentAction.StepDone(
                observationId = snapshot.observationId,
                summary = payload["summary"]?.jsonPrimitive?.content.orEmpty(),
            )
            "replan" -> AgentAction.Replan(
                observationId = snapshot.observationId,
                reason = payload["reason"]?.jsonPrimitive?.content.orEmpty(),
            )
            else -> throw ActionParseException("未知动作 $action")
        }
        return if (declaresStepDone && parsed is AgentAction.Do) {
            parsed.copy(
                stepDone = true,
                stepSummary = payload["summary"]?.jsonPrimitive?.contentOrNull,
            )
        } else {
            parsed
        }
    }

    /**
     * 功能：从原始输出里截出第一个 JSON 对象
     * 参数：raw 模型原始输出
     * 返回：解析后的对象；找不到或不合法时为 null
     */
    private fun extractJsonObject(raw: String): JsonObject? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    /**
     * 功能：解析点击动作
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：带目标指纹的点击动作
     */
    /**
     * 功能：把模型给的 point 归一化成 0–1000
     * 参数：point 模型输出的坐标对象；snapshot 当前观察（用于像素→归一化折算）
     * 返回：归一化 (x, y)
     *
     * 模型实际会混用三种写法，硬要求 0–1000 会让整条任务在第一步就失败（真机 DeepSeek 实测：
     * 连续两次"point 需要 0–1000 的 x/y"，任务直接 FAILED，且没有任何审计留下原始输出）：
     * 1. 0–1000 归一化（提示词要求的形式）；
     * 2. 0–1 的相对坐标（部分模型习惯）；
     * 3. 像素坐标（720×1600 这类，y 轻松超过 1000）。
     * 这里按取值范围判定，再用图或副屏尺寸折算，最后夹到合法区间——宁可容忍，也不要判死任务。
     */
    private fun parsePoint(point: JsonObject, snapshot: ObservationSnapshot): Pair<Int, Int> {
        val rawX = point["x"]?.jsonPrimitive?.doubleOrNull
            ?: point["x"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        val rawY = point["y"]?.jsonPrimitive?.doubleOrNull
            ?: point["y"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        if (rawX == null || rawY == null) {
            throw ActionParseException("point 需要数字 x/y，实际是 $point")
        }
        val nx: Double
        val ny: Double
        when {
            rawX <= 1.0 && rawY <= 1.0 -> {
                nx = rawX * NORM_MAX
                ny = rawY * NORM_MAX
            }
            rawX <= NORM_MAX && rawY <= NORM_MAX -> {
                nx = rawX
                ny = rawY
            }
            else -> {
                val imageWidth = snapshot.image?.width ?: 0
                val imageHeight = snapshot.image?.height ?: 0
                val fitsImage = imageWidth > 0 && rawX <= imageWidth && rawY <= imageHeight
                val width = if (fitsImage) imageWidth else snapshot.nativeWidth
                val height = if (fitsImage) imageHeight else snapshot.nativeHeight
                if (width <= 0 || height <= 0) {
                    throw ActionParseException("point 超出 0–1000 且没有屏幕尺寸可折算：$point")
                }
                nx = rawX * NORM_MAX / width
                ny = rawY * NORM_MAX / height
            }
        }
        return nx.coerceIn(0.0, NORM_MAX).toInt() to ny.coerceIn(0.0, NORM_MAX).toInt()
    }

    private fun tapAction(payload: JsonObject, snapshot: ObservationSnapshot): AgentAction.Do {
        // 纯视觉专用：目标在元素表里根本没有（例如空的输入框），只能给归一化坐标。
        // 坐标一律 0–1000，按截图尺寸换算，模型不需要知道原生分辨率。
        val point = runCatching { payload["point"]?.jsonObject }.getOrNull()
        if (point != null) {
            val (x, y) = parsePoint(point, snapshot)
            return AgentAction.Do(
                kind = ActionKind.TAP,
                observationId = snapshot.observationId,
                targetFingerprint = null,
                normX = x,
                normY = y,
            )
        }
        val element = requireElement(payload, snapshot)
        return AgentAction.Do(
            kind = ActionKind.TAP,
            observationId = snapshot.observationId,
            targetFingerprint = fingerprintOf(element),
            elementId = element.index,
        )
    }

    /**
     * 功能：解析文本输入动作
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：带目标指纹与正文的输入动作
     */
    private fun inputAction(payload: JsonObject, snapshot: ObservationSnapshot): AgentAction.Do {
        val text = payload["text"]?.jsonPrimitive?.content
            ?: throw ActionParseException("input 动作缺少 text")
        // 纯视觉：输入框在元素表里没有编号（没有文字），只能靠坐标定位。
        // 与 tap 的 point 语义一致：0–1000 归一化，先点这个位置聚焦再键入。
        val point = runCatching { payload["point"]?.jsonObject }.getOrNull()
        if (point != null) {
            val (x, y) = parsePoint(point, snapshot)
            return AgentAction.Do(
                kind = ActionKind.TEXT,
                observationId = snapshot.observationId,
                targetFingerprint = null,
                normX = x,
                normY = y,
                text = text,
            )
        }
        val element = requireElement(payload, snapshot)
        return AgentAction.Do(
            kind = ActionKind.TEXT,
            observationId = snapshot.observationId,
            targetFingerprint = fingerprintOf(element),
            elementId = element.index,
            text = text,
        )
    }

    /**
     * 功能：解析凭据填充动作
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：带目标指纹与 credential_id 的动作
     *
     * 模型只能提交凭据编号，不能提交密码；密码由本地 Broker 在授权后解密填充。
     */
    private fun credentialAction(payload: JsonObject, snapshot: ObservationSnapshot): AgentAction.Do {
        val element = requireElement(payload, snapshot)
        val credentialId = payload["credential"]?.jsonPrimitive?.content
            ?: throw ActionParseException("credential 动作缺少 credential")
        return AgentAction.Do(
            kind = ActionKind.CREDENTIAL,
            observationId = snapshot.observationId,
            targetFingerprint = fingerprintOf(element),
            elementId = element.index,
            text = credentialId,
        )
    }

    /**
     * 功能：解析滑动手势
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：带原生像素轨迹的滑动动作
     */
    private fun swipeAction(payload: JsonObject, snapshot: ObservationSnapshot): AgentAction.Do {
        val direction = payload["direction"]?.jsonPrimitive?.content?.lowercase() ?: "up"
        val distance = payload["distance"]?.jsonPrimitive?.intOrNull ?: DEFAULT_SWIPE_PERCENT
        val path = swipePath(direction, distance, snapshot)
        return AgentAction.Do(
            kind = ActionKind.SWIPE,
            observationId = snapshot.observationId,
            targetFingerprint = null,
            swipePath = path,
        )
    }

    /**
     * 功能：解析拉起应用动作
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：携带包名的动作
     */
    private fun launchAction(payload: JsonObject, snapshot: ObservationSnapshot): AgentAction.Do {
        val packageName = payload["package"]?.jsonPrimitive?.content
            ?: throw ActionParseException("launch 动作缺少 package")
        return AgentAction.Do(
            kind = ActionKind.LAUNCH,
            observationId = snapshot.observationId,
            targetFingerprint = null,
            text = packageName,
        )
    }

    /**
     * 功能：按编号取元素
     * 参数：payload 模型输出对象；snapshot 当前观察
     * 返回：编号对应的元素
     * 抛出：编号缺失或不存在（模型编造编号）时抛 ActionParseException
     */
    private fun requireElement(payload: JsonObject, snapshot: ObservationSnapshot): UiElement {
        val index = payload["element"]?.jsonPrimitive?.intOrNull
            ?: throw ActionParseException("动作缺少 element 编号")
        return snapshot.elements.firstOrNull { it.index == index }
            ?: throw ActionParseException("元素 #$index 不在当前元素表中（共 ${snapshot.elements.size} 个）")
    }

    /**
     * 功能：由元素构造目标指纹
     * 参数：element 目标元素
     * 返回：树模式指纹，锚点级别按可用信息取 A1/A2/A3
     *
     * internal 而非 private：本地改投输入目标时也要用同一套指纹构造，
     * 两处各写一份迟早会漂移。
     */
    internal fun fingerprintOf(element: UiElement): TargetFingerprint {
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
     * 功能：生成滑动轨迹
     * 参数：direction 方向；distance 占屏高的百分比（0–100）；snapshot 当前观察
     * 返回：12 个点的原生像素轨迹
     */
    private fun swipePath(direction: String, distance: Int, snapshot: ObservationSnapshot): List<Point> {
        val width = snapshot.nativeWidth
        val height = snapshot.nativeHeight
        val span = (height * distance.coerceIn(10, 90) / 100).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        val (fromY, toY) = when (direction) {
            "down" -> centerY - span / 2 to centerY + span / 2
            "left" -> centerY to centerY
            else -> centerY + span / 2 to centerY - span / 2
        }
        val (fromX, toX) = when (direction) {
            "left" -> centerX + span / 2 to centerX - span / 2
            "right" -> centerX - span / 2 to centerX + span / 2
            else -> centerX to centerX
        }

        return (0..SWIPE_POINTS).map { step ->
            val ratio = step.toFloat() / SWIPE_POINTS
            Point(
                x = (fromX + (toX - fromX) * ratio).toInt(),
                y = (fromY + (toY - fromY) * ratio).toInt(),
            )
        }
    }

    /** 默认滑动距离（占屏高百分比）。 */
    private const val DEFAULT_SWIPE_PERCENT = 50

    /** 轨迹中间点数量；与执行层的缓动配合，避免直线匀速（§4.5）。 */
    private const val SWIPE_POINTS = 12

    /** 异常信息里最多回显多少字模型输出。 */
    private const val RAW_LIMIT = 200

    /** 归一化坐标的满量程：模型给的 x/y 是 0–1000。 */
    private const val NORM_MAX = 1000.0
}

/** 动作解析失败；上层据此同 prompt 重试一次（§1.3 S4b）。 */
class ActionParseException(message: String) : IllegalStateException(message)
