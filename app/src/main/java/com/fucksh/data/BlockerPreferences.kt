package com.fucksh.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Context.fuckshDataStore: DataStore<Preferences> by preferencesDataStore(name = "fucksh_settings")

/** 从 DataStore 读出的持久化设置。 */
data class PersistedSettings(
    val guardEnabled: Boolean = false,
    val mode: BlockerMode = BlockerMode.RECOMMENDED,
    val policies: Map<PolicyKey, Boolean> = PolicyKey.defaults(),
    val stats: BlockerStats = BlockerStats(),
)

/** 设置、统计与日期的统一读写入口。 */
class BlockerPreferences(private val dataStore: DataStore<Preferences>) {

    val settings: Flow<PersistedSettings> = dataStore.data.map { prefs ->
        val today = todayKey()
        val storedDay = prefs[STAT_DAY] ?: ""
        PersistedSettings(
            guardEnabled = prefs[GUARD_ENABLED] ?: false,
            mode = BlockerMode.fromKey(prefs[MODE]),
            policies = PolicyKey.entries.associateWith { key ->
                prefs[policyKey(key)] ?: key.defaultEnabled
            },
            stats = BlockerStats(
                total = prefs[STAT_TOTAL] ?: 0L,
                today = if (storedDay == today) prefs[STAT_TODAY] ?: 0L else 0L,
                net = prefs[STAT_NET] ?: 0L,
                tel = prefs[STAT_TEL] ?: 0L,
                loc = prefs[STAT_LOC] ?: 0L,
                str = prefs[STAT_STR] ?: 0L,
                day = today,
                dnsAllowed = prefs[DNS_ALLOWED] ?: 0L,
                dnsBlocked = prefs[DNS_BLOCKED] ?: 0L,
            ),
        )
    }

    suspend fun setGuardEnabled(enabled: Boolean) {
        dataStore.edit { it[GUARD_ENABLED] = enabled }
    }

    suspend fun setMode(mode: BlockerMode, policies: Map<PolicyKey, Boolean>) {
        dataStore.edit { prefs ->
            prefs[MODE] = mode.name
            PolicyKey.entries.forEach { key ->
                prefs[policyKey(key)] = policies[key] ?: key.defaultEnabled
            }
        }
    }

    suspend fun setPolicy(key: PolicyKey, enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[policyKey(key)] = enabled
            // 手动调整后模式切到「调试监控」以外时仍保留用户选择，不再回写预设。
        }
    }

    /** 记录本地拦截，并同步今日计数。批量写入由守护服务每 2 秒聚合一次。 */
    suspend fun recordBlocked(category: LogCategory, count: Long = 1L) {
        if (count <= 0L) return
        dataStore.edit { prefs ->
            val today = todayKey()
            val sameDay = prefs[STAT_DAY] == today
            prefs[STAT_DAY] = today
            prefs[STAT_TOTAL] = (prefs[STAT_TOTAL] ?: 0L) + count
            prefs[STAT_TODAY] = if (sameDay) (prefs[STAT_TODAY] ?: 0L) + count else count
            val key = when (category) {
                LogCategory.NETWORK -> STAT_NET
                LogCategory.TELEMETRY -> STAT_TEL
                LogCategory.LOCATION -> STAT_LOC
                LogCategory.STORAGE -> STAT_STR
            }
            prefs[key] = (prefs[key] ?: 0L) + count
        }
    }

    /** 记录 DNS 采样，用于计算纯净指数。 */
    suspend fun recordDnsBatch(blocked: Long, allowed: Long) {
        if (blocked <= 0L && allowed <= 0L) return
        dataStore.edit { prefs ->
            if (blocked > 0L) prefs[DNS_BLOCKED] = (prefs[DNS_BLOCKED] ?: 0L) + blocked
            if (allowed > 0L) prefs[DNS_ALLOWED] = (prefs[DNS_ALLOWED] ?: 0L) + allowed
        }
    }

    /** 全量覆盖审计统计，用于 Hook 架构切换后的计数重对齐。 */
    suspend fun replaceHookStats(stats: BlockerStats) {
        dataStore.edit { prefs ->
            prefs[STAT_NET] = stats.net
            prefs[STAT_TEL] = stats.tel
            prefs[STAT_LOC] = stats.loc
            prefs[STAT_STR] = stats.str
            prefs[STAT_TOTAL] = stats.total
            prefs[STAT_TODAY] = stats.today
            prefs[STAT_DAY] = stats.day
        }
    }

    companion object {
        private val GUARD_ENABLED = booleanPreferencesKey("guard_enabled")
        private val MODE = stringPreferencesKey("mode")
        private val STAT_TOTAL = longPreferencesKey("stat_total")
        private val STAT_TODAY = longPreferencesKey("stat_today")
        private val STAT_NET = longPreferencesKey("stat_net")
        private val STAT_TEL = longPreferencesKey("stat_tel")
        private val STAT_LOC = longPreferencesKey("stat_loc")
        private val STAT_STR = longPreferencesKey("stat_str")
        private val STAT_DAY = stringPreferencesKey("stat_day")
        private val DNS_ALLOWED = longPreferencesKey("dns_allowed")
        private val DNS_BLOCKED = longPreferencesKey("dns_blocked")

        private fun policyKey(key: PolicyKey) = booleanPreferencesKey("policy_${key.name.lowercase()}")

        private fun todayKey(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
