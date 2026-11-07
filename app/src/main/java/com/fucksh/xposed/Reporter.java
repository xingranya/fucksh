package com.fucksh.xposed;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import io.github.libxposed.api.XposedModule;

/**
 * 模块侧审计记录器。
 *
 * <p><b>设计约束</b>：</p>
 * <ul>
 *   <li><b>后台零 IPC</b>：模块永远不会反向调用 ContentProvider 唤醒管理器，
 *       所有状态只写入框架 Remote File，由管理器在前台主动拉取。</li>
 *   <li><b>审计不丢失</b>：计数与最近事件同时写入框架 Remote File（跨进程可读）
 *       与目标进程私有文件（进程重启后恢复）；私有文件仅作持久化保底，
 *       不作为跨进程读取通道。</li>
 *   <li><b>失败必须可见</b>：Remote File 写入失败会写入日志一次，并把错误信息写进
 *       快照的 {@code remoteWriteError} 字段，由管理器前台展示，绝不静默吞掉。</li>
 * </ul>
 */
final class Reporter {

    /** 主进程审计快照文件名（框架 Remote File）。 */
    static final String REMOTE_FILE_MAIN = "fucksh_audit_main.json";
    /** 兼容旧文件名，主进程同样写入。 */
    static final String REMOTE_FILE_LEGACY = "fucksh_audit.json";

    private static final String STATS_FILE_NAME = "stats.properties";
    private static final String EVENTS_FILE_NAME = "events.log";
    private static final int MAX_RECENT_EVENTS = 50;
    private static final int MAX_EVENT_LOG_LINES = 500;

    private final XposedModule module;
    private final HookConfig config;
    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "fucksh-audit-worker");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicBoolean persistScheduled = new AtomicBoolean(false);
    private final AtomicLong eventCount = new AtomicLong(0L);
    private final AtomicLong netHits = new AtomicLong(0L);
    private final AtomicLong locHits = new AtomicLong(0L);
    private final AtomicLong strHits = new AtomicLong(0L);
    private final AtomicLong telHits = new AtomicLong(0L);
    private final Deque<Hit> recentEvents = new ArrayDeque<>();

    private volatile Context context;
    private volatile File auditDir;
    private volatile long lastEventAt = 0L;

    private volatile String cachedFramework = "";
    private volatile String cachedFrameworkVersion = "";
    private volatile long cachedFrameworkVersionCode = 0L;
    private volatile int cachedApiVersion = 0;
    private volatile Map<String, List<String>> cachedPoints = new java.util.LinkedHashMap<>();
    private volatile int cachedHookCount = 0;
    private volatile boolean cachedDynamic = false;
    private volatile String cachedNote = "";
    private volatile String remoteWriteError = "";

    Reporter(XposedModule module, HookConfig config) {
        this.module = module;
        this.config = config;
    }

    /**
     * Application 创建后绑定上下文并初始化私有持久化存储。
     */
    void attach(Context application) {
        if (application == null) return;
        this.context = application.getApplicationContext();
        try {
            File base = this.context.getFilesDir();
            if (base == null) base = this.context.getCacheDir();
            this.auditDir = new File(base, "fucksh_audit");
            if (!this.auditDir.exists()) {
                this.auditDir.mkdirs();
            }
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "failed to initialize audit directory", error);
        }

        // 异步从目标进程私有文件恢复历史审计统计（仅本进程内部使用）
        worker.execute(this::restoreFromStorage);
    }

    long eventCount() {
        return eventCount.get();
    }

    long lastEventAt() {
        return lastEventAt;
    }

    long netHits() {
        return netHits.get();
    }

    long locHits() {
        return locHits.get();
    }

    long strHits() {
        return strHits.get();
    }

    long telHits() {
        return telHits.get();
    }

    /**
     * 记录一次拦截。仅更新内存计数与最近事件，并异步落盘，<b>不发起任何 IPC</b>。
     */
    void hit(String rule, String layer, String detail) {
        long now = System.currentTimeMillis();
        lastEventAt = now;
        eventCount.incrementAndGet();

        if ("网络".equals(layer)) {
            netHits.incrementAndGet();
        } else if ("定位".equals(layer)) {
            locHits.incrementAndGet();
        } else if ("落库".equals(layer)) {
            strHits.incrementAndGet();
        } else {
            telHits.incrementAndGet();
        }

        synchronized (recentEvents) {
            recentEvents.addLast(new Hit(rule, layer, detail, now));
            while (recentEvents.size() > MAX_RECENT_EVENTS) {
                recentEvents.removeFirst();
            }
        }

        schedulePersistence(500L);
    }

    /** 记录 Hook 环境（框架版本、目标版本、进程）。 */
    void reportEnvironment(String framework, String frameworkVersion,
                           long frameworkVersionCode, int apiVersion) {
        this.cachedFramework = framework != null ? framework : "";
        this.cachedFrameworkVersion = frameworkVersion != null ? frameworkVersion : "";
        this.cachedFrameworkVersionCode = frameworkVersionCode;
        this.cachedApiVersion = apiVersion;
        schedulePersistence(200L);
    }

    /** 记录动态特征点定位结果。 */
    void reportPoints(Map<String, List<String>> points, int hookCount,
                      boolean dynamic, String note) {
        this.cachedPoints = points != null ? new java.util.LinkedHashMap<>(points) : new java.util.LinkedHashMap<>();
        this.cachedHookCount = hookCount;
        this.cachedDynamic = dynamic;
        this.cachedNote = note != null ? note : "";
        schedulePersistence(200L);
    }

    /** 当前进程 pid，写入快照供管理器校验快照归属。 */
    int currentPid() {
        return android.os.Process.myPid();
    }

    /** 模块版本号，写入快照供管理器与框架 loadedVersionCode 比对。 */
    int moduleVersionCode() {
        try {
            ApplicationInfo info = module.getModuleApplicationInfo();
            return info != null ? info.versionCode : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    private void schedulePersistence(long delayMs) {
        if (!persistScheduled.compareAndSet(false, true)) return;
        try {
            worker.schedule(this::persist, delayMs, TimeUnit.MILLISECONDS);
        } catch (Throwable error) {
            persistScheduled.set(false);
            module.log(5, ModuleMain.TAG, "schedule persistence failed", error);
        }
    }

    /**
     * 落盘：先写框架 Remote File（跨进程可读的主通道），再写目标进程私有文件（持久化保底）。
     */
    private void persist() {
        persistScheduled.set(false);
        String json = buildSnapshotJson();
        writeRemoteFile(json);
        writePrivateFiles(json);
    }

    private String buildSnapshotJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("pid", currentPid());
            root.put("moduleVersionCode", moduleVersionCode());
            root.put("process", processName());
            root.put("total", eventCount.get());
            root.put("net", netHits.get());
            root.put("loc", locHits.get());
            root.put("str", strHits.get());
            root.put("tel", telHits.get());
            root.put("hookCount", cachedHookCount);
            root.put("dynamic", cachedDynamic);
            root.put("note", cachedNote);
            root.put("framework", cachedFramework);
            root.put("frameworkVersion", cachedFrameworkVersion);
            root.put("frameworkVersionCode", cachedFrameworkVersionCode);
            root.put("apiVersion", cachedApiVersion);
            root.put("lastEventAt", lastEventAt);
            root.put("remoteWriteError", remoteWriteError);
            if (config != null) {
                root.put("configTimestamp", config.getConfigTimestamp());
            }

            JSONObject pointBundle = new JSONObject();
            for (Map.Entry<String, List<String>> entry : cachedPoints.entrySet()) {
                pointBundle.put(entry.getKey(), join(entry.getValue()));
            }
            root.put("points", pointBundle);

            JSONArray eventsArray = new JSONArray();
            synchronized (recentEvents) {
                for (Hit h : recentEvents) {
                    JSONObject obj = new JSONObject();
                    obj.put("rule", h.rule);
                    obj.put("layer", h.layer);
                    obj.put("detail", h.detail);
                    obj.put("time", h.time);
                    eventsArray.put(obj);
                }
            }
            root.put("recentEvents", eventsArray);
            return root.toString();
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "failed to build audit snapshot", error);
            return "{}";
        }
    }

    /**
     * 写入框架 Remote File。失败时记录错误并放进快照，供管理器前台展示。
     */
    private void writeRemoteFile(String json) {
        String proc = processName();
        boolean isMain = ModuleMain.TARGET_PACKAGE.equals(proc);
        String[] names = isMain
                ? new String[]{REMOTE_FILE_MAIN, REMOTE_FILE_LEGACY}
                : new String[]{"fucksh_audit_" + proc.replace(':', '_') + ".json"};

        for (String name : names) {
            try {
                ParcelFileDescriptor pfd = module.openRemoteFile(name);
                if (pfd == null) {
                    throw new IllegalStateException("openRemoteFile returned null: " + name);
                }
                try (FileOutputStream fos = new FileOutputStream(pfd.getFileDescriptor());
                     OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                    writer.write(json);
                    writer.flush();
                }
                if (!remoteWriteError.isEmpty()) {
                    module.log(4, ModuleMain.TAG, "remote audit file recovered: " + name);
                    remoteWriteError = "";
                }
                return;
            } catch (Throwable error) {
                module.log(5, ModuleMain.TAG, "write remote audit file failed: " + name, error);
                remoteWriteError = "写入框架审计文件失败(" + name + "): " + error.getClass().getSimpleName();
            }
        }
    }

    /**
     * 写入目标进程私有文件：计数与最近事件，仅用于本进程重启后的恢复。
     */
    private void writePrivateFiles(String json) {
        File dir = this.auditDir;
        if (dir == null || !dir.exists()) return;

        try {
            File statsFile = new File(dir, STATS_FILE_NAME);
            File tmpFile = new File(dir, STATS_FILE_NAME + ".tmp");
            Properties props = new Properties();
            props.setProperty("total_hits", String.valueOf(eventCount.get()));
            props.setProperty("net_hits", String.valueOf(netHits.get()));
            props.setProperty("loc_hits", String.valueOf(locHits.get()));
            props.setProperty("str_hits", String.valueOf(strHits.get()));
            props.setProperty("tel_hits", String.valueOf(telHits.get()));
            props.setProperty("last_event_at", String.valueOf(lastEventAt));
            props.setProperty("hook_count", String.valueOf(cachedHookCount));
            props.setProperty("dynamic", String.valueOf(cachedDynamic));
            props.setProperty("note", cachedNote);
            props.setProperty("framework", cachedFramework);
            props.setProperty("framework_version", cachedFrameworkVersion);
            props.setProperty("framework_version_code", String.valueOf(cachedFrameworkVersionCode));
            props.setProperty("api_version", String.valueOf(cachedApiVersion));
            props.setProperty("snapshot", json);

            try (FileOutputStream fos = new FileOutputStream(tmpFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                props.store(writer, "fucksh audit stats");
            }
            if (!tmpFile.renameTo(statsFile)) {
                statsFile.delete();
                tmpFile.renameTo(statsFile);
            }
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "failed to persist audit stats", error);
        }

        try {
            File eventsFile = new File(dir, EVENTS_FILE_NAME);
            StringBuilder builder = new StringBuilder();
            synchronized (recentEvents) {
                for (Hit h : recentEvents) {
                    builder.append(h.time).append('\t')
                            .append(h.rule).append('\t')
                            .append(h.layer).append('\t')
                            .append(h.detail.replace('\n', ' ')).append('\n');
                }
            }
            try (FileOutputStream fos = new FileOutputStream(eventsFile);
                 OutputStreamWriter writer = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                writer.write(builder.toString());
            }
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "failed to persist audit events", error);
        }
    }

    /**
     * 从目标进程私有文件恢复历史审计数据。
     */
    private void restoreFromStorage() {
        File dir = this.auditDir;
        if (dir == null || !dir.exists()) return;

        File statsFile = new File(dir, STATS_FILE_NAME);
        if (!statsFile.exists()) return;

        try (FileInputStream fis = new FileInputStream(statsFile);
             InputStreamReader reader = new InputStreamReader(fis, StandardCharsets.UTF_8)) {
            Properties props = new Properties();
            props.load(reader);

            eventCount.set(Math.max(eventCount.get(), parseLong(props.getProperty("total_hits"), 0L)));
            netHits.set(Math.max(netHits.get(), parseLong(props.getProperty("net_hits"), 0L)));
            locHits.set(Math.max(locHits.get(), parseLong(props.getProperty("loc_hits"), 0L)));
            strHits.set(Math.max(strHits.get(), parseLong(props.getProperty("str_hits"), 0L)));
            telHits.set(Math.max(telHits.get(), parseLong(props.getProperty("tel_hits"), 0L)));
            long savedLastEvent = parseLong(props.getProperty("last_event_at"), 0L);
            if (savedLastEvent > lastEventAt) {
                lastEventAt = savedLastEvent;
            }

            if (cachedHookCount == 0) {
                cachedHookCount = parseInt(props.getProperty("hook_count"), 0);
                cachedDynamic = Boolean.parseBoolean(props.getProperty("dynamic", "false"));
                cachedNote = props.getProperty("note", "");
            }
            if (cachedFramework.isEmpty()) {
                cachedFramework = props.getProperty("framework", "");
                cachedFrameworkVersion = props.getProperty("framework_version", "");
                cachedFrameworkVersionCode = parseLong(props.getProperty("framework_version_code"), 0L);
                cachedApiVersion = parseInt(props.getProperty("api_version"), 0);
            }

            module.log(4, ModuleMain.TAG, "restored audit stats from local storage: total="
                    + eventCount.get() + " net=" + netHits.get() + " loc=" + locHits.get());
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "failed to restore audit stats", error);
        }
    }

    private String processName() {
        try {
            return android.app.Application.getProcessName();
        } catch (Throwable error) {
            return ModuleMain.TARGET_PACKAGE;
        }
    }

    private static String join(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(value);
        }
        return builder.toString();
    }

    private static long parseLong(String val, long def) {
        if (val == null) return def;
        try {
            return Long.parseLong(val.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static int parseInt(String val, int def) {
        if (val == null) return def;
        try {
            return Integer.parseInt(val.trim());
        } catch (Throwable ignored) {
            return def;
        }
    }

    private static final class Hit {
        final String rule;
        final String layer;
        final String detail;
        final long time;

        Hit(String rule, String layer, String detail, long time) {
            this.rule = rule;
            this.layer = layer;
            this.detail = detail;
            this.time = time;
        }
    }
}
