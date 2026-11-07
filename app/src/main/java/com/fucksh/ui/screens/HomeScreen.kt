package com.fucksh.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fucksh.data.BlockerUiState
import com.fucksh.ui.components.*
import com.fucksh.ui.theme.TintKind
import com.fucksh.ui.theme.glassEdge
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    state: BlockerUiState,
    onGuardChange: (Boolean) -> Unit,
    onSelfCheck: () -> Unit,
    onRestart: () -> Unit,
    onRefresh: () -> Unit,
    onModuleClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp)
            .padding(horizontal = 16.dp)
            .padding(bottom = 120.dp)
    ) {
        // Status Card
        Card(modifier = Modifier.glassEdge(18.dp), insideMargin = PaddingValues(16.dp)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusChip(text = state.statusLabel, active = state.guardEnabled)
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = state.guardEnabled, onCheckedChange = onGuardChange)
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MiuixTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.ShieldCheck,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(state.headline, style = MiuixTheme.textStyles.title3, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(state.subheadline, style = MiuixTheme.textStyles.body2)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    MetricItem(
                        value = String.format("%,d", state.effectiveStats.total),
                        label = "已拦截总数"
                    )
                    MetricItem(
                        value = "+${state.effectiveStats.today}",
                        label = "今日防御"
                    )
                    MetricItem(
                        value = "${String.format("%.1f", state.effectiveStats.purity)}%",
                        label = "纯净指数"
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        SmallTitle("核心防护模块")
        
        // Modules Grid
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardModuleCard(
                icon = AppIcons.Globe,
                kind = TintKind.PRIMARY,
                count = "${state.effectiveStats.net}",
                title = "网络出口阻断",
                summary = "DNS / 遥测上送清洗",
                onClick = { onModuleClick("网络出口阻断：已拦截 ${state.effectiveStats.net} 条") },
                modifier = Modifier.weight(1f)
            )
            DashboardModuleCard(
                icon = AppIcons.Activity,
                kind = TintKind.NEUTRAL,
                count = "${state.effectiveStats.tel}",
                title = "采集总闸拦截",
                summary = "OAID / 诊断上报旁路",
                onClick = { onModuleClick("采集总闸拦截：已拦截 ${state.effectiveStats.tel} 条") },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DashboardModuleCard(
                icon = AppIcons.MapPin,
                kind = TintKind.SUCCESS,
                count = "${state.effectiveStats.loc}",
                title = "定位权限护盾",
                summary = "基站与 Wi-Fi 模糊化",
                onClick = { onModuleClick("定位权限护盾：已保护 ${state.effectiveStats.loc} 次") },
                modifier = Modifier.weight(1f)
            )
            DashboardModuleCard(
                icon = AppIcons.Archive,
                kind = TintKind.WARNING,
                count = "${state.effectiveStats.str}",
                title = "落库清理守护",
                summary = "沙盒追踪标记自动丢弃",
                onClick = { onModuleClick("落库清理守护：已清理 ${state.effectiveStats.str} 次") },
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        SmallTitle("系统维保")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onSelfCheck,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Activity,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("环境自检")
                }
            }
            Button(
                onClick = onRestart,
                enabled = !state.busy,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = AppIcons.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("重启守护")
                }
            }
        }
    }
}
