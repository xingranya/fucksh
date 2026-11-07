package com.fucksh.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * APK 安全与完整性校验器。
 *
 * 在安装前执行三项必验：
 * 1. SHA-256 校验：下载文件的哈希值必须与清单严格一致。
 * 2. Android 包名校验：必须为 [EXPECTED_PACKAGE_NAME]（com.fucksh），杜绝下错或恶意篡改包名。
 * 3. 签名证书一致性：新 APK 的签名公钥 SHA-256 摘要必须与当前宿主 App 的签名完全一致。
 */
class ApkVerifier(private val context: Context) {

    companion object {
        const val EXPECTED_PACKAGE_NAME = "com.fucksh"

        /** 计算文件的 SHA-256（小写 hex）。 */
        fun computeSha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    /**
     * 针对指定 APK 运行全量校验。
     *
     * @param apk 已下载的 APK 文件
     * @param expectedSha256 期望的 SHA-256（小写或大写 hex 均可，空串则跳过 SHA 比较，但在严格模式下必须提供）
     * @return 校验通过的 [PackageInfo]
     * @throws UpdateException 任何一项校验未通过时抛出明确异常
     */
    fun verify(apk: File, expectedSha256: String): PackageInfo {
        if (!apk.exists() || !apk.isFile) {
            throw UpdateException.Unverified("安装包文件不存在：${apk.absolutePath}")
        }

        // 1. SHA-256 校验
        val actualSha256 = computeSha256(apk)
        val expectedClean = expectedSha256.trim().lowercase(Locale.US)
        if (expectedClean.isNotEmpty() && actualSha256 != expectedClean) {
            throw UpdateException.ChecksumMismatch(expectedClean, actualSha256)
        }

        // 2. APK 解析与包名校验
        val pm = context.packageManager
        val archiveInfo = parseArchive(pm, apk)
            ?: throw UpdateException.Unverified("无法解析安装包，文件可能损坏或不是合法的 Android APK")

        val actualPackage = archiveInfo.packageName
        if (actualPackage != EXPECTED_PACKAGE_NAME) {
            throw UpdateException.PackageMismatch(EXPECTED_PACKAGE_NAME, actualPackage)
        }

        // 3. 签名证书校验（与当前已运行应用比对）
        verifySignatureMatchesInstalled(pm, archiveInfo)

        return archiveInfo
    }

    /** 解析待安装的 APK 的 PackageInfo（包含签名）。 */
    @Suppress("DEPRECATION")
    private fun parseArchive(pm: PackageManager, apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageArchiveInfo(apk.absolutePath, flags)
    }

    /** 读取当前宿主 APK 的 PackageInfo（包含签名）。 */
    @Suppress("DEPRECATION")
    private fun getInstalledPackageInfo(pm: PackageManager): PackageInfo {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        return pm.getPackageInfo(context.packageName, flags)
    }

    /** 比对 APK 与已安装宿主的签名是否完全一致。 */
    @Suppress("DEPRECATION")
    private fun verifySignatureMatchesInstalled(pm: PackageManager, archiveInfo: PackageInfo) {
        val installedInfo = try {
            getInstalledPackageInfo(pm)
        } catch (e: Exception) {
            // 测试环境或极端情况下可能未安装自身（如单元测试），退化为报错提示
            throw UpdateException.Unverified("无法读取当前应用签名：${e.message}", e)
        }

        val installedSignatures = extractSignatures(installedInfo)
        val archiveSignatures = extractSignatures(archiveInfo)

        if (installedSignatures.isEmpty()) {
            throw UpdateException.Unverified("无法提取当前应用签名证书")
        }
        if (archiveSignatures.isEmpty()) {
            throw UpdateException.Unverified("无法提取安装包签名证书，可能未签名")
        }

        // 证书 SHA-256 集合必须相交且一致
        val installedDigests = installedSignatures.map { sha256Bytes(it) }.toSet()
        val archiveDigests = archiveSignatures.map { sha256Bytes(it) }.toSet()

        if (installedDigests != archiveDigests) {
            val expected = installedDigests.joinToString(",")
            val actual = archiveDigests.joinToString(",")
            throw UpdateException.SignatureMismatch(expected, actual)
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSignatures(info: PackageInfo): List<ByteArray> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyList()
            if (signingInfo.hasMultipleSigners()) {
                return signingInfo.apkContentsSigners.map { it.toByteArray() }
            }
            return signingInfo.signingCertificateHistory.map { it.toByteArray() }
        } else {
            val sigs = info.signatures ?: return emptyList()
            return sigs.map { it.toByteArray() }
        }
    }

    private fun sha256Bytes(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
