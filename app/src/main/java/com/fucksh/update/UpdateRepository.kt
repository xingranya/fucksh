package com.fucksh.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 核心更新仓库层。
 *
 * 职责与约束：
 * 1. 提供对外的最简 API：
 *    - `suspend fun check(): UpdateInfo?`
 *    - `suspend fun download(info: UpdateInfo, onProgress: ((DownloadProgress) -> Unit)?): File`
 *    - `fun verify(apk: File, expectedSha256: String): Boolean`
 *    - `fun createInstallIntent(apk: File): Intent`
 * 2. 调度策略：CNB 优先，失败自动 fallback 至 GitHub。
 * 3. 严格的安全保障：
 *    - 仅允许受信 HTTPS 域名（CNB 官方与实际 asset 域、GitHub 官方与 release 资产 CDN）
 *    - 临时文件严格落于 App 内部私有缓存目录（`context.cacheDir/updates/`）
 *    - 完成后执行 SHA-256、包名与签名证书一致性三重校验
 * 4. 无常驻后台任务，仅在用户/界面显式触发时执行。
 */
class UpdateRepository(
    private val context: Context,
    private val verifier: ApkVerifier = ApkVerifier(context),
    private val cnbManifestUrl: String = DEFAULT_CNB_MANIFEST,
    private val githubManifestUrl: String = DEFAULT_GITHUB_MANIFEST,
    private val maxApkBytes: Long = SafeHttpClient.DEFAULT_MAX_APK_BYTES,
) {

    companion object {
        const val DEFAULT_CNB_BASE = "https://cnb.cool/xingranya/fucksh/-/releases/download/update"
        const val DEFAULT_CNB_MANIFEST = "$DEFAULT_CNB_BASE/update.json"
        const val DEFAULT_CNB_APK = "$DEFAULT_CNB_BASE/fucksh-latest.apk"

        const val DEFAULT_GITHUB_BASE = "https://github.com/xingranya/fucksh/releases/download/update"
        const val DEFAULT_GITHUB_MANIFEST = "$DEFAULT_GITHUB_BASE/update.json"
        const val DEFAULT_GITHUB_APK = "$DEFAULT_GITHUB_BASE/fucksh-latest.apk"

        private const val UPDATES_DIR_NAME = "updates"
        private const val APK_NAME_PREFIX = "fucksh-update"
    }

    /** 读取当前宿主版本。 */
    @Suppress("DEPRECATION")
    fun getInstalledVersion(): InstalledVersion {
        val pm = context.packageManager
        val pInfo = pm.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (pInfo.longVersionCode and 0x7FFFFFFF).toInt()
        } else {
            pInfo.versionCode
        }
        return InstalledVersion(
            versionCode = code,
            versionName = pInfo.versionName ?: "v$code",
        )
    }

    /**
     * 检查更新（挂起函数）：
     * 优先探测 CNB 渠道，网络不可达或出错时平滑回退 GitHub。
     * 若远端 versionCode <= 本机已安装版本，则返回 null。
     *
     * @return 若有更高版本则返回 [UpdateInfo]，否则返回 null
     * @throws UpdateException 当两个源均明确校验失败且不是普通的 404/无更新时
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val installed = getInstalledVersion()
        val currentCode = installed.versionCode

        // 1. 尝试 CNB 优先
        var lastError: Exception? = null
        val cnbInfo = try {
            val json = SafeHttpClient.getUtf8(cnbManifestUrl)
            UpdateManifestParser.parse(json, UpdateSource.CNB, DEFAULT_CNB_APK)
        } catch (e: Exception) {
            lastError = e
            null
        }

        if (cnbInfo != null) {
            return@withContext if (cnbInfo.versionCode > currentCode) cnbInfo else null
        }

        // 2. Fallback 至 GitHub
        val ghInfo = try {
            val json = SafeHttpClient.getUtf8(githubManifestUrl)
            UpdateManifestParser.parse(json, UpdateSource.GITHUB, DEFAULT_GITHUB_APK)
        } catch (e: Exception) {
            lastError = e
            null
        }

        if (ghInfo != null) {
            return@withContext if (ghInfo.versionCode > currentCode) ghInfo else null
        }

        // 两个源均不可达
        if (lastError != null) {
            throw UpdateException.Network(
                "检查更新失败（CNB 与 GitHub 均不可达）：${lastError.message}",
                lastError,
            )
        }
        null
    }

    /**
     * 下载安装包到应用内部私有缓存目录（挂起函数，支持协程取消与实时进度）。
     *
     * 下载完成后，会自动执行 [ApkVerifier] 的三重安全校验（SHA-256、包名、签名一致性）。
     * 若校验失败，会立即抹除已下载文件并抛出对应 [UpdateException]。
     *
     * @param info 更新信息
     * @param onProgress 进度回调 (0-100%, downloaded, total, speed)
     * @return 校验通过的本地 APK 文件
     */
    suspend fun download(
        info: UpdateInfo,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        // 确保下载地址在白名单内
        HostAllowlist.verify(info.apkUrl)

        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME).apply { mkdirs() }
        val targetApk = File(updatesDir, "$APK_NAME_PREFIX-${info.versionCode}.apk")

        // 启动安全下载
        SafeHttpClient.downloadToFile(
            url = info.apkUrl,
            destFile = targetApk,
            expectedSize = info.size,
            maxBytes = maxApkBytes,
            onProgress = onProgress,
        )

        // 严格安全校验：若校验未通过，抹除该文件并抛出异常
        try {
            verifier.verify(targetApk, info.sha256)
        } catch (e: Throwable) {
            if (targetApk.exists()) {
                targetApk.delete()
            }
            throw e
        }

        targetApk
    }

    /**
     * 便捷校验接口。
     */
    fun verify(apk: File, expectedSha256: String): Boolean {
        return try {
            verifier.verify(apk, expectedSha256)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 生成系统安装器所需的 Intent（配合 FileProvider 授权）。
     */
    fun createInstallIntent(apk: File): Intent {
        val uri: Uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
        } catch (e: Exception) {
            Uri.fromFile(apk)
        }

        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 清理过期的历史下载包，释放存储空间。
     */
    fun cleanOldDownloads() {
        val updatesDir = File(context.cacheDir, UPDATES_DIR_NAME)
        if (!updatesDir.exists()) return
        updatesDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith(APK_NAME_PREFIX)) {
                file.delete()
            }
        }
    }
}
