package com.fucksh.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import com.fucksh.data.BlockerUiState
import com.fucksh.data.HealthReport
import com.fucksh.ui.components.AppIcons
import com.fucksh.ui.components.GlassBar
import com.fucksh.ui.screens.AboutScreen
import com.fucksh.ui.screens.ConfigScreen
import com.fucksh.ui.screens.HomeScreen
import com.fucksh.ui.screens.LogsScreen
import com.fucksh.ui.theme.FuckshBackground
import com.fucksh.viewmodel.BlockerViewModel
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

private data class TabMeta(
    val title: String,
    val subtitle: String,
    val smallTitle: String,
    val navLabel: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    TabMeta("系统守护", "fucksh 运行中", "系统守护", "首页", AppIcons.Home),
    TabMeta("拦截策略", "规则配置与模式切换", "拦截策略", "策略", AppIcons.Settings),
    TabMeta("实时记录", "安全审计与拦截事件流", "实时记录", "日志", AppIcons.List),
    TabMeta("关于", "应用信息与开源说明", "关于", "关于", AppIcons.User),
)

@Composable
fun FuckshApp(viewModel: BlockerViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val backdrop = rememberLayerBackdrop()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.shareRequests.collect { file -> shareAudit(context, file) }
    }

    // 监听前台生命周期，触发模块审计定时刷新
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.setForeground(true)
            try { awaitCancellation() } finally { viewModel.setForeground(false) }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // 顶栏高度 64dp，使用 BackdropBlur 覆盖在内容背景之上
                GlassBar(
                    backdrop = backdrop,
                    divider = false,
                    tint = if (isSystemInDarkTheme()) Color(0x99000000) else Color(0xB3FFFFFF),
                ) {
                    Box(modifier = Modifier.height(64.dp).fillMaxWidth().padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = tabs[tabIndex].title,
                            style = MiuixTheme.textStyles.title2,
                            color = MiuixTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            },
            bottomBar = {
                GlassBar(
                    backdrop = backdrop,
                    divider = false,
                    tint = if (isSystemInDarkTheme()) Color(0x99000000) else Color(0xB3FFFFFF),
                ) {
                    NavigationBar(
                        color = Color.Transparent,
                        showDivider = false,
                    ) {
                        val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
                        tabs.forEachIndexed { index, meta ->
                            NavigationBarItem(
                                selected = tabIndex == index,
                                onClick = { 
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    tabIndex = index 
                                },
                                icon = meta.icon,
                                label = meta.navLabel,
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { padding ->
            // 内容区域：背景 + 内容
            Box(modifier = Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                FuckshBackground() // 背景作为内容的一部分被捕获
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding() + 16.dp, bottom = padding.calculateBottomPadding()),
                ) {
                    AnimatedContent(
                        targetState = tabIndex,
                        transitionSpec = {
                            (fadeIn(spring(stiffness = 380f)) + slideInVertically(spring(stiffness = 380f)) { it / 36 })
                                .togetherWith(
                                    fadeOut(spring(stiffness = 420f)) + slideOutVertically(spring(stiffness = 420f)) { -it / 48 },
                                )
                        },
                        label = "fucksh-pages",
                    ) { index ->
                        when (index) {
                            0 -> HomeScreen(
                                state = state,
                                onGuardChange = viewModel::setGuard,
                                onSelfCheck = viewModel::selfCheck,
                                onRestart = viewModel::restartGuard,
                                onRefresh = viewModel::refreshModule,
                                onModuleClick = viewModel::showMessage,
                            )
                            1 -> ConfigScreen(
                                state = state,
                                onModeChange = viewModel::setMode,
                                onPolicyChange = viewModel::setPolicy,
                            )
                            2 -> LogsScreen(
                                state = state,
                                onFilterChange = viewModel::setFilter,
                                onClear = viewModel::clearLogs,
                                onExport = viewModel::exportAudit,
                            )
                            else -> AboutScreen(
                                state = state,
                                onCheckRules = viewModel::checkRules,
                                onCheckAppUpdate = viewModel::checkUpdate,
                                onOpenUrl = { url -> openUrl(context, url) },
                            )
                        }
                    }
                }
            }
            state.lastHealth?.let { report ->
                HealthDialog(report = report, onDismiss = viewModel::dismissHealth)
            }
        }
    }

}

/**
 * 紧凑顶栏：内容位于 GlassBar 内，具有真正的高斯模糊液态玻璃质感。
 */
@Composable
private fun FuckshTopBar(meta: TabMeta) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp),
    ) {
        Text(
            text = meta.title,
            style = MiuixTheme.textStyles.title2,
            color = MiuixTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = meta.subtitle,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onBackgroundVariant,
        )
    }
}

@Composable
private fun HealthDialog(report: HealthReport, onDismiss: () -> Unit) {
    OverlayDialog(
        show = true,
        title = "环境自检",
        summary = "通过 ${report.passed}/${report.items.size} 项",
        onDismissRequest = onDismiss,
    ) {
        Column {
            report.items.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (item.ok) AppIcons.ShieldCheck else AppIcons.AlertCircle,
                        contentDescription = null,
                        tint = if (item.ok) Color(0xFF1C9C56) else MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            style = MiuixTheme.textStyles.body1,
                            color = MiuixTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = item.detail,
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onDismiss) {
                    Text("知道了")
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

private fun shareAudit(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "fucksh 审计记录")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "导出审计记录").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
