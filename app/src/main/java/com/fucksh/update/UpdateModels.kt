package com.fucksh.update

/**
 * 应用内更新的数据模型。
 *
 * 更新链路只有两类数据：
 * - [UpdateInfo]：安装包更新信息，来源于远端 `update.json`。
 * - [RuleCatalog]：规则数据（域名清单 + 版本），来源���远端 `rules.json`，见 RuleCatalog.kt。
 */

/** 更新源：优先 CNB，失败后回退 GitHub。 */
enum class UpdateSource(val label: String) {
    CNB("CNB"),
    GITHUB("GitHub"),
}

/** 历史版本条目，用于展示更新日志。 */
data class VersionNote(
    val versionName: String,
    val versionCode: Int,
    val publishedAt: String,
    val size: Long,
    val notes: List<String>,
)

/** 一次可用的安装包更新。 */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val publishedAt: String,
    val size: Long,
    val sha256: String,
    val apkUrl: String,
    val changelog: String,
    val source: UpdateSource,
    val versions: List<VersionNote> = emptyList(),
)

/** 本机已安装版本。 */
data class InstalledVersion(
    val versionCode: Int,
    val versionName: String,
)

/** 下载进度：已下载字节、总量（未知时为 0）与实时速度。 */
data class DownloadProgress(
    val downloaded: Long,
    val total: Long,
    val bytesPerSec: Long,
) {
    /** 百分比；总量未知时返回 0。 */
    val percent: Int
        get() = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0

    /** 剩余字节数。 */
    val remaining: Long
        get() = (total - downloaded).coerceAtLeast(0L)
}
