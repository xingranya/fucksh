package com.fucksh.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fucksh.data.PolicyGroup
import com.fucksh.data.PolicyKey
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.SwitchPreference
import androidx.compose.foundation.layout.PaddingValues
import com.fucksh.ui.theme.TintKind
import com.fucksh.ui.theme.glassEdge

@Composable
fun ConfigGroup(
    group: PolicyGroup,
    policies: List<PolicyKey>,
    statePolicies: Map<PolicyKey, Boolean>,
    onPolicyChange: (PolicyKey, Boolean) -> Unit,
    busy: Boolean
) {
    Column {
        SmallTitle(text = group.label)
        Card(modifier = Modifier.padding(horizontal = 16.dp).glassEdge(14.dp), insideMargin = PaddingValues(0.dp)) {
            Column {
                policies.forEachIndexed { index, policy ->
                    SwitchPreference(
                        title = policy.title,
                        summary = policy.summary,
                        checked = statePolicies[policy] ?: policy.defaultEnabled,
                        onCheckedChange = { onPolicyChange(policy, it) },
                        enabled = !busy,
                        startAction = {
                            val (icon, tint) = when (policy) {
                                PolicyKey.NET_ANALYTICS -> AppIcons.Globe to TintKind.PRIMARY
                                PolicyKey.NET_AD_DOMAINS -> AppIcons.AlertCircle to TintKind.WARNING
                                PolicyKey.NET_DOH -> AppIcons.ShieldCheck to TintKind.PRIMARY
                                PolicyKey.TEL_OAID -> AppIcons.User to TintKind.SUCCESS
                                PolicyKey.TEL_SYS_LOG -> AppIcons.Document to TintKind.NEUTRAL
                                PolicyKey.LOC_FUSED -> AppIcons.MapPin to TintKind.SUCCESS
                                PolicyKey.STORE_SANDBOX -> AppIcons.Archive to TintKind.NEUTRAL
                            }
                            TintIcon(icon = icon, kind = tint)
                        }
                    )
                    if (index < policies.size - 1) {
                        HorizontalDivider(modifier = Modifier.padding(start = 60.dp))
                    }
                }
            }
        }
    }
}
