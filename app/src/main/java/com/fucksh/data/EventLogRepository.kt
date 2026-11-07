package com.fucksh.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 事件日志仓库：内存 StateFlow + 追加式文件持久化。
 *
 * 文件采用 JSON Lines，最多保留 [MAX_ENTRIES] 条，超过 256KB 时自动重写。
 */
class EventLogRepository(
    private val file: File,
    private val scope: CoroutineScope,
) {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val idGenerator = AtomicLong(System.currentTimeMillis())

    init {
        scope.launch(Dispatchers.IO) { load() }
    }

    fun nextId(): Long = idGenerator.incrementAndGet()

    fun append(entry: LogEntry) {
        _logs.update { current ->
            if (current.any { it.id == entry.id }) current else (listOf(entry) + current).take(MAX_ENTRIES)
        }
        scope.launch(Dispatchers.IO) { persistAppend(entry) }
    }

    fun appendAll(entries: List<LogEntry>) {
        if (entries.isEmpty()) return
        _logs.update { current ->
            val known = current.mapTo(HashSet()) { it.id }
            (entries.filterNot { it.id in known } + current).take(MAX_ENTRIES)
        }
        scope.launch(Dispatchers.IO) {
            entries.forEach { persistAppend(it) }
        }
    }

    fun clear() {
        _logs.value = emptyList()
        scope.launch(Dispatchers.IO) {
            runCatching { file.writeText("") }
        }
    }

    /** 导出为可读文本，返回导出的条数。 */
    fun export(target: File): Int {
        val snapshot = _logs.value
        target.writeText(buildString {
            appendLine("# fucksh 审计导出 · ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("# 共 ${snapshot.size} 条")
            snapshot.sortedByDescending { it.timeMillis }.forEach { entry ->
                appendLine(
                    "${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(entry.timeMillis))}" +
                        "  [${entry.category.badge}]  ${entry.target}  ${entry.action}",
                )
            }
        })
        return snapshot.size
    }

    private fun load() {
        runCatching {
            if (!file.exists()) return@runCatching
            val entries = file.readLines()
                .asSequence()
                .mapNotNull { line -> runCatching { parse(line) }.getOrNull() }
                .sortedByDescending { it.timeMillis }
                .take(MAX_ENTRIES)
                .toList()
            if (entries.isNotEmpty()) {
                _logs.value = entries
                idGenerator.set(maxOf(idGenerator.get(), entries.maxOf { it.id }))
            }
        }
    }

    private fun persistAppend(entry: LogEntry) {
        runCatching {
            file.parentFile?.mkdirs()
            file.appendText(entry.toJson().toString() + "\n")
            if (file.length() > MAX_FILE_BYTES) {
                file.writeText(_logs.value.take(MAX_ENTRIES).joinToString("\n") { it.toJson().toString() } + "\n")
            }
        }
    }

    private fun parse(line: String): LogEntry {
        val json = JSONObject(line)
        return LogEntry(
            id = json.getLong("id"),
            category = LogCategory.valueOf(json.getString("category")),
            target = json.getString("target"),
            action = json.getString("action"),
            timeMillis = json.getLong("time"),
            source = runCatching { LogSource.valueOf(json.getString("source")) }.getOrDefault(LogSource.LOCAL),
        )
    }

    private fun LogEntry.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category.name)
        put("target", target)
        put("action", action)
        put("time", timeMillis)
        put("source", source.name)
    }

    companion object {
        const val MAX_ENTRIES = 300
        private const val MAX_FILE_BYTES = 256 * 1024L
    }
}
