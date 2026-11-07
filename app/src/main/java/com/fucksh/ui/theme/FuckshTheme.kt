package com.fucksh.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/**
 * 全局主题：跟随系统深浅色。
 *
 * 在 Miuix 默认色板之上，把 surfaceContainer 系列改成半透明，
 * 让所有 Card / 设置组都呈现「液态玻璃」质感，透出底部的极光背景。
 */
@Composable
fun FuckshTheme(content: @Composable () -> Unit) {
    val controller = remember { ThemeController(ColorSchemeMode.System) }
    MiuixTheme(controller = controller) {
        val dark = isSystemInDarkTheme()
        val base = MiuixTheme.colorScheme
        val glass = remember(base, dark) {
            base.copy(
                surfaceContainer = base.surfaceContainer.copy(alpha = if (dark) 0.52f else 0.60f),
                surfaceContainerHigh = base.surfaceContainerHigh.copy(alpha = if (dark) 0.60f else 0.70f),
                surfaceContainerHighest = base.surfaceContainerHighest.copy(alpha = if (dark) 0.68f else 0.80f),
            )
        }
        MiuixTheme(colors = glass) {
            content()
        }
    }
}

/** 图标底色分类，对应设计稿里的五种 tint。 */
enum class TintKind { PRIMARY, NEUTRAL, ERROR, SUCCESS, WARNING }

data class TintColors(val background: Color, val foreground: Color)

@Composable
fun tintColors(kind: TintKind): TintColors {
    val scheme = MiuixTheme.colorScheme
    return when (kind) {
        TintKind.PRIMARY -> TintColors(scheme.tertiaryContainer, scheme.onTertiaryContainer)
        TintKind.NEUTRAL -> TintColors(scheme.secondaryVariant, scheme.onBackground)
        TintKind.ERROR -> TintColors(scheme.error.copy(alpha = 0.14f), scheme.error)
        TintKind.SUCCESS -> TintColors(Color(0x2928C76F), Color(0xFF1C9C56))
        TintKind.WARNING -> TintColors(Color(0x29FF9800), Color(0xFFC97A00))
    }
}

/** 卡片外沿的 1dp 液态高光边，压在内容之上但不遮挡文字。 */
@Composable
fun Modifier.glassEdge(radius: Dp = 16.dp): Modifier {
    val dark = isSystemInDarkTheme()
    return this.drawWithContent {
        drawContent()
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    Color.White.copy(alpha = if (dark) 0.26f else 0.92f),
                    Color.White.copy(alpha = if (dark) 0.08f else 0.28f),
                    Color.White.copy(alpha = if (dark) 0.02f else 0.08f),
                ),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

/**
 * 应用背景：一整层高斯模糊的极光光斑 + 顶部高光 + 底部暗角。
 * 模糊层与内容层分离，底部导航栏 / 日志操作栏的 miuix textureBlur 会读取内容层。
 */
@Composable
fun FuckshBackground(modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val scheme = MiuixTheme.colorScheme
    Box(modifier = modifier.fillMaxSize().background(scheme.background)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .blur(56.dp)
        ) {
            if (dark) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF2F6BFF).copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(size.width * 0.12f, size.height * 0.06f),
                        radius = size.width * 0.95f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF7C4DFF).copy(alpha = 0.26f), Color.Transparent),
                        center = Offset(size.width * 0.92f, size.height * 0.20f),
                        radius = size.width * 0.85f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF00D4FF).copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.72f, size.height * 0.94f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFFFF5EA8).copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.04f, size.height * 0.86f),
                        radius = size.width * 0.7f,
                    )
                )
            } else {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF7CC7FF).copy(alpha = 0.34f), Color.Transparent),
                        center = Offset(size.width * 0.08f, size.height * 0.04f),
                        radius = size.width * 0.95f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFFC7B8FF).copy(alpha = 0.26f), Color.Transparent),
                        center = Offset(size.width * 0.94f, size.height * 0.18f),
                        radius = size.width * 0.85f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFF8FF0D8).copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width * 0.62f, size.height * 0.96f),
                        radius = size.width * 0.9f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Color(0xFFFFD6E8).copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.02f, size.height * 0.86f),
                        radius = size.width * 0.7f,
                    )
                )
            }
        }

        // 顶部高光 + 底部暗角
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = if (dark) 0.04f else 0.12f),
                    0.30f to Color.Transparent,
                )
            )
            drawRect(
                Brush.verticalGradient(
                    0.72f to Color.Transparent,
                    1f to Color.Black.copy(alpha = if (dark) 0.18f else 0.05f),
                )
            )
        }
    }
}
