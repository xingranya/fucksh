package com.fucksh.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.fucksh.data.BlockerMode
import com.fucksh.data.BlockerUiState
import com.fucksh.data.PolicyGroup
import com.fucksh.data.PolicyKey
import com.fucksh.ui.components.ConfigGroup
import com.fucksh.ui.theme.glassEdge
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConfigScreen(
    state: BlockerUiState,
    onModeChange: (BlockerMode) -> Unit,
    onPolicyChange: (PolicyKey, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp)
            .padding(bottom = 140.dp),
    ) {
        // Miuix 药丸胶囊分段选择器（完全告别方块状）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(MiuixTheme.colorScheme.surfaceContainer)
                .glassEdge(22.dp)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BlockerMode.entries.forEach { mode ->
                val selected = state.mode == mode
                val targetBg = if (selected) MiuixTheme.colorScheme.primary else Color.Transparent
                val animatedBg by animateColorAsState(
                    targetValue = targetBg,
                    animationSpec = tween(220),
                    label = "mode_tab_bg",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(50))
                        .background(animatedBg)
                        .clickable { onModeChange(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = mode.label,
                        style = MiuixTheme.textStyles.body2,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) {
                            MiuixTheme.colorScheme.onPrimary
                        } else {
                            MiuixTheme.colorScheme.onBackground.copy(alpha = 0.72f)
                        },
                    )
                }
            }
        }

        PolicyGroup.entries.forEach { group ->
            ConfigGroup(
                group = group,
                policies = PolicyKey.entries.filter { it.group == group },
                statePolicies = state.policies,
                onPolicyChange = onPolicyChange,
                busy = state.busy,
            )
        }
    }
}
