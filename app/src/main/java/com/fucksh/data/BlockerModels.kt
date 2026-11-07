package com.fucksh.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 拦截模式：驱动策略开关的一组预设。 */
enum class BlockerMode(val label: String, val summary: String) {
    RECOMMENDED("推荐模式", "均衡防护，兼容日常使用"),
    STRICT("严格静默", "全量拦截，包含 DoH 探测"),
    DEBUG("调试监控", "仅保留网络出口防护");

    companion object {
        fun fromKey(key: String?): BlockerMode = entries.firstOrNull { it.name == key } ?: RECOMMENDED
    }
}

/** 策略分组，对应配置页的三个 SmallTitle。 */
enum class PolicyGroup(val label: String) {
    NETWORK("网络出口防护"),
    TELEMETRY("遥测与身份标识"),
    LOCATION("定位与沙盒隔离"),
}

/** 七个可持久化的策略开关。 */
enum class PolicyKey(
    val configKey: String,
    val title: String,
    val summary: String,
    val group: PolicyGroup,
    val category: LogCategory,
    val defaultEnabled: Boolean = true,
) {
    NET_ANALYTICS(
        configKey = "BLOCK_ANALYTICS",
        title = "统计与分析域名清洗",
        summary = "阻断应用后台静默发起的遥测请求",
        group = PolicyGroup.NETWORK,
        category = LogCategory.NETWORK,
    ),
    NET_AD_DOMAINS(
        configKey = "BLOCK_AD_DOMAINS",
        title = "开屏画像与广告阻断",
        summary = "阻断冷启动预加载与用户画像推送",
        group = PolicyGroup.NETWORK,
        category = LogCategory.NETWORK,
    ),
    NET_DOH(
        configKey = "BLOCK_DOH",
        title = "应用内置 DoH 探测阻断",
        summary = "防范应用私建 DoH 逃逸全局策略",
        group = PolicyGroup.NETWORK,
        category = LogCategory.NETWORK,
        defaultEnabled = false,
    ),
    TEL_OAID(
        configKey = "MASK_OAID",
        title = "OAID 匿名标识符伪装",
        summary = "为每次调用返回单次随机 Hash 标识",
        group = PolicyGroup.TELEMETRY,
        category = LogCategory.TELEMETRY,
    ),
    TEL_SYS_LOG(
        configKey = "BLOCK_SYS_LOG",
        title = "系统日志静默上报旁路",
        summary = "过滤电池功耗与传感器特征上报",
        group = PolicyGroup.TELEMETRY,
        category = LogCategory.TELEMETRY,
    ),
    LOC_FUSED(
        configKey = "BLUR_LOCATION",
        title = "粗粒度网格模糊定位",
        summary = "限制精准坐标为 5km 区域半径",
        group = PolicyGroup.LOCATION,
        category = LogCategory.LOCATION,
    ),
    STORE_SANDBOX(
        configKey = "CLEAN_SANDBOX",
        title = "沙盒追踪标记清理",
        summary = "进程退出时抹除持久化匿名缓存",
        group = PolicyGroup.LOCATION,
        category = LogCategory.STORAGE,
    );

    companion object {
        fun defaults(): Map<PolicyKey, Boolean> = entries.associateWith { it.defaultEnabled }

        fun recommended(): Map<PolicyKey, Boolean> = defaults()

        fun strict(): Map<PolicyKey, Boolean> = entries.associateWith { true }

        fun debug(): Map<PolicyKey, Boolean> = entries.associateWith {
            it.group == PolicyGroup.NETWORK && it != NET_DOH
        }

        fun presetFor(mode: BlockerMode): Map<PolicyKey, Boolean> = when (mode) {
            BlockerMode.RECOMMENDED -> recommended()
            BlockerMode.STRICT -> strict()
            BlockerMode.DEBUG -> debug()
        }

        fun fromStorage(key: String?): PolicyKey? = entries.firstOrNull { it.name == key }
    }
}

/** 日志类别，同时用于筛选胶囊和徽标配色。 */
enum class LogCategory(val badge: String, val filterLabel: String) {
    NETWORK("NET", "网络出口"),
    TELEMETRY("TEL", "遥测采集"),
    LOCATION("LOC", "定位权限"),
    STORAGE("STR", "沙盒落盘"),
}

/** 事件来源：本地守护服务 / 系统模块。 */
enum class LogSource { LOCAL, MODULE }

data class LogEntry(
    val id: Long,
    val category: LogCategory,
    val target: String,
    val action: String,
    val timeMillis: Long,
    val source: LogSource = LogSource.LOCAL,
) {
    val timeLabel: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timeMillis))
}

data class BlockerStats(
    val total: Long = 0L,
    val today: Long = 0L,
    val net: Long = 0L,
    val tel: Long = 0L,
    val loc: Long = 0L,
    val str: Long = 0L,
    val day: String = "",
    val dnsAllowed: Long = 0L,
    val dnsBlocked: Long = 0L,
    val purityOverride: Double? = null,
) {
    /** 纯净指数：模块模式下取防护完整度，本地兜底模式取拦截占比。 */
    val purity: Double
        get() {
            purityOverride?.let { return it }
            val sampled = dnsAllowed + dnsBlocked
            return if (sampled <= 0L) 100.0 else dnsBlocked * 100.0 / sampled
        }

    fun countOf(category: LogCategory): Long = when (category) {
        LogCategory.NETWORK -> net
        LogCategory.TELEMETRY -> tel
        LogCategory.LOCATION -> loc
        LogCategory.STORAGE -> str
    }
}

data class ModuleStatus(
    val hookSummary: String? = null,
    val config: Map<String, Boolean> = emptyMap(),
    val counters: Map<String, Long> = emptyMap(),
    val serviceConnected: Boolean = false,
    val targetHooked: Boolean = false,
    val framework: String? = null,
    val targetState: String? = null,
    val hookPointCount: Int = 0,
    val loadedVersion: Long? = null,
    val error: String? = null,
    val lastReportAt: Long? = null,
    val frameworkConfirmed: Boolean = false,
    val configApplied: Boolean = false,
) {
    /** 框架确认进程正在运行且已加载当前模块。历史审计本身不代表在线。 */
    val online: Boolean get() = targetHooked && frameworkConfirmed
    val hookOnline: Boolean get() = online && hookPointCount > 0
}

data class HealthItem(
    val title: String,
    val ok: Boolean,
    val detail: String,
)

data class HealthReport(
    val items: List<HealthItem>,
    val timeMillis: Long = System.currentTimeMillis(),
) {
    val allOk: Boolean get() = items.isNotEmpty() && items.all { it.ok }
    val passed: Int get() = items.count { it.ok }
}

data class BlockerUiState(
    val loading: Boolean = true,
    val guardEnabled: Boolean = false,
    val mode: BlockerMode = BlockerMode.RECOMMENDED,
    val policies: Map<PolicyKey, Boolean> = PolicyKey.defaults(),
    val stats: BlockerStats = BlockerStats(),
    val logs: List<LogEntry> = emptyList(),
    val filter: LogCategory? = null,
    val module: ModuleStatus = ModuleStatus(),
    val targetInstalled: Boolean = false,
    val targetUid: Int? = null,
    val busy: Boolean = false,
    val lastHealth: HealthReport? = null,
    val rulesVersion: String = RuleRepository.VERSION,
    val rulesCount: Int = RuleRepository.ruleCount,
    val moduleStats: BlockerStats? = null,
) {
    val filteredLogs: List<LogEntry>
        get() = if (filter == null) logs else logs.filter { it.category == filter }

    /** 首页与日志使用同一份纯 Hook 审计，和是否具有 Root 权限无关。 */
    val effectiveStats: BlockerStats get() = moduleStats ?: stats

    val fullProtection: Boolean
        get() = guardEnabled && module.hookOnline && module.configApplied

    val statusLabel: String
        get() = when {
            !guardEnabled -> "守护已暂停"
            fullProtection -> "Hook 已生效"
            module.error != null -> "连接需要检查"
            module.online -> "进程已接入"
            module.serviceConnected -> "自动守护就绪"
            !targetInstalled -> "目标未安装"
            else -> "等待框架连接"
        }

    val headline: String
        get() = when {
            !guardEnabled -> "守护已暂停"
            fullProtection -> "进程内拦截已启用"
            module.targetState == "STALE" -> "目标进程需要重新加载"
            module.online -> "正在核对拦截配置"
            module.serviceConnected && module.hookPointCount > 0 -> "已保留上次拦截记录"
            module.serviceConnected -> "等待系统启动目标进程"
            !targetInstalled -> "未检测到 SystemHelper"
            else -> "请在 LSPosed 中启用 fucksh"
        }

    val subheadline: String
        get() = when {
            !guardEnabled -> "已暂停策略；历史拦截记录仍会保留"
            fullProtection -> "已确认 ${module.hookPointCount} 个拦截点，关闭管理界面后继续生效"
            module.error != null -> module.error
            module.targetState == "STALE" -> "更新模块后，需重启目标进程或设备以加载新版"
            module.online -> "框架已加载模块，正在读取配置与审计快照"
            module.serviceConnected -> "系统启动 SystemHelper 时自动加载，无需让管理界面常驻"
            !targetInstalled -> "当前设备未安装目标组件"
            else -> "需要 libxposed API 102 框架；应用不请求 Root 或 VPN 权限"
        }
            ?: "正在读取框架状态"
}
