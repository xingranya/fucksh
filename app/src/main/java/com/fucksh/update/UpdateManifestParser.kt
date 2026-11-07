package com.fucksh.update

import org.json.JSONObject

/**
 * update.json 清单解析器。
 *
 * 格式与 OneKey dist/update.json 保持完全同构：
 * ```json
 * {
 *   "versionCode": 201,
 *   "versionName": "2.19.3",
 *   "publishedAt": "2026-11-07T21:00:00+08:00",
 *   "size": 24228449,
 *   "sha256": "637afb35...",
 *   "apkUrl": "https://cnb.cool/.../fucksh-latest.apk",
 *   "changelog": "更新说明...",
 *   "versions": [
 *     { "versionName": "...", "versionCode": 201, "notes": [...] }
 *   ]
 * }
 * ```
 */
object UpdateManifestParser {

    /**
     * 将原始 JSON 文本解析为 [UpdateInfo]。
     *
     * @param jsonText update.json 的 UTF-8 字符串
     * @param source 来源渠道（CNB 或 GitHub）
     * @param fallbackApkUrl 若清单中未声明 apkUrl 时使用的兜底地址
     * @throws UpdateException.Manifest 若 JSON 格式错误或缺失核心字段
     */
    fun parse(jsonText: String, source: UpdateSource, fallbackApkUrl: String): UpdateInfo {
        val root = try {
            JSONObject(jsonText.trim())
        } catch (e: Exception) {
            throw UpdateException.Manifest("解析 update.json 失败：${e.message}", e)
        }

        val code = root.optInt("versionCode", -1)
        if (code <= 0) {
            throw UpdateException.Manifest("update.json 缺少有效的 versionCode: $code")
        }

        val versionName = root.optString("versionName", "").trim()
        val publishedAt = root.optString("publishedAt", "").trim()
        val size = root.optLong("size", 0L)
        val sha256 = root.optString("sha256", "").trim()
        val apkUrl = root.optString("apkUrl", "").trim().ifEmpty { fallbackApkUrl }
        val changelog = root.optString("changelog", "").trim()

        val versions = parseVersions(root)

        return UpdateInfo(
            versionCode = code,
            versionName = if (versionName.isNotEmpty()) versionName else "v$code",
            publishedAt = publishedAt,
            size = size,
            sha256 = sha256,
            apkUrl = apkUrl,
            changelog = changelog,
            source = source,
            versions = versions,
        )
    }

    private fun parseVersions(root: JSONObject): List<VersionNote> {
        val array = root.optJSONArray("versions") ?: return emptyList()
        val result = ArrayList<VersionNote>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val code = item.optInt("versionCode", 0)
            val name = item.optString("versionName", "")
            val publishedAt = item.optString("publishedAt", "")
            val size = item.optLong("size", 0L)

            val notes = ArrayList<String>()
            val notesArr = item.optJSONArray("notes")
            if (notesArr != null) {
                for (j in 0 until notesArr.length()) {
                    val line = notesArr.optString(j, "").trim()
                    if (line.isNotEmpty()) notes.add(line)
                }
            }
            if (notes.isEmpty()) {
                val cl = item.optString("changelog", "")
                cl.lines().forEach { raw ->
                    val t = raw.trim().removePrefix("-").trim()
                    if (t.isNotEmpty()) notes.add(t)
                }
            }

            result.add(
                VersionNote(
                    versionName = name,
                    versionCode = code,
                    publishedAt = publishedAt,
                    size = size,
                    notes = notes,
                ),
            )
        }
        return result.sortedByDescending { it.versionCode }
    }
}
