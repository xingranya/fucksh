package com.fucksh.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 轻量且安全的 HTTP 客户端。
 *
 * 特性：
 * - 纯标准库实现，不引入 OkHttp 等大依赖
 * - 严格遵循 [HostAllowlist] 校验，重定向过程中每个跳转地址均做二次白名单过滤
 * - 具备连接超时、读取超时与总体大小上限保护
 * - 协程友好：可响应取消，取消时立即关闭底层流并删除不完整的临时文件
 * - 下载时平滑计算速度并分发进度回调
 */
object SafeHttpClient {

    private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_READ_TIMEOUT_MS = 30_000
    private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
    private const val MAX_REDIRECTS = 5
    private const val USER_AGENT = "fucksh-updater/2.6.0 (Android)"

    /** 默认安装包大小上限：100 MB。 */
    const val DEFAULT_MAX_APK_BYTES = 100L * 1024L * 1024L

    /**
     * 以 GET 请求拉取文本（用于拉取 update.json）。
     */
    suspend fun getUtf8(
        url: String,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
        maxBytes: Long = 2L * 1024L * 1024L,
    ): String = withContext(Dispatchers.IO) {
        HostAllowlist.verify(url)
        var currentUrl = url
        var redirects = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                this.requestMethod = "GET"
                this.instanceFollowRedirects = false
                this.setRequestProperty("User-Agent", USER_AGENT)
                this.setRequestProperty("Accept", "application/json, text/plain, */*")
            }

            try {
                val code = conn.responseCode
                if (code in 301..308) {
                    redirects++
                    if (redirects > MAX_REDIRECTS) {
                        throw UpdateException.Network("重定向次数过多（> $MAX_REDIRECTS）：$url")
                    }
                    val loc = conn.getHeaderField("Location")
                        ?: throw UpdateException.Network("HTTP $code 缺少 Location 头")
                    val nextUrl = URL(URL(currentUrl), loc).toString()
                    HostAllowlist.verify(nextUrl)
                    currentUrl = nextUrl
                    continue
                }

                if (code !in 200..299) {
                    throw UpdateException.Network("HTTP 错误 $code，地址：$currentUrl")
                }

                val len = conn.contentLengthLong
                if (len > maxBytes) {
                    throw UpdateException.TooLarge(len, maxBytes)
                }

                val stream = conn.inputStream
                val bytes = stream.use { input ->
                    val buf = ByteArray(8192)
                    var readTotal = 0L
                    val baos = java.io.ByteArrayOutputStream()
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        baos.write(buf, 0, n)
                        readTotal += n
                        if (readTotal > maxBytes) {
                            throw UpdateException.TooLarge(readTotal, maxBytes)
                        }
                    }
                    baos.toByteArray()
                }
                return@withContext bytes.toString(Charsets.UTF_8)
            } catch (e: SocketTimeoutException) {
                throw UpdateException.Timeout("请求超时：${e.message}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                throw e
            } catch (e: Exception) {
                throw UpdateException.Network("请求异常（$currentUrl）：${e.message}", e)
            } finally {
                conn.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw UpdateException.Network("未知重定向终态")
    }

    /**
     * 下载二进制文件至 [destFile]，并向 [onProgress] 回调进度。
     *
     * 响应协程取消：外部取消时会捕获并主动清空 destFile。
     */
    suspend fun downloadToFile(
        url: String,
        destFile: File,
        expectedSize: Long = 0L,
        maxBytes: Long = DEFAULT_MAX_APK_BYTES,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DOWNLOAD_READ_TIMEOUT_MS,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File = withContext(Dispatchers.IO) {
        HostAllowlist.verify(url)
        var currentUrl = url
        var redirects = 0

        // 确保上级目录存在
        destFile.parentFile?.mkdirs()
        if (destFile.exists()) {
            destFile.delete()
        }

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    this.connectTimeout = connectTimeoutMs
                    this.readTimeout = readTimeoutMs
                    this.requestMethod = "GET"
                    this.instanceFollowRedirects = false
                    this.setRequestProperty("User-Agent", USER_AGENT)
                    this.setRequestProperty("Accept", "*/*")
                }

                try {
                    val code = conn.responseCode
                    if (code in 301..308) {
                        redirects++
                        if (redirects > MAX_REDIRECTS) {
                            throw UpdateException.Network("重定向次数过多（> $MAX_REDIRECTS）：$url")
                        }
                        val loc = conn.getHeaderField("Location")
                            ?: throw UpdateException.Network("HTTP $code 缺少 Location 头")
                        val nextUrl = URL(URL(currentUrl), loc).toString()
                        HostAllowlist.verify(nextUrl)
                        currentUrl = nextUrl
                        continue
                    }

                    if (code !in 200..299) {
                        throw UpdateException.Network("下载失败，HTTP $code（$currentUrl）")
                    }

                    val headerLen = conn.contentLengthLong
                    val total = if (headerLen > 0L) headerLen else expectedSize.coerceAtLeast(0L)
                    if (total > maxBytes) {
                        throw UpdateException.TooLarge(total, maxBytes)
                    }

                    onProgress?.invoke(DownloadProgress(0L, total, 0L))

                    var downloaded = 0L
                    var lastTick = System.currentTimeMillis()
                    var lastBytes = 0L
                    var smoothedSpeed = 0L

                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                downloaded += n

                                if (downloaded > maxBytes) {
                                    throw UpdateException.TooLarge(downloaded, maxBytes)
                                }

                                val now = System.currentTimeMillis()
                                val span = now - lastTick
                                if (span >= 250L) {
                                    val instant = (downloaded - lastBytes) * 1000L / span.coerceAtLeast(1L)
                                    smoothedSpeed = if (smoothedSpeed <= 0L) instant else (smoothedSpeed * 3 + instant) / 4
                                    lastTick = now
                                    lastBytes = downloaded
                                    onProgress?.invoke(DownloadProgress(downloaded, total, smoothedSpeed))
                                }
                            }
                        }
                    }

                    // 最终交付 100% 进度
                    onProgress?.invoke(DownloadProgress(downloaded, if (total > 0L) total else downloaded, 0L))
                    return@withContext destFile
                } finally {
                    conn.disconnect()
                }
            }
            @Suppress("UNREACHABLE_CODE")
            destFile
        } catch (e: Exception) {
            // 清理残缺的临时文件
            if (destFile.exists()) {
                destFile.delete()
            }
            when (e) {
                is CancellationException -> throw e
                is UpdateException -> throw e
                is SocketTimeoutException -> throw UpdateException.Timeout("下载超时：${e.message}")
                else -> throw UpdateException.Network("下载异常（$currentUrl）：${e.message}", e)
            }
        }
    }
}
