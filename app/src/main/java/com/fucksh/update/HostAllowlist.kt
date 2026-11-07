package com.fucksh.update

import java.net.URI
import java.util.Locale

/**
 * 严格的 HTTPS Host 校验器。
 *
 * 仅允许：
 * 1. CNB 官方域及其资产/API 域：
 *    - `cnb.cool`
 *    - `api.cnb.cool`
 *    - `asset.cnb.cool`
 *    - `*.cnb.cool`
 * 2. GitHub 官方域及其 Releases / Assets CDN 域：
 *    - `github.com`
 *    - `api.github.com`
 *    - `raw.githubusercontent.com`
 *    - `objects.githubusercontent.com`
 *    - `release-assets.githubusercontent.com`
 *    - `github-production-release-asset-*.s3.amazonaws.com`
 *    - `github-releases.githubusercontent.com`
 *
 * 任何明文 HTTP、内网 IP、未授权第三方镜像均被拒绝。
 */
object HostAllowlist {

    private val CNB_SUFFIXES = listOf(
        "cnb.cool",
    )

    private val GITHUB_EXACT_HOSTS = setOf(
        "github.com",
        "api.github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com",
        "release-assets.githubusercontent.com",
        "github-releases.githubusercontent.com",
        "github-production-release-asset.s3.amazonaws.com",
    )

    private val GITHUB_SUFFIXES = listOf(
        ".githubusercontent.com",
        ".github.com",
    )

    /**
     * 校验 URL 是否合法（必须 HTTPS + 在白名单域名内）。
     *
     * @throws UpdateException.HostBlocked 若协议不是 HTTPS 或 host 不在白名单
     */
    fun verify(url: String) {
        val uri = try {
            URI.create(url.trim())
        } catch (e: Exception) {
            throw UpdateException.HostBlocked(url)
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "https") {
            throw UpdateException.HostBlocked(url)
        }

        val host = uri.host?.lowercase(Locale.US) ?: throw UpdateException.HostBlocked(url)
        if (!isAllowedHost(host)) {
            throw UpdateException.HostBlocked(url)
        }
    }

    /** 是否属于受信任的 Host。 */
    fun isAllowedHost(host: String): Boolean {
        val lower = host.lowercase(Locale.US)

        // 1. CNB 域名体系
        if (lower == "cnb.cool" || lower.endsWith(".cnb.cool")) {
            return true
        }

        // 2. GitHub 官方与 CDN 体系
        if (GITHUB_EXACT_HOSTS.contains(lower)) {
            return true
        }
        for (suffix in GITHUB_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true
            }
        }
        // GitHub release asset 托管在 AWS S3 的特定前缀桶
        if (lower.startsWith("github-production-release-asset") && lower.endsWith(".s3.amazonaws.com")) {
            return true
        }

        return false
    }
}
