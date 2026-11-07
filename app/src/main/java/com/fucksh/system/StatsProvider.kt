package com.fucksh.system

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.fucksh.FuckshApplication
import com.fucksh.data.LogCategory
import com.fucksh.data.LogEntry
import com.fucksh.data.LogSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 接收被注入进程（com.mobiletools.systemhelper）审计上报与状态同步的 ContentProvider。
 *
 * <p>安全与性能保证：</p>
 * <ul>
 *   <li><b>Caller UID 校验</b>：严格核验 Binder 调用方真实 UID，仅放行目标应用与本应用调用，
 *       拦截未授权伪造上报。</li>
 *   <li><b>前台批量拉取</b>：支持 {@code sync} 批量导入累积事件与全量计数对齐，杜绝模块拦截高频 IPC。</li>
 *   <li><b>审计持久化联动</b>：全量与增量计数自动写回 Preferences 与 DataStore，保证重启不丢计数。</li>
 * </ul>
 */
class StatsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (extras == null) return null

        // 1. 严格核验调用方 UID，防止任意第三方 App 伪造拦截统计
        val callingUid = Binder.getCallingUid()
        val myUid = Process.myUid()
        val targetUid = runCatching {
            context?.packageManager?.getApplicationInfo(ModuleService.TARGET_PACKAGE, 0)?.uid
        }.getOrNull()

        if (callingUid != myUid && (targetUid == null || callingUid != targetUid)) {
            Log.w(TAG, "Rejected unauthorized call: method=$method, callerUid=$callingUid, expectedTargetUid=$targetUid, myUid=$myUid")
            return Bundle().apply { putBoolean("ok", false) }
        }

        val app = runCatching { FuckshApplication.instance }.getOrNull() ?: return null
        val container = app.container

        when (method) {
            "sync" -> {
                // 1) 提取批量事件
                val rawEvents = parseEvents(extras)
                if (rawEvents.isNotEmpty()) {
                    val entries = ArrayList<LogEntry>(rawEvents.size)
                    val incrementalCounts = mutableMapOf<LogCategory, Long>()

                    for (b in rawEvents) {
                        val rule = b.getString("rule") ?: "sh_rule"
                        val layer = b.getString("layer") ?: "防护"
                        val detail = b.getString("detail") ?: ""
                        val time = b.getLong("time", System.currentTimeMillis())

                        val category = when (layer) {
                            "网络" -> LogCategory.NETWORK
                            "定位" -> LogCategory.LOCATION
                            "落库" -> LogCategory.STORAGE
                            else -> LogCategory.TELEMETRY
                        }
                        incrementalCounts[category] = (incrementalCounts[category] ?: 0L) + 1L

                        entries.add(
                            LogEntry(
                                id = container.eventLog.nextId(),
                                category = category,
                                target = "SystemHelper",
                                action = if (detail.isNotBlank()) detail else rule,
                                timeMillis = time,
                                source = LogSource.MODULE,
                            )
                        )
                    }

                    container.eventLog.appendAll(entries)
                }

                // 2) 提取模块侧持久化全量计数并对齐
                val countsBundle = extras.getBundle("counts")
                if (countsBundle != null) {
                    val total = countsBundle.getLong("total", 0L)
                    val net = countsBundle.getLong("net", 0L)
                    val loc = countsBundle.getLong("loc", 0L)
                    val str = countsBundle.getLong("str", 0L)
                    val tel = countsBundle.getLong("tel", 0L)

                    val fullCounts = mapOf(
                        LogCategory.NETWORK to net,
                        LogCategory.LOCATION to loc,
                        LogCategory.STORAGE to str,
                        LogCategory.TELEMETRY to tel,
                    )
                    container.moduleService.alignCounts(fullCounts, total)
                }

                // 3) 提取 Hook 安装点
                val hookCount = extras.getInt("hookCount", 0)
                if (hookCount > 0) {
                    val pointsBundle = extras.getBundle("points")
                    val dynamic = extras.getBoolean("dynamic", false)
                    val note = extras.getString("note") ?: ""
                    val map = buildMap<String, List<String>> {
                        pointsBundle?.keySet()?.forEach { k ->
                            val lines = pointsBundle.getString(k)?.split('\n')?.filter { it.isNotBlank() }
                            if (lines != null) put(k, lines)
                        }
                    }
                    container.moduleService.recordPoints(map, hookCount, dynamic, note)
                }

                // 4) 提取环境
                val framework = extras.getString("framework")
                val version = extras.getString("frameworkVersion")
                if (!framework.isNullOrBlank()) {
                    container.moduleService.recordEnvironment(framework, version)
                }

                // 5) 提取配置确认时间戳
                val configTimestamp = extras.getLong("configTimestamp", 0L)
                if (configTimestamp > 0L) {
                    container.moduleService.recordConfigApplied(configTimestamp)
                }

                return Bundle().apply { putBoolean("ok", true) }
            }

            "hit" -> {
                val rawEvents = parseEvents(extras)
                if (rawEvents.isNotEmpty()) {
                    val entries = ArrayList<LogEntry>(rawEvents.size)
                    val counts = mutableMapOf<LogCategory, Long>()

                    for (b in rawEvents) {
                        val rule = b.getString("rule") ?: "sh_rule"
                        val layer = b.getString("layer") ?: "防护"
                        val detail = b.getString("detail") ?: ""
                        val time = b.getLong("time", System.currentTimeMillis())

                        val category = when (layer) {
                            "网络" -> LogCategory.NETWORK
                            "定位" -> LogCategory.LOCATION
                            "落库" -> LogCategory.STORAGE
                            else -> LogCategory.TELEMETRY
                        }
                        counts[category] = (counts[category] ?: 0L) + 1L

                        entries.add(
                            LogEntry(
                                id = container.eventLog.nextId(),
                                category = category,
                                target = "SystemHelper",
                                action = if (detail.isNotBlank()) detail else rule,
                                timeMillis = time,
                                source = LogSource.MODULE,
                            )
                        )
                    }

                    container.eventLog.appendAll(entries)
                    container.moduleService.recordHits(counts)
                }
                return Bundle().apply { putBoolean("ok", true) }
            }

            "points" -> {
                val pointsBundle = extras.getBundle("points")
                val hookCount = extras.getInt("hookCount", 0)
                val dynamic = extras.getBoolean("dynamic", false)
                val note = extras.getString("note") ?: ""
                val map = buildMap<String, List<String>> {
                    pointsBundle?.keySet()?.forEach { k ->
                        val lines = pointsBundle.getString(k)?.split('\n')?.filter { it.isNotBlank() }
                        if (lines != null) put(k, lines)
                    }
                }
                container.moduleService.recordPoints(map, hookCount, dynamic, note)
                return Bundle().apply { putBoolean("ok", true) }
            }

            "env" -> {
                val framework = extras.getString("framework")
                val version = extras.getString("frameworkVersion")
                container.moduleService.recordEnvironment(framework, version)
                return Bundle().apply { putBoolean("ok", true) }
            }
        }
        return Bundle().apply { putBoolean("ok", false) }
    }

    private fun parseEvents(extras: Bundle): List<Bundle> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArrayList("events", Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArrayList("events")
        } ?: emptyList()
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val TAG = "StatsProvider"
    }
}
