package com.neptine.mimir.ui

import com.neptine.mimir.ui.i18n.t
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neptine.mimir.ui.component.MimirHairline
import com.neptine.mimir.ui.component.MimirIcons
import com.neptine.mimir.ui.theme.LocalMimirColors
import com.neptine.mimir.ui.theme.MimirSpacing
import com.neptine.mimir.ui.theme.MimirType

/** 侧边栏宽度；比首版收窄 48dp，仍能容纳四个汉字的标签与品牌区。 */
private val DrawerWidth = 248.dp

/**
 * 功能：渲染侧边栏（品牌区 + 四个一级页 + 版本脚注）
 * 参数：currentTab 当前页，用于高亮；onSelect 选择一级页的回调
 * 返回：无
 *
 * 导航项是"图标在上、文字在下"的纵向块，而不是常见的左图标右文字：
 * 这是从原底栏继承下来的形态（用户点名要图标+底部文字），
 * 放进抽屉后仍然成立，而且每个块有完整的点击面积，比一行细文字好点得多。
 *
 * 全站不使用 01/02 这类序号：模块的先后没有语义，编号只会被读成噪声。
 */
@Composable
fun MimirDrawerContent(
    currentTab: MimirTab?,
    onSelect: (MimirTab) -> Unit,
) {
    val colors = LocalMimirColors.current
    val context = LocalContext.current
    val version = remember(context) {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }

    ModalDrawerSheet(
        modifier = Modifier.width(DrawerWidth),
        drawerShape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp),
        drawerContainerColor = colors.canvas,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = MimirSpacing.xxl),
        ) {
            Spacer(modifier = Modifier.height(MimirSpacing.xxl))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MimirMark(size = 40.dp)
                Spacer(modifier = Modifier.width(MimirSpacing.md))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MIMIR",
                            style = MimirType.Brand,
                            color = colors.ink,
                        )
                        Spacer(modifier = Modifier.width(MimirSpacing.sm))
                        // 一点珊瑚色：品牌区唯一的彩色，提醒这里是"产品"而不是页眉
                        Spacer(
                            modifier = Modifier
                                .size(5.dp)
                                .background(colors.coral, RoundedCornerShape(999.dp)),
                        )
                    }
                    Text(
                        text = "PERSONAL AGENT",
                        style = MimirType.Brand.copy(letterSpacing = 1.2.sp),
                        color = colors.muted,
                    )
                }
            }
            Spacer(modifier = Modifier.height(MimirSpacing.xxxl))

            MimirTab.entries.forEach { tab ->
                SidebarNavItem(
                    tab = tab,
                    selected = tab == currentTab,
                    onClick = { onSelect(tab) },
                )
                Spacer(modifier = Modifier.height(MimirSpacing.xs))
            }

            Spacer(modifier = Modifier.weight(1f))
            MimirHairline()
            Spacer(modifier = Modifier.height(MimirSpacing.lg))
            Text(
                text = "v$version · API 36",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
            )
            Spacer(modifier = Modifier.height(MimirSpacing.xl))
        }
    }
}

/**
 * 功能：渲染一个侧边栏导航项
 * 参数：tab 目标页；selected 是否选中；onClick 点击回调
 * 返回：无
 *
 * 选中态用"浅墨底 + 深色字"表达，而不是下划线：
 * 一整块可点的底色比一条 2dp 的线更容易被认出是"当前所在位置"。
 */
@Composable
private fun SidebarNavItem(
    tab: MimirTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalMimirColors.current
    val container by animateColorAsState(
        targetValue = if (selected) colors.ink.copy(alpha = 0.07f) else Color.Transparent,
        animationSpec = tween(180),
        label = "navContainer",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.ink else colors.slate,
        animationSpec = tween(180),
        label = "navContent",
    )
    // semantics 的 lambda 不是可组合作用域，文案必须先在组合里取好
    val description = tab.description.t()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = MimirSpacing.lg, vertical = 11.dp),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.height(MimirSpacing.xs))
        Text(
            text = tab.label.t(),
            style = MaterialTheme.typography.titleSmall,
            color = content,
        )
    }
}

/**
 * 功能：渲染左上角的 M 标记（项目原型图标，也是侧边栏入口）
 * 参数：onClick 点击回调；modifier 修饰符；size 图标边长
 * 返回：无
 *
 * 用"墨块 + 白 M"而不是直接把字母放在画布上：页眉背后有颗粒纹理，
 * 纯字母会和噪点混在一起；实心块给了一个稳定的点击目标与视觉锚点。
 */
@Composable
fun MimirMarkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
) {
    // 触控盒比标记本体高 12dp（48dp 标准），宽 8dp：标记靠左上角绘制，
    // 视觉位置与页眉左边距对齐，多出来的部分只用于扩大点击范围。
    val menuLabel = "打开导航菜单".t()
    Box(
        modifier = modifier
            .width(size + 8.dp)
            .height(size + 12.dp)
            .clip(RoundedCornerShape((size + 8.dp) * 0.3f))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = menuLabel },
        contentAlignment = Alignment.TopStart,
    ) {
        MimirMark(size = size)
    }
}

/**
 * 功能：渲染 M 标记本体（不可点）
 * 参数：size 边长
 * 返回：无
 *
 * 完全复刻启动图标 `res/drawable/ic_launcher_foreground.xml`：
 * 同一组坐标（108 视口内的 32,76 → 32,38 → 54,62 → 76,38 → 76,76）、
 * 同一条 6 单位白色描边、同样的圆头圆角；底色也取图标资源里的 #17171C。
 * 这样桌面上的 App 图标与页面左上角是同一个标记，而不是"一个黑方块加一个字母"。
 */
@Composable
fun MimirMark(size: Dp) {
    val colors = LocalMimirColors.current
    Canvas(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(colors.primary),
    ) {
        val unit = this.size.width / ICON_VIEWPORT
        val path = Path().apply {
            moveTo(32f * unit, 76f * unit)
            lineTo(32f * unit, 38f * unit)
            lineTo(54f * unit, 62f * unit)
            lineTo(76f * unit, 38f * unit)
            lineTo(76f * unit, 76f * unit)
        }
        drawPath(
            path = path,
            color = Color.White,
            style = Stroke(
                width = 6f * unit,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

/** 启动图标前景层使用的 108×108 视口。 */
private const val ICON_VIEWPORT = 108f
