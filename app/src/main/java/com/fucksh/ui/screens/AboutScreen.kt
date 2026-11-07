package com.fucksh.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fucksh.R
import com.fucksh.data.BlockerUiState
import com.fucksh.ui.components.AppIcons
import com.fucksh.ui.components.StatusChip
import com.fucksh.ui.components.TintIcon
import com.fucksh.ui.theme.TintKind
import com.fucksh.ui.theme.glassEdge
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun AboutScreen(
    state: BlockerUiState,
    onCheckRules: () -> Unit,
    onCheckAppUpdate: () -> Unit = {},
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var thanksExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 140.dp),
    ) {
        // Logo + App Name + Version
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = "fucksh",
                modifier = Modifier
                    .size(84.dp)
                    .shadow(16.dp, RoundedCornerShape(22.dp), clip = false)
                    .clip(RoundedCornerShape(22.dp)),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "fucksh",
                style = MiuixTheme.textStyles.title1,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "v1.0.0 · Miuix Edition",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            )
        }

        // Developer Card：作者头像 + 名字 + 签名 + 标签
        Card(
            modifier = Modifier.glassEdge(16.dp),
            insideMargin = PaddingValues(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.avatar_author),
                    contentDescription = "xingranya 的头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .border(
                            width = 2.dp,
                            brush = Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.95f), MiuixTheme.colorScheme.primary),
                            ),
                            shape = CircleShape,
                        ),
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "xingranya（星苒鸭）",
                        style = MiuixTheme.textStyles.title4,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "把复杂问题拆简单，把简单方案做扎实。",
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.75f),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusChip(text = "Core Developer", active = true)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        SmallTitle("开源与支持")
        Card(modifier = Modifier.glassEdge(16.dp)) {
            ArrowPreference(
                title = "GitHub 源码仓库",
                summary = "https://github.com/xingranya/fucksh",
                onClick = { onOpenUrl("https://github.com/xingranya/fucksh") },
                startAction = { TintIcon(icon = AppIcons.Github, kind = TintKind.PRIMARY) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            ArrowPreference(
                title = "作者个人主页",
                summary = "https://xran.uk",
                onClick = { onOpenUrl("https://xran.uk") },
                startAction = { TintIcon(icon = AppIcons.Globe, kind = TintKind.PRIMARY) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            ArrowPreference(
                title = "开源许可证",
                summary = "GPL-3.0",
                onClick = { onOpenUrl("https://github.com/xingranya/fucksh/blob/main/LICENSE") },
                startAction = { TintIcon(icon = AppIcons.Document, kind = TintKind.PRIMARY) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            ArrowPreference(
                title = "检查应用更新",
                summary = "获取 fucksh 最新版本",
                onClick = onCheckAppUpdate,
                startAction = { TintIcon(icon = AppIcons.Refresh, kind = TintKind.PRIMARY) },
            )
            HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
            ArrowPreference(
                title = "检查规则更新",
                summary = "当前规则库版本 ${state.rulesVersion}",
                onClick = onCheckRules,
                startAction = { TintIcon(icon = AppIcons.Refresh, kind = TintKind.PRIMARY) },
            )
        }

        Spacer(modifier = Modifier.height(20.dp))
        SmallTitle("社区致谢")
        Card(modifier = Modifier.glassEdge(16.dp)) {
            ArrowPreference(
                title = "特别致谢",
                summary = if (thanksExpanded) "点击收起致谢清单" else "感谢开源社区的卓越贡献与灵感（点击展开）",
                onClick = { thanksExpanded = !thanksExpanded },
                startAction = { TintIcon(icon = AppIcons.Heart, kind = TintKind.PRIMARY) },
            )
        }

        AnimatedVisibility(
            visible = thanksExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Card(
                    modifier = Modifier.glassEdge(16.dp),
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ThanksItem(
                            name = "ColorOSGmsNetworkGuard",
                            summary = "SystemHelper 深度拦截与定时任务抑制方案",
                            onClick = { onOpenUrl("https://github.com/cvhhji/ColorOSGmsNetworkGuard") },
                        )
                        HorizontalDivider()
                        ThanksItem(
                            name = "compose-miuix-ui",
                            summary = "优雅的类 MIUI/HyperOS 设计组件库与模糊效果",
                            onClick = { onOpenUrl("https://compose-miuix-ui.github.io/miuix/") },
                        )
                        HorizontalDivider()
                        ThanksItem(
                            name = "KernelSU · libxposed",
                            summary = "现代化的内核级 Root 与现代 ART Hook 方案",
                            onClick = { onOpenUrl("https://kernelsu.org/") },
                        )
                        HorizontalDivider()
                        ThanksItem(
                            name = "躺平老咸鱼",
                            summary = "原 SystemHelper Blocker 作者",
                            onClick = { onOpenUrl("https://github.com/zhang010416/systemhelper-blocker") },
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "fucksh for OnePlus 16\nBuilt with compose-miuix-ui Design Specification",
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.45f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ThanksItem(
    name: String,
    summary: String,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Text(
            text = name,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
    }
}
