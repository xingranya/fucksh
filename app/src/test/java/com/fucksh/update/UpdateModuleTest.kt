package com.fucksh.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * 针对 Update 模块的独立单元测试。
 */
class UpdateModuleTest {

    @Test
    fun testHostAllowlistAccepted() {
        val validUrls = listOf(
            "https://cnb.cool/xingranya/fucksh/-/releases/download/update/update.json",
            "https://api.cnb.cool/releases/123",
            "https://asset.cnb.cool/assets/t/0146bezzPJMUUMw5ReYVag0BSxB",
            "https://static.cnb.cool/cdn/test.apk",
            "https://github.com/xingranya/fucksh/releases/download/update/update.json",
            "https://api.github.com/repos/xingranya/fucksh",
            "https://raw.githubusercontent.com/xingranya/fucksh/main/dist/update.json",
            "https://objects.githubusercontent.com/github-production-release-asset/12345",
            "https://release-assets.githubusercontent.com/github-production-release-asset/3432266/file.zip",
            "https://github-production-release-asset.s3.amazonaws.com/12345/abc",
        )

        for (url in validUrls) {
            try {
                HostAllowlist.verify(url)
            } catch (e: Exception) {
                fail("URL 应该被允许但被拦截了: $url, error: ${e.message}")
            }
        }
    }

    @Test
    fun testHostAllowlistBlocked() {
        val invalidUrls = listOf(
            "http://cnb.cool/test.apk", // 明文 HTTP 必须拒绝
            "http://github.com/test.apk",
            "https://evil.com/fake-update.apk",
            "https://cnb.cool.evil.com/test.apk",
            "https://127.0.0.1/test.apk",
            "https://192.168.1.100/test.apk",
            "ftp://github.com/test.apk",
            "https:///no-host",
            "invalid-url-string",
        )

        for (url in invalidUrls) {
            try {
                HostAllowlist.verify(url)
                fail("URL 应该被拦截但通过了: $url")
            } catch (e: UpdateException.HostBlocked) {
                // Expected
            }
        }
    }

    @Test
    fun testParseUpdateManifest() {
        val json = """
            {
              "versionCode": 260,
              "versionName": "2.6.0",
              "publishedAt": "2026-11-07T21:00:00+08:00",
              "size": 15420110,
              "sha256": "abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890",
              "apkUrl": "https://cnb.cool/xingranya/fucksh/-/releases/download/update/fucksh-latest.apk",
              "changelog": "v2.6.0 深度更新\n- 核心防护模块优化",
              "versions": [
                {
                  "versionName": "2.6.0",
                  "versionCode": 260,
                  "publishedAt": "2026-11-07T21:00:00+08:00",
                  "size": 15420110,
                  "notes": [
                    "核心防护模块优化",
                    "全新 Miuix 架构"
                  ]
                }
              ]
            }
        """.trimIndent()

        val info = UpdateManifestParser.parse(
            jsonText = json,
            source = UpdateSource.CNB,
            fallbackApkUrl = "https://cnb.cool/fallback.apk",
        )

        assertEquals(260, info.versionCode)
        assertEquals("2.6.0", info.versionName)
        assertEquals(15420110L, info.size)
        assertEquals("abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890", info.sha256)
        assertEquals("https://cnb.cool/xingranya/fucksh/-/releases/download/update/fucksh-latest.apk", info.apkUrl)
        assertEquals(UpdateSource.CNB, info.source)
        assertEquals(1, info.versions.size)
        assertEquals("2.6.0", info.versions[0].versionName)
        assertEquals(2, info.versions[0].notes.size)
    }

    @Test
    fun testComputeSha256() {
        val temp = File.createTempFile("sha_test", ".bin")
        try {
            FileOutputStream(temp).use { it.write("Hello Fucksh Updater".toByteArray(Charsets.UTF_8)) }
            val sha = ApkVerifier.computeSha256(temp)
            assertNotNull(sha)
            assertEquals(64, sha.length)
        } finally {
            temp.delete()
        }
    }

    @Test
    fun testParseRuleCatalog() {
        val json = """
            {
              "version": "2026.11.08",
              "updatedAt": "2026-11-08T00:00:00+08:00",
              "rules": [
                { "suffix": "dm.wo.com.cn", "policyKey": "NET_ANALYTICS", "category": "NETWORK" },
                { "suffix": "adservice.google.com", "policyKey": "NET_AD_DOMAINS", "category": "NETWORK" }
              ]
            }
        """.trimIndent()

        val catalog = RuleCatalogParser.parse(json)
        assertEquals("2026.11.08", catalog.version)
        assertEquals(2, catalog.rules.size)
        assertEquals("dm.wo.com.cn", catalog.rules[0].suffix)

        val serialized = RuleCatalogParser.toJson(catalog)
        assertTrue(serialized.contains("dm.wo.com.cn"))
        assertTrue(serialized.contains("2026.11.08"))
    }

    @Test
    fun testDownloadProgressCalculation() {
        val p1 = DownloadProgress(downloaded = 50L, total = 100L, bytesPerSec = 1024L)
        assertEquals(50, p1.percent)
        assertEquals(50L, p1.remaining)

        val p2 = DownloadProgress(downloaded = 0L, total = 0L, bytesPerSec = 0L)
        assertEquals(0, p2.percent)
        assertEquals(0L, p2.remaining)
    }
}
