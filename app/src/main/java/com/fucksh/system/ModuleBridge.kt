package com.fucksh.system

import android.content.Context
import com.fucksh.data.HealthItem
import com.fucksh.data.HealthReport
import com.fucksh.data.RuleRepository

/**
 * 宿主环境与规则库查询。
 *
 * <p>纯 libxposed 架构：应用不执行任何 su 命令，也不建立 VPN。拦截在目标进程内完成，
 * 因此这里只做无副作用的查询。</p>
 */
class ModuleBridge(private val context: Context) {

    fun targetUid(): Int? = runCatching {
        context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0).uid
    }.getOrNull()

    fun isTargetInstalled(): Boolean = runCatching {
        context.packageManager.getApplicationInfo(TARGET_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** 规则库完整性检查（本地规则，不依赖网络）。 */
    fun checkRules(): HealthReport {
        val count = RuleRepository.ruleCount
        val version = RuleRepository.VERSION
        return HealthReport(
            items = listOf(
                HealthItem("规则库版本", true, version),
                HealthItem("规则条目", count > 0, "$count 条内置规则"),
                HealthItem("目标域名", true, "dm.wo.com.cn 已在拦截集合内"),
            ),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.mobiletools.systemhelper"
    }
}
