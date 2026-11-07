package com.fucksh.update

import org.json.JSONArray
import org.json.JSONObject

/**
 * 纯数据规则清单，与 APK 编译代码完全解耦。
 *
 * 架构声明与安全边界：
 * 1. 真正的底层拦截点位于 Hook 框架（libxposed/LSPosed/KernelSU）编译期注入的代码中，
 *    严禁且不可能通过下载可执行 DEX / SO 进行远程热更新（违反系统安全与沙盒规范）。
 * 2. 规则库热更新仅限于「纯文本/JSON」的域名清单与策略映射数据。
 * 3. [RuleCatalog] 具备严格的数据结构校验，任何未知字段或恶意注入均无法转化为可执行代码。
 */
data class RuleCatalog(
    val version: String,
    val updatedAt: String,
    val rules: List<DomainRuleEntry>,
)

/** 单条域名匹配规则。 */
data class DomainRuleEntry(
    val suffix: String,
    val policyKey: String,
    val category: String,
)

/**
 * 规则 JSON 解析器与结构合法性校验器。
 */
object RuleCatalogParser {

    /**
     * 解析规则清单 JSON。
     *
     * 规范格式：
     * ```json
     * {
     *   "version": "2026.11.08",
     *   "updatedAt": "2026-11-08T00:00:00+08:00",
     *   "rules": [
     *     { "suffix": "dm.wo.com.cn", "policyKey": "NET_ANALYTICS", "category": "NETWORK" },
     *     { "suffix": "adservice.google.com", "policyKey": "NET_AD_DOMAINS", "category": "NETWORK" }
     *   ]
     * }
     * ```
     */
    fun parse(jsonText: String): RuleCatalog {
        val root = try {
            JSONObject(jsonText.trim())
        } catch (e: Exception) {
            throw UpdateException.Manifest("规则 JSON 解析失败：${e.message}", e)
        }

        val version = root.optString("version", "").trim()
        if (version.isEmpty()) {
            throw UpdateException.Manifest("规则 JSON 缺少 version 字段")
        }

        val updatedAt = root.optString("updatedAt", "").trim()
        val array = root.optJSONArray("rules")
            ?: throw UpdateException.Manifest("规则 JSON 缺少 rules 数组")

        val list = ArrayList<DomainRuleEntry>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val suffix = item.optString("suffix", "").trim().lowercase()
            val policyKey = item.optString("policyKey", "").trim()
            val category = item.optString("category", "").trim()

            // 基础合法性过滤：域名后缀不可包含空格或非法字符
            if (suffix.isNotEmpty() && !suffix.contains("/") && !suffix.contains(" ")) {
                list.add(DomainRuleEntry(suffix, policyKey, category))
            }
        }

        return RuleCatalog(version = version, updatedAt = updatedAt, rules = list)
    }

    /** 将 [RuleCatalog] 序列化为规范 JSON 字符串。 */
    fun toJson(catalog: RuleCatalog): String {
        val root = JSONObject()
        root.put("version", catalog.version)
        root.put("updatedAt", catalog.updatedAt)
        val arr = JSONArray()
        for (r in catalog.rules) {
            val o = JSONObject()
            o.put("suffix", r.suffix)
            o.put("policyKey", r.policyKey)
            o.put("category", r.category)
            arr.put(o)
        }
        root.put("rules", arr)
        return root.toString(2)
    }
}
