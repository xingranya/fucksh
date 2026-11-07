package com.fucksh.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fucksh.data.BlockerMode
import com.fucksh.data.BlockerUiState
import com.fucksh.data.HealthItem
import com.fucksh.data.HealthReport
import com.fucksh.data.LogCategory
import com.fucksh.data.PolicyKey
import com.fucksh.di.AppContainer
import com.fucksh.update.UpdateInfo
import com.fucksh.update.DownloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 管理界面只负责下发配置、读取框架审计。拦截由目标进程中的 Hook 独立执行。 */
class BlockerViewModel(
    private val app: Application,
    private val container: AppContainer,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BlockerUiState())
    val uiState = _uiState.asStateFlow()
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _shareRequests = Channel<File>(Channel.BUFFERED)
    val shareRequests = _shareRequests.receiveAsFlow()
    private val refreshMutex = Mutex()
    private var foregroundJob: Job? = null

    init {
        viewModelScope.launch {
            container.preferences.settings.collect { settings ->
                _uiState.update {
                    it.copy(loading = false, guardEnabled = settings.guardEnabled,
                        mode = settings.mode, policies = settings.policies, stats = settings.stats)
                }
            }
        }
        viewModelScope.launch {
            container.eventLog.logs.collect { logs -> _uiState.update { it.copy(logs = logs) } }
        }
        viewModelScope.launch {
            container.moduleService.status.collect { module ->
                _uiState.update { it.copy(module = module) }
            }
        }
        // 只有策略发生变化时写框架，统计刷新不重复覆盖配置。
        viewModelScope.launch {
            container.preferences.settings
                .map { Triple(it.guardEnabled, it.policies, it.mode) }
                .distinctUntilChanged()
                .collect { (guard, policies, mode) ->
                    withContext(Dispatchers.IO) { container.moduleService.writeConfig(guard, policies, mode) }
                }
        }
    }

    /** 只在 Activity 可见时拉取审计；切到后台即取消，不启动服务或保活。 */
    fun setForeground(visible: Boolean) {
        foregroundJob?.cancel()
        foregroundJob = null
        if (!visible) return
        foregroundJob = viewModelScope.launch {
            container.preferences.settings.first()
            while (isActive) {
                refreshSnapshot()
                delay(3_000L)
            }
        }
    }

    fun refreshModule() { viewModelScope.launch { refreshSnapshot() } }

    private suspend fun refreshSnapshot() = refreshMutex.withLock {
        withContext(Dispatchers.IO) {
            container.moduleService.refresh()
            val module = container.moduleService.toModuleStatus()
            val stats = container.moduleService.toStats()
            // 仅在读到新架构的审计快照后替换计数。旧 VPN 统计不会混入。
            if (module.lastReportAt != null) container.preferences.replaceHookStats(stats)
            _uiState.update {
                it.copy(module = module,
                    moduleStats = stats.takeIf { module.lastReportAt != null },
                    targetInstalled = container.moduleBridge.isTargetInstalled(),
                    targetUid = container.moduleBridge.targetUid())
            }
        }
    }

    fun setGuard(enabled: Boolean) {
        viewModelScope.launch {
            container.preferences.setGuardEnabled(enabled)
            _messages.send(if (container.moduleService.service.value == null) {
                "策略已保存；连接 LSPosed 后自动同步"
            } else if (enabled) "守护策略已下发" else "已下发暂停策略")
        }
    }

    fun setMode(mode: BlockerMode) {
        viewModelScope.launch {
            container.preferences.setMode(mode, _uiState.value.policies)
            _messages.send("已切换到${mode.label}")
        }
    }

    fun setPolicy(key: PolicyKey, enabled: Boolean) {
        viewModelScope.launch {
            container.preferences.setPolicy(key, enabled)
            _messages.send("${key.title}已${if (enabled) "开启" else "关闭"}")
        }
    }

    fun setFilter(category: LogCategory?) { _uiState.update { it.copy(filter = category) } }
    fun clearLogs() {
        container.eventLog.clear()
        showMessage("本地审计记录已清空")
    }

    fun exportAudit() {
        viewModelScope.launch {
            val file = File(container.exportDir,
                "fucksh-audit-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt")
            runCatching { withContext(Dispatchers.IO) { container.eventLog.export(file) } }
                .onSuccess { count ->
                    if (count > 0) _shareRequests.send(file) else _messages.send("暂无可导出的记录")
                }
                .onFailure { _messages.send("导出失败：${it.message ?: "无法写入文件"}") }
        }
    }

    fun selfCheck() {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            try {
                refreshSnapshot()
                val state = _uiState.value
                val module = state.module
                val report = HealthReport(listOf(
                    HealthItem("libxposed 框架", module.serviceConnected,
                        module.framework ?: "请在支持 API 102 的 LSPosed 中启用 fucksh"),
                    HealthItem("目标应用", state.targetInstalled,
                        if (state.targetInstalled) "SystemHelper 已安装" else "当前设备未检测到目标组件"),
                    HealthItem("进程加载", module.frameworkConfirmed,
                        "框架状态：${module.targetState ?: "目标尚未运行"}"),
                    HealthItem("拦截点", module.hookPointCount > 0,
                        module.hookSummary ?: "尚未读取到实际安装的拦截点"),
                    HealthItem("配置同步", module.configApplied,
                        if (module.configApplied) "目标已确认最新策略" else "等待目标进程确认配置"),
                    HealthItem("独立运行", true, "Hook 在目标进程中执行，无需管理界面常驻、Root 命令或 VPN"),
                ))
                _uiState.update { it.copy(lastHealth = report) }
            } finally { _uiState.update { it.copy(busy = false) } }
        }
    }

    fun checkRules() {
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) { container.moduleBridge.checkRules() }
            _uiState.update { it.copy(lastHealth = report) }
        }
    }

    fun dismissHealth() { _uiState.update { it.copy(lastHealth = null) } }
    fun showMessage(message: String) { viewModelScope.launch { _messages.send(message) } }

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    val updateInfo = _updateInfo.asStateFlow()
    private val _updateProgress = MutableStateFlow<DownloadProgress?>(null)
    val updateProgress = _updateProgress.asStateFlow()

    fun checkUpdate() {
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            val info = withContext(Dispatchers.IO) { container.updateRepository.check() }
            _updateInfo.value = info
            if (info == null) _messages.send("当前已是最新版本")
            _uiState.update { it.copy(busy = false) }
        }
    }

    fun downloadUpdate(info: UpdateInfo) {
        viewModelScope.launch {
            val apk = withContext(Dispatchers.IO) {
                container.updateRepository.download(info) { progress ->
                    _updateProgress.value = progress
                }
            }
            if (container.updateRepository.verify(apk, info.sha256)) {
                _messages.send("下载完成，正在调起安装")
                app.startActivity(container.updateRepository.createInstallIntent(apk).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } else {
                _messages.send("安装包校验失败")
            }
            _updateProgress.value = null
        }
    }

    /** 重新同步配置，不强停目标进程，不请求 Root。 */
    fun restartGuard() {
        viewModelScope.launch {
            val settings = container.preferences.settings.first()
            withContext(Dispatchers.IO) {
                container.moduleService.writeConfig(settings.guardEnabled, settings.policies, settings.mode)
            }
            refreshSnapshot()
            _messages.send("已重新同步策略；Hook 随系统目标进程自动加载")
        }
    }

    class Factory(private val app: Application, private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BlockerViewModel::class.java))
            return BlockerViewModel(app, container) as T
        }
    }
}
