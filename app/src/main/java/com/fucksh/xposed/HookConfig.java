package com.fucksh.xposed;

import android.content.SharedPreferences;

import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;

/**
 * 模块侧配置视图。
 *
 * <p>配置的唯一来源是 libxposed 框架提供的 RemotePreferences（组名 {@value #PREF_NAME}）。
 * 管理器（fucksh APP）通过 {@code XposedService.getRemotePreferences(PREF_NAME)} 写入，
 * 模块在目标进程内通过 {@code XposedModule.getRemotePreferences(PREF_NAME)} 读取并监听变化，
 * 全程不读写 config.conf 等任何文件。</p>
 *
 * <p>模块内的 RemotePreferences 是只读的，因此配置是单向“下发 + 监听”，
 * 模块状态则通过 {@link Reporter} 走 ContentProvider 回传给管理器，两者合起来构成双向同步。</p>
 */
final class HookConfig {

    /** 与 ModuleService.PREF_NAME 保持一致。 */
    static final String PREF_NAME = "fucksh";

    private final XposedModule module;
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> syncListeners = new CopyOnWriteArrayList<>();
    private volatile SharedPreferences preferences;
    private volatile Snapshot snapshot = Snapshot.fallback();

    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener =
            (changed, key) -> reload();

    HookConfig(XposedModule module) {
        this.module = module;
    }

    /** 绑定 RemotePreferences；重复调用安全。 */
    void bind() {
        if (preferences != null) {
            reload();
            return;
        }
        try {
            preferences = module.getRemotePreferences(PREF_NAME);
            preferences.registerOnSharedPreferenceChangeListener(preferenceListener);
            reload();
            module.log(4, ModuleMain.TAG, "config bound group=" + PREF_NAME);
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "remote preferences unavailable, using safe defaults", error);
            snapshot = Snapshot.fallback();
        }
    }

    /** 当前配置快照，Hook 每次调用时读取，保证管理器改开关后无需重启目标进程。 */
    Snapshot snapshot() {
        return snapshot;
    }

    boolean guard() {
        return snapshot.guard;
    }

    boolean egress() {
        return snapshot.guard && snapshot.egress;
    }

    boolean collect() {
        return snapshot.guard && snapshot.collect;
    }

    boolean location() {
        return snapshot.guard && snapshot.location;
    }

    boolean db() {
        return snapshot.guard && snapshot.db;
    }

    boolean wipe() {
        return snapshot.guard && snapshot.wipe;
    }

    boolean analytics() {
        return snapshot.guard && snapshot.analytics;
    }

    boolean maskOaid() {
        return snapshot.guard && snapshot.maskOaid;
    }

    boolean blurLocation() {
        return snapshot.guard && snapshot.blurLocation;
    }

    boolean cleanSandbox() {
        return snapshot.guard && snapshot.cleanSandbox;
    }

    /** 管理端是否当前处于前台活跃状态。 */
    boolean isManagerActive() {
        return snapshot.managerActive;
    }

    /** 最近一次来自管理端的批量同步请求时间戳。 */
    long getSyncRequestTime() {
        return snapshot.syncRequestTime;
    }

    /** 管理端下发的最新配置时间戳。 */
    long getConfigTimestamp() {
        return snapshot.configTimestamp;
    }

    /** 注册配置变化回调（在 binder 线程触发）。 */
    void onChange(Runnable listener) {
        if (listener != null) listeners.add(listener);
    }

    /** 注册管理端发起的前台同步拉取回调。 */
    void onSyncRequested(Runnable listener) {
        if (listener != null) syncListeners.add(listener);
    }

    private void reload() {
        SharedPreferences current = preferences;
        if (current == null) return;
        try {
            Snapshot next = Snapshot.read(current);
            Snapshot previous = snapshot;
            boolean configDiff = !next.equals(previous);
            boolean syncRequested = next.syncRequestTime != previous.syncRequestTime && next.managerActive;

            if (configDiff) {
                snapshot = next;
                module.log(4, ModuleMain.TAG, "config changed: " + next);
                for (Runnable listener : listeners) {
                    try {
                        listener.run();
                    } catch (Throwable error) {
                        module.log(5, ModuleMain.TAG, "config listener failed", error);
                    }
                }
            } else {
                snapshot = next;
            }

            if (syncRequested) {
                for (Runnable listener : syncListeners) {
                    try {
                        listener.run();
                    } catch (Throwable error) {
                        module.log(5, ModuleMain.TAG, "sync listener failed", error);
                    }
                }
            }
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "read remote preferences failed", error);
        }
    }

    /**
     * 不可变配置快照。
     *
     * <p>{@code guard} 是总开关；{@code egress/collect/location/db/wipe} 是管理器计算出的
     * 分层开关；其余 granular 字段对应配置页的七个策略。</p>
     */
    static final class Snapshot {
        final boolean guard;
        final boolean egress;
        final boolean collect;
        final boolean location;
        final boolean db;
        final boolean wipe;
        final boolean analytics;
        final boolean adDomains;
        final boolean doh;
        final boolean maskOaid;
        final boolean sysLog;
        final boolean blurLocation;
        final boolean cleanSandbox;
        final boolean managerActive;
        final long syncRequestTime;
        final long configTimestamp;

        private Snapshot(boolean guard, boolean egress, boolean collect, boolean location,
                         boolean db, boolean wipe, boolean analytics, boolean adDomains,
                         boolean doh, boolean maskOaid, boolean sysLog, boolean blurLocation,
                         boolean cleanSandbox, boolean managerActive, long syncRequestTime,
                         long configTimestamp) {
            this.guard = guard;
            this.egress = egress;
            this.collect = collect;
            this.location = location;
            this.db = db;
            this.wipe = wipe;
            this.analytics = analytics;
            this.adDomains = adDomains;
            this.doh = doh;
            this.maskOaid = maskOaid;
            this.sysLog = sysLog;
            this.blurLocation = blurLocation;
            this.cleanSandbox = cleanSandbox;
            this.managerActive = managerActive;
            this.syncRequestTime = syncRequestTime;
            this.configTimestamp = configTimestamp;
        }

        static Snapshot read(SharedPreferences prefs) {
            boolean guard = prefs.getBoolean("guard", false);
            return new Snapshot(
                    guard,
                    prefs.getBoolean("egress", guard),
                    prefs.getBoolean("collect", guard),
                    prefs.getBoolean("location", guard),
                    prefs.getBoolean("db", guard),
                    prefs.getBoolean("wipe", guard),
                    prefs.getBoolean("BLOCK_ANALYTICS", true),
                    prefs.getBoolean("BLOCK_AD_DOMAINS", true),
                    prefs.getBoolean("BLOCK_DOH", false),
                    prefs.getBoolean("MASK_OAID", true),
                    prefs.getBoolean("BLOCK_SYS_LOG", true),
                    prefs.getBoolean("BLUR_LOCATION", true),
                    prefs.getBoolean("CLEAN_SANDBOX", true),
                    prefs.getBoolean("manager_active", false),
                    prefs.getLong("sync_request_time", 0L),
                    prefs.getLong("config_timestamp", 0L));
        }

        /**
         * 没有可用 RemotePreferences 时（内嵌框架或框架异常）采用保守默认值：
         * 默认开启全部拦截，避免模块“看起来加载了但什么都没做”。
         */
        static Snapshot fallback() {
            return new Snapshot(true, true, true, true, true, true,
                    true, true, false, true, true, true, true,
                    false, 0L, 0L);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Snapshot)) return false;
            Snapshot that = (Snapshot) other;
            return guard == that.guard
                    && egress == that.egress
                    && collect == that.collect
                    && location == that.location
                    && db == that.db
                    && wipe == that.wipe
                    && analytics == that.analytics
                    && adDomains == that.adDomains
                    && doh == that.doh
                    && maskOaid == that.maskOaid
                    && sysLog == that.sysLog
                    && blurLocation == that.blurLocation
                    && cleanSandbox == that.cleanSandbox
                    && managerActive == that.managerActive
                    && syncRequestTime == that.syncRequestTime
                    && configTimestamp == that.configTimestamp;
        }

        @Override
        public int hashCode() {
            int result = guard ? 1 : 0;
            result = 31 * result + (egress ? 1 : 0);
            result = 31 * result + (collect ? 1 : 0);
            result = 31 * result + (location ? 1 : 0);
            result = 31 * result + (db ? 1 : 0);
            result = 31 * result + (wipe ? 1 : 0);
            result = 31 * result + (managerActive ? 1 : 0);
            result = 31 * result + (int) (syncRequestTime ^ (syncRequestTime >>> 32));
            result = 31 * result + (int) (configTimestamp ^ (configTimestamp >>> 32));
            return result;
        }

        @Override
        public String toString() {
            return "guard=" + guard + " egress=" + egress + " collect=" + collect
                    + " location=" + location + " db=" + db + " wipe=" + wipe
                    + " analytics=" + analytics + " ads=" + adDomains + " doh=" + doh
                    + " oaid=" + maskOaid + " syslog=" + sysLog
                    + " blurLoc=" + blurLocation + " sandbox=" + cleanSandbox
                    + " active=" + managerActive + " syncReq=" + syncRequestTime
                    + " ts=" + configTimestamp;
        }
    }
}
