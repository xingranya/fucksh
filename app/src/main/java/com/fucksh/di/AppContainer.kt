package com.fucksh.di

import android.content.Context
import com.fucksh.data.BlockerPreferences
import com.fucksh.data.EventLogRepository
import com.fucksh.data.fuckshDataStore
import com.fucksh.system.ModuleBridge
import com.fucksh.system.ModuleService
import com.fucksh.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/** 手工依赖容器：应用生命周期内单例。 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val moduleBridge = ModuleBridge(appContext)
    val moduleService = ModuleService(appContext)
    val preferences = BlockerPreferences(appContext.fuckshDataStore)
    val eventLog = EventLogRepository(File(appContext.filesDir, "logs.jsonl"), appScope)
    val updateRepository = UpdateRepository(appContext)

    val exportDir: File
        get() = File(appContext.getExternalFilesDir("exports") ?: appContext.filesDir, "").apply { mkdirs() }
}
