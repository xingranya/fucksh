package com.fucksh.data

/**
 * 内置域名规则库。
 *
 * 规则按「后缀匹配」生效：`track.analytics.oppo.com` 会命中 `analytics.oppo.com`。
 * 每条规则绑定一个策略开关，关闭开关即从拦截集合中移除。
 */
object RuleRepository {

    /** 规则库版本，展示在关于页与规则更新检查中。 */
    const val VERSION = "2026.11.07"

    data class RuleMatch(
        val domain: String,
        val rule: String,
        val policy: PolicyKey,
        val category: LogCategory,
    )

    private data class Rule(
        val suffix: String,
        val policy: PolicyKey,
        val category: LogCategory,
    )

    private val rules: List<Rule> = listOf(
        // 网络出口：联通 DM 上报与统计域名
        Rule("dm.wo.com.cn", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("dm.10010.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("analytics.oppo.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("track.analytics.oppo.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("data.oppo.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("stat.oppo.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("statistics.oppo.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("metrics.oplus.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("sdk.oplus.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),
        Rule("data.oplus.com", PolicyKey.NET_ANALYTICS, LogCategory.NETWORK),

        // 广告与开屏画像
        Rule("adservice.google.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("googleads.g.doubleclick.net", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("pagead2.googlesyndication.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("ad.doubleclick.net", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("ads.oppo.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("adx.oppo.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("tracking.oppomobile.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("applovin.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),
        Rule("unityads.unity3d.com", PolicyKey.NET_AD_DOMAINS, LogCategory.NETWORK),

        // DoH 探测
        Rule("dns.google", PolicyKey.NET_DOH, LogCategory.NETWORK),
        Rule("cloudflare-dns.com", PolicyKey.NET_DOH, LogCategory.NETWORK),
        Rule("doh.pub", PolicyKey.NET_DOH, LogCategory.NETWORK),
        Rule("dns.alidns.com", PolicyKey.NET_DOH, LogCategory.NETWORK),
        Rule("doh.360.cn", PolicyKey.NET_DOH, LogCategory.NETWORK),
        Rule("dns.nextdns.io", PolicyKey.NET_DOH, LogCategory.NETWORK),

        // 遥测与身份标识
        Rule("oaid.oppo.com", PolicyKey.TEL_OAID, LogCategory.TELEMETRY),
        Rule("id.oppo.com", PolicyKey.TEL_OAID, LogCategory.TELEMETRY),
        Rule("deviceid.oppo.com", PolicyKey.TEL_OAID, LogCategory.TELEMETRY),
        Rule("tracker.oppo.com", PolicyKey.TEL_OAID, LogCategory.TELEMETRY),
        Rule("log.oppo.com", PolicyKey.TEL_SYS_LOG, LogCategory.TELEMETRY),
        Rule("logs.oppo.com", PolicyKey.TEL_SYS_LOG, LogCategory.TELEMETRY),
        Rule("telemetry.oppo.com", PolicyKey.TEL_SYS_LOG, LogCategory.TELEMETRY),
        Rule("dc.oppo.com", PolicyKey.TEL_SYS_LOG, LogCategory.TELEMETRY),

        // 定位
        Rule("lbs.oppo.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),
        Rule("location.oppo.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),
        Rule("geo.oppo.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),
        Rule("lbs.amap.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),
        Rule("restapi.amap.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),
        Rule("api.map.baidu.com", PolicyKey.LOC_FUSED, LogCategory.LOCATION),

        // 落库与指纹
        Rule("fingerprint.oppo.com", PolicyKey.STORE_SANDBOX, LogCategory.STORAGE),
        Rule("fp.oppo.com", PolicyKey.STORE_SANDBOX, LogCategory.STORAGE),
        Rule("sandbox.oppo.com", PolicyKey.STORE_SANDBOX, LogCategory.STORAGE),
    )

    val ruleCount: Int get() = rules.size

    /** 返回命中的规则；未命中或对应开关关闭时返回 null。 */
    fun match(domain: String, policies: Map<PolicyKey, Boolean>): RuleMatch? {
        val normalized = normalize(domain)
        if (normalized.isEmpty()) return null
        val rule = rules.firstOrNull { normalized == it.suffix || normalized.endsWith(".${it.suffix}") } ?: return null
        if (policies[rule.policy] != true) return null
        return RuleMatch(normalized, rule.suffix, rule.policy, rule.category)
    }

    /** 目标应用的固定上报域名，用于总开关下的强制保护。 */
    fun isCoreTarget(domain: String): Boolean {
        val normalized = normalize(domain)
        return normalized == "dm.wo.com.cn" || normalized.endsWith(".dm.wo.com.cn")
    }

    fun normalize(domain: String): String = domain.trim().trimEnd('.').lowercase()
}
