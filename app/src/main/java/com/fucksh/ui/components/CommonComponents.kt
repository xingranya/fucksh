package com.fucksh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import com.fucksh.data.LogCategory
import com.fucksh.ui.theme.TintKind
import com.fucksh.ui.theme.glassEdge
import com.fucksh.ui.theme.tintColors

/** 分组标题，直接复用 Miuix SmallTitle 的 28dp / 8dp 规范。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    SmallTitle(text = text, modifier = modifier)
}

/** 带底色圆角容器的小图标。 */
@Composable
fun TintIcon(
    icon: ImageVector,
    kind: TintKind,
    modifier: Modifier = Modifier,
    containerSize: Dp = 28.dp,
    iconSize: Dp = 20.dp,
) {
    val colors = tintColors(kind)
    Box(
        modifier = modifier
            .size(containerSize)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.foreground,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** 状态胶囊：绿点 + 文案。 */
@Composable
fun StatusChip(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val background = if (active) Color(0x1F28C76F) else MiuixTheme.colorScheme.secondaryVariant
    val foreground = if (active) Color(0xFF1C9C56) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(foreground),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = foreground,
        )
    }
}

/** 首页三项统计中的一项。 */
@Composable
fun MetricItem(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 首页 2×2 核心防护模块卡片。 */
@Composable
fun DashboardModuleCard(
    icon: ImageVector,
    kind: TintKind,
    count: String,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.glassEdge(16.dp),
        insideMargin = PaddingValues(14.dp),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TintIcon(icon = icon, kind = kind, containerSize = 30.dp, iconSize = 21.dp)
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = count,
                style = MiuixTheme.textStyles.title2,
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.body1,
            color = MiuixTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 日志类别徽标。 */
@Composable
fun LogBadge(category: LogCategory) {
    val (background, foreground) = when (category) {
        LogCategory.NETWORK -> Color(0x1F3482FF) to Color(0xFF2F6FE4)
        LogCategory.TELEMETRY -> Color(0x1FFFC107) to Color(0xFFB87400)
        LogCategory.LOCATION -> Color(0x1F28C76F) to Color(0xFF1C9C56)
        LogCategory.STORAGE -> Color(0x1FE94634) to Color(0xFFE94634)
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = category.badge,
            style = MiuixTheme.textStyles.footnote2,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * miuix 模糊玻璃条：底部导航、日志操作栏共用。
 * 背景内容由调用方通过 [backdrop] 捕获，模糊半径与染色针对深浅色分别调校。
 */
@Composable
fun GlassBar(
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(0.dp),
    divider: Boolean = true,
    tint: Color? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val baseTint = tint ?: if (dark) Color(0xB3000000) else Color(0xC4FFFFFF)
    val colors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(baseTint)),
        brightness = if (dark) 0.02f else 0.04f,
        contrast = 1f,
        saturation = 1.2f,
    )
    Box(
        modifier = modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = 26f,
            noiseCoefficient = 0.02f,
            colors = colors,
        ),
    ) {
        val scope = this
        Column {
            if (divider) {
                HorizontalDivider(color = MiuixTheme.colorScheme.dividerLine)
            }
            scope.content()
        }
    }
}

/** 空状态提示。 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
