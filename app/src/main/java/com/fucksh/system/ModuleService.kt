package com.fucksh.system

import android.content.Context
import com.fucksh.data.BlockerMode
import com.fucksh.data.BlockerStats
import com.fucksh.data.LogCategory
import com.fucksh.data.ModuleStatus
import com.fucksh.data.PolicyKey
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 与 libxposed 服务的管理端桥接层。
 *
 * <p>核心职责：</p>
 * <ol>
 *   <li><b>配置下发</b>：通过 {@code XposedService.getRemotePreferences("fucksh")} 将 UI 策略
 *       直接写入框架 RemotePreferences，双向实时同步，无需任何配置文件。</li>
 *   <li><b>状态判定</b>：严格区分四大正交状态，绝不伪造激活：
 *       框架确认（UP_TO_DATE）、方法安装点数、拦截事件计数、策略生效确认。</li>
 *   <li><b>零唤醒审计拉取</b>：通过 {@code XposedService.openRemoteFile} 在管理端前台时
 *       按需拉取目标主进程写入的审计快照，模块侧永不反向唤醒管理器。</li>
 * </ol>
 *
 * <p>审计快照必须通过 pid 与 loadedVersion 校验后才被采纳为「本进程已安装」，
 * 避免旧进程残留快照被误判为当前生效。</p>
 */
class ModuleService(private val context: Context) : XposedServiceHelper.OnServiceListener {

    private val scope = CoroutineScope(Dispatchers.IO)

    private val _service = MutableStateFlow<XposedService?>(null)
    val service: StateFlow<XposedService?> = _service.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _frameworkConfirmed = MutableStateFlow(false)
    val frameworkConfirmed: StateFlow<Boolean> = _frameworkConfirmed.asStateFlow()

    private val _targetHooked = MutableStateFlow(false)
    val targetHooked: StateFlow<Boolean> = _targetHooked.asStateFlow()

    private val _targetState = MutableStateFlow<String?>(null)
    val targetState: StateFlow<String?> = _targetState.asStateFlow()

    private val _hookPointCount = MutableStateFlow(0)
    val hookPointCount: StateFlow<Int> = _hookPointCount.asStateFlow()

    private val _loadedVersion = MutableStateFlow<Long?>(null)
    val loadedVersion: StateFlow<Long?> = _loadedVersion.asStateFlow()

    private val _lastReportAt = MutableStateFlow<Long?>(null)
    val lastReportAt: StateFlow<Long?> = _lastReportAt.asStateFlow()

    private val _configApplied = MutableStateFlow(false)
    val configApplied: StateFlow<Boolean> = _configApplied.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _framework = MutableStateFlow<String?>(null)
    val framework: StateFlow<String?> = _framework.asStateFlow()

    private val _hookSummary = MutableStateFlow<String?>("等待模块挂载")
    val hookSummary: StateFlow<String?> = _hookSummary.asStateFlow()

    private val _lastReportTime = MutableStateFlow<Long?>(null)
    val lastReportTime: StateFlow<Long?> = _lastReportTime.asStateFlow()

    // 注意：必须使用无参默认值构造，禁止在此处调用 toModuleStatus()（其依赖下方 counters 等字段）
    private val _status = MutableStateFlow(ModuleStatus())
    val status: StateFlow<ModuleStatus> = _status.asStateFlow()

    private val counters = ConcurrentHashMap<LogCategory, AtomicLong>()
    private val cachedConfig = ConcurrentHashMap<String, Boolean>()
    private var pendingGuard: Boolean = false
    private var pendingPolicies: Map<PolicyKey, Boolean> = PolicyKey.defaults()
    private var pendingMode: BlockerMode = BlockerMode.RECOMMENDED
    private var configSubmitted: Boolean = false
    private var lastConfigTimestamp: Long = 0L

    /** 当前框架上报的目标进程 pid，用于校验审计快照归属。 */
    private var targetPid: Int = 0

    init {
        LogCategory.entries.forEach { counters[it] = AtomicLong(0L) }
        runCatching { XposedServiceHelper.registerListener(this) }
    }

    private fun notifyChange() {
        _status.value = toModuleStatus()
        _revision.value = _revision.value + 1
    }

    override fun onServiceBind(service: XposedService) {
        _service.value = service
        _framework.value = "${service.frameworkName} ${service.frameworkVersion}"
        refreshTargets()
        // 仅当 UI 已经提交过配置时才回推，避免用默认 false 覆盖用户已启用的策略
        if (configSubmitted) {
            writeConfig(pendingGuard, pendingPolicies, pendingMode)
        }
        notifyChange()
    }

    override fun onServiceDied(service: XposedService) {
        _service.value = null
        _frameworkConfirmed.value = false
        _targetState.value = null
        _targetHooked.value = false
        _framework.value = null
        notifyChange()
    }

    /**
     * 前台按需全量刷新：检测目标进程挂载状态，并从框架 Remote File 读取主进程最新审计快照。
     * 无内部后台轮询，完全由 UI 前台生命周期驱动。
     */
    suspend fun refresh() {
        val currentService = _service.value
        if (currentService != null) {
            _framework.value = "${currentService.frameworkName} ${currentService.frameworkVersion}"
            checkFrameworkRunningTargets(currentService)
            withContext(Dispatchers.IO) {
                readRemoteAudit(currentService)
            }
        } else {
            _frameworkConfirmed.value = false
            _targetState.value = null
            targetPid = 0
            _hookPointCount.value = 0
            recomputeHookState()
        }
        notifyChange()
    }

    /**
     * 同步非挂起状态检测，兼容既有调用点。
     */
    fun refreshTargets() {
        val current = _service.value
        if (current != null) {
            checkFrameworkRunningTargets(current)
            scope.launch {
                readRemoteAudit(current)
                recomputeHookState()
                notifyChange()
            }
        } else {
            _frameworkConfirmed.value = false
            _targetState.value = null
            targetPid = 0
            _hookPointCount.value = 0
            recomputeHookState()
        }
        notifyChange()
    }

    private fun checkFrameworkRunningTargets(service: XposedService) {
        val target = runCatching {
            if (service.apiVersion >= 102) {
                service.runningTargets.firstOrNull { it.processName == TARGET_PACKAGE }
            } else null
        }.getOrNull()

        if (target != null) {
            _targetState.value = target.state.name
            _loadedVersion.value = target.loadedVersionCode
            _frameworkConfirmed.value = (target.state == HookedTarget.State.UP_TO_DATE)
            targetPid = target.pid
        } else {
            _frameworkConfirmed.value = false
            _targetState.value = null
            _loadedVersion.value = null
            targetPid = 0
            _hookPointCount.value = 0
        }
        recomputeHookState()
    }

    /**
     * 目标是否真正挂载：必须同时满足框架确认与本进程已安装拦截点，历史拦截计数不参与判定。
     */
    private fun recomputeHookState() {
        _targetHooked.value = _frameworkConfirmed.value && _hookPointCount.value > 0
    }

    /**
     * 从框架 Remote File 读取目标主进程写入的审计快照。
     */
    private fun readRemoteAudit(service: XposedService) {
        val names = runCatching { service.listRemoteFiles() }.getOrNull()
        val fileName = AUDIT_FILE_NAMES.firstOrNull { candidate ->
            names != null && names.contains(candidate)
        }

        if (fileName == null) {
            // 快照尚未生成：仅在框架已确认挂载时提示，避免目标未启动时误报
            _error.value = if (_frameworkConfirmed.value) {
                "未找到目标进程审计快照(${AUDIT_FILE_NAMES.joinToString("/")})，请确认模块已写入"
            } else {
                null
            }
            return
        }

        try {
            val pfd = runCatching { service.openRemoteFile(fileName) }.getOrNull()
            if (pfd == null) {
                _error.value = "打开审计快照失败: $fileName"
                return
            }
            pfd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).bufferedReader(Charsets.UTF_8).use { reader ->
                    val content = reader.readText()
                    if (content.isBlank()) {
                        _error.value = "审计快照为空: $fileName"
                        return
                    }
                    applyAuditSnapshot(JSONObject(content))
                }
            }
        } catch (error: Throwable) {
            _error.value = "读取审计快照失败: ${error.javaClass.simpleName}"
        }
    }

    /**
     * 应用审计快照：计数直接采纳，安装点数与配置生效必须通过 pid / 版本校验。
     */
    private fun applyAuditSnapshot(json: JSONObject) {
        // 1. 计数属于历史审计，始终采纳，避免丢失
        counters[LogCategory.NETWORK]?.set(json.optLong("net", 0L))
        counters[LogCategory.LOCATION]?.set(json.optLong("loc", 0L))
        counters[LogCategory.STORAGE]?.set(json.optLong("str", 0L))
        counters[LogCategory.TELEMETRY]?.set(json.optLong("tel", 0L))

        val lastEvent = json.optLong("lastEventAt", 0L)
        if (lastEvent > 0L) {
            _lastReportAt.value = lastEvent
            _lastReportTime.value = lastEvent
        }

        // 2. 快照归属校验：pid 必须与框架上报的存活进程一致
        val snapshotPid = json.optInt("pid", 0)
        val snapshotVersion = json.optLong("moduleVersionCode", 0L)
        val pidMatches = targetPid > 0 && snapshotPid == targetPid
        val versionMatches = snapshotVersion <= 0L ||
                (_loadedVersion.value ?: -1L) <= 0L ||
                snapshotVersion == _loadedVersion.value

        val hookCount = json.optInt("hookCount", 0)
        if (pidMatches && versionMatches) {
            _hookPointCount.value = hookCount
            val dynamic = json.optBoolean("dynamic", false)
            val note = json.optString("note", "")
            val typeLabel = if (dynamic) "DEX 特征启发式" else "已知混淆名回退"
            _hookSummary.value = "已挂载 $hookCount 个拦截点（$typeLabel）" +
                    if (note.isNotBlank()) " - $note" else ""
        } else if (hookCount > 0) {
            // 旧进程残留快照：不得当作当前进程安装成功
            _hookPointCount.value = 0
            _hookSummary.value = "等待目标进程刷新模块快照"
        }

        // 3. 配置生效：必须来自当前存活进程
        val moduleConfigTs = json.optLong("configTimestamp", 0L)
        _configApplied.value = pidMatches && moduleConfigTs > 0L &&
                (lastConfigTimestamp <= 0L || moduleConfigTs >= lastConfigTimestamp)

        // 4. 框架与写入错误
        val fw = json.optString("framework", "")
        val fwVer = json.optString("frameworkVersion", "")
        if (fw.isNotBlank()) {
            _framework.value = "$fw $fwVer".trim()
        }

        val remoteWriteError = json.optString("remoteWriteError", "")
        _error.value = if (remoteWriteError.isNotBlank()) {
            "模块侧审计写入失败: $remoteWriteError"
        } else {
            null
        }

        recomputeHookState()
    }

    /** 把 UI 的开关写入框架侧 RemotePreferences，模块无需读文件。 */
    fun writeConfig(
        guard: Boolean,
        policies: Map<PolicyKey, Boolean>,
        mode: BlockerMode,
    ) {
        pendingGuard = guard
        pendingPolicies = policies
        pendingMode = mode
        configSubmitted = true

        cachedConfig["guard"] = guard
        cachedConfig["egress"] = guard && (policies[PolicyKey.NET_ANALYTICS] == true)
        cachedConfig["collect"] = guard
        cachedConfig["location"] = guard && (policies[PolicyKey.LOC_FUSED] == true)
        cachedConfig["db"] = guard && (policies[PolicyKey.STORE_SANDBOX] == true)
        cachedConfig["wipe"] = guard
        policies.forEach { (key, value) -> cachedConfig[key.configKey] = value }

        val prefs = _service.value?.getRemotePreferences(PREF_NAME) ?: return
        try {
            // 与模块侧 HookConfig 读取的 config_timestamp 使用同一 key、同一时间单位
            val ts = System.currentTimeMillis()
            lastConfigTimestamp = ts
            val editor = prefs.edit()
            editor.putBoolean("guard", guard)
            editor.putBoolean("egress", guard && (policies[PolicyKey.NET_ANALYTICS] == true))
            editor.putBoolean("collect", guard)
            editor.putBoolean("location", guard && (policies[PolicyKey.LOC_FUSED] == true))
            editor.putBoolean("db", guard && (policies[PolicyKey.STORE_SANDBOX] == true))
            editor.putBoolean("wipe", guard)
            policies.forEach { (key, value) -> editor.putBoolean(key.configKey, value) }
            editor.putString("mode", mode.name)
            editor.putLong(CONFIG_TIMESTAMP_KEY, ts)
            editor.putBoolean("manager_active", true)
            editor.apply()
            notifyChange()
        } catch (error: Throwable) {
            _error.value = "下发配置失败: ${error.javaClass.simpleName}"
        }
    }

    /** 对齐模块侧全量计数。 */
    fun alignCounts(counts: Map<LogCategory, Long>, total: Long) {
        counts.forEach { (category, count) ->
            counters[category]?.set(count)
        }
        notifyChange()
    }

    /** 接收来自 StatsProvider 的拦截命中计数。 */
    fun recordHits(hits: Map<LogCategory, Long>) {
        hits.forEach { (category, count) ->
            counters[category]?.addAndGet(count)
        }
        _lastReportTime.value = System.currentTimeMillis()
        notifyChange()
    }

    /** 接收来自 StatsProvider 的 Hook 挂载点结果。 */
    fun recordPoints(points: Map<String, List<String>>, count: Int, dynamic: Boolean, note: String) {
        _hookPointCount.value = count
        val typeLabel = if (dynamic) "DEX 特征启发式" else "已知混淆名回退"
        _hookSummary.value = "已挂载 $count 个拦截点（$typeLabel）"
        _lastReportTime.value = System.currentTimeMillis()
        recomputeHookState()
        notifyChange()
    }

    /** 接收来自 StatsProvider 的模块环境信息。 */
    fun recordEnvironment(frameworkName: String?, version: String?) {
        if (!frameworkName.isNullOrBlank()) {
            _framework.value = "$frameworkName ${version ?: ""}".trim()
        }
        _lastReportTime.value = System.currentTimeMillis()
        notifyChange()
    }

    fun recordConfigApplied(timestamp: Long) {
        if (lastConfigTimestamp <= 0L || timestamp >= lastConfigTimestamp) {
            _configApplied.value = true
            notifyChange()
        }
    }

    /** 将当前内部状态组装为 ModuleStatus。 */
    fun toModuleStatus(rootAvailable: Boolean = false): ModuleStatus {
        val isHooked = _targetHooked.value
        val lastReport = _lastReportTime.value
        val summary = _hookSummary.value

        val counterMap = buildMap<String, Long> {
            counters.forEach { (k, v) -> put(k.name.lowercase(), v.get()) }
        }

        return ModuleStatus(
            counters = counterMap,
            serviceConnected = _service.value != null,
            targetHooked = isHooked,
            framework = _framework.value,
            targetState = _targetState.value,
            hookPointCount = _hookPointCount.value,
            loadedVersion = _loadedVersion.value,
            error = _error.value,
            lastReportAt = _lastReportAt.value,
            frameworkConfirmed = _frameworkConfirmed.value,
            configApplied = _configApplied.value,
        )
    }

    fun toStats(): BlockerStats {
        val net = counters[LogCategory.NETWORK]?.get() ?: 0L
        val tel = counters[LogCategory.TELEMETRY]?.get() ?: 0L
        val loc = counters[LogCategory.LOCATION]?.get() ?: 0L
        val str = counters[LogCategory.STORAGE]?.get() ?: 0L
        val total = net + tel + loc + str
        return BlockerStats(
            total = total,
            today = total,
            net = net,
            tel = tel,
            loc = loc,
            str = str,
            dnsBlocked = net,
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.mobiletools.systemhelper"
        const val PREF_NAME = "fucksh"
        /** 与模块侧 HookConfig 读取的 key 完全一致。 */
        const val CONFIG_TIMESTAMP_KEY = "config_timestamp"

        /** 目标主进程审计快照候选文件名（框架 Remote File）。 */
        private val AUDIT_FILE_NAMES = arrayOf("fucksh_audit_main.json", "fucksh_audit.json")
    }
}
