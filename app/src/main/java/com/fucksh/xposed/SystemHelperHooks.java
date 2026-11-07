package com.fucksh.xposed;

import android.app.Application;
import android.app.Instrumentation;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.telephony.PreciseDataConnectionState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface.ExceptionMode;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * SystemHelper 的全部 Hook 点。
 *
 * <p>分为两类：</p>
 *
 * <ol>
 *   <li><b>动态特征点</b>：设备信息 {@code e1.b.b()}、基站经纬度 {@code g1.n.a()}、
 *       {@link UsageStatsManager}。前两者由 {@link FeatureLocator} 扫描 DEX 特征串定位，
 *       OTA 重新混淆后仍能命中；定位失败才回退到当前版本的已知混淆名。</li>
 *   <li><b>结构性 Hook</b>：采集总闸、后台 worker、缓存落库、HTTP/HTTPS 出口、
 *       定位门控、广播接收器、定时任务。这些方法名相对稳定，直接按签名挂载。</li>
 * </ol>
 *
 * <p>所有 Hook 在执行时读取 {@link HookConfig} 的最新快照，管理器改开关后立即生效。</p>
 */
final class SystemHelperHooks {

    private static final Set<String> IDENTITY_KEYS = new LinkedHashSet<>(Arrays.asList(
            "IMEI", "IMEI1", "IMEI2", "MEID", "ICCID", "ICCID1", "ICCID2",
            "IMSI", "IMSI1", "IMSI2", "AndroidId", "AndroidID", "SerialNo",
            "OAID", "AAID", "VAID", "MAC", "BSSID", "PairedMacAddress"));
    private static final Set<String> LOCATION_KEYS = new LinkedHashSet<>(Arrays.asList(
            "LONGITUDE", "LATITUDE", "ALTITUDE", "District", "SPEED", "BEARING",
            "BSSID", "SSID", "Address", "Location"));
    private static final Set<String> ANALYTICS_KEYS = new LinkedHashSet<>(Arrays.asList(
            "AppInfo", "AppUsage", "UsageStats", "AppList"));
    private static final Set<String> SANDBOX_KEYS = new LinkedHashSet<>(Arrays.asList(
            "Sandbox", "SandboxId", "TrackId", "TraceId"));

    private static final FeatureLocator.Feature DEVICE_INFO = new FeatureLocator.Feature(
            "device_info",
            new LinkedHashSet<>(Arrays.asList("IMEI1", "ICCID1", "IMSI1")),
            null, "Ljava/lang/String;", Collections.emptyList());
    private static final FeatureLocator.Feature SIGNAL_RECORD = new FeatureLocator.Feature(
            "signal_record",
            new LinkedHashSet<>(Arrays.asList("LONGITUDE", "LATITUDE", "CSIRSRP")),
            null, "Lorg/json/JSONObject;", Collections.emptyList());
    private static final List<FeatureLocator.Feature> FEATURES =
            Arrays.asList(DEVICE_INFO, SIGNAL_RECORD);

    private final ModuleMain module;
    private final HookConfig config;
    private final Reporter reporter;
    private final ClassLoader classLoader;
    private final Map<String, List<String>> points = new LinkedHashMap<>();
    private final Set<String> missing = new LinkedHashSet<>();
    private final AtomicBoolean applicationReady = new AtomicBoolean();
    private final AtomicBoolean featureInstalled = new AtomicBoolean();
    private int hookCount;
    private Constructor<?> emptyDeviceInfoCtor;
    private boolean dynamicFeaturePoints;
    private String featureNote = "";

    SystemHelperHooks(ModuleMain module, HookConfig config, Reporter reporter,
                      ClassLoader classLoader) {
        this.module = module;
        this.config = config;
        this.reporter = reporter;
        this.classLoader = classLoader;
    }

    /** 在 onPackageReady 阶段安装所有不依赖 Context 的 Hook。 */
    void install() {
        installCollectHooks();
        installEgressHooks();
        installDatabaseHooks();
        installLocationHooks();
        installReceiverHooks();
        installDiagnosticsHooks();
        installAlarmHooks();
        installUsageStatsHooks();
        installLifecycleHooks();
        module.log(4, ModuleMain.TAG, "static hooks installed=" + hookCount
                + " missing=" + missing.size());
    }

    /* ================= 采集总闸 ================= */

    private void installCollectHooks() {
        Class<?> info = loadClass("e1.b");
        if (info == null) {
            missing.add("e1.b");
            return;
        }
        try {
            emptyDeviceInfoCtor = info.getDeclaredConstructor();
            emptyDeviceInfoCtor.setAccessible(true);
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "e1.b has no empty constructor", error);
        }

        hookStatic("e1.b", "a", info, emptyDeviceInfo(), Context.class, String.class);
        hookStatic("f1.p", "d", info, emptyDeviceInfo(), Context.class, String.class);
        hook("f1.a", "b", void.class, gated(this::collectBlocked, chain -> null),
                Context.class, info);
        hook("f1.f", "b", void.class, gated(this::collectBlocked, chain -> null),
                Context.class, info);
        hook("f1.a", "o", void.class, gated(this::collectBlocked, chain -> null), Context.class);
        hook("f1.a", "c", boolean.class, gated(this::collectBlocked, chain -> false), Context.class);
        hook("f1.f", "u", boolean.class, gated(this::collectBlocked, chain -> false), Context.class);

        for (String worker : new String[]{"e1.c", "e1.e", "e1.f", "e1.g"}) {
            hook(worker, "run", void.class, gated(this::collectBlocked, chain -> null));
        }
    }

    private Hooker emptyDeviceInfo() {
        return gated(this::collectBlocked, chain -> {
            if (emptyDeviceInfoCtor == null) return null;
            try {
                return emptyDeviceInfoCtor.newInstance();
            } catch (Throwable error) {
                module.log(5, ModuleMain.TAG, "create empty device info failed", error);
                return null;
            }
        });
    }

    /* ================= 出站 ================= */

    private void installEgressHooks() {
        Hooker fakeSuccess = gated(this::egressBlocked, chain -> {
            reporter.hit("sh_egress", "网络", "拦截 SystemHelper 上报请求");
            return Boolean.TRUE;
        });
        hook("g1.f", "f", boolean.class, fakeSuccess, String.class, byte[].class);
        hook("g1.f", "g", boolean.class, fakeSuccess, String.class, byte[].class, byte[].class);
        // 部分版本使用 b(String, byte[], byte[]) 作为统一入口。
        hook("g1.f", "b", boolean.class, fakeSuccess, String.class, byte[].class, byte[].class);
        installUrlFallback();
    }

    /**
     * 混淆名变化时的出站兜底：直接拦截目标进程内的 URL 连接。
     *
     * <p>目标应用只在上报时建连；拦截 {@code URL.openConnection()} 可以在所有版本上
     * 保证不出网，即使 g1.f 的方法名被改掉。</p>
     */
    private void installUrlFallback() {
        try {
            Method open = java.net.URL.class.getDeclaredMethod("openConnection");
            module.hook(open).setId("fucksh-url-open")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!config.egress()) return chain.proceed();
                        Object self = chain.getThisObject();
                        String host = "";
                        if (self instanceof java.net.URL) {
                            host = ((java.net.URL) self).getHost();
                        }
                        reporter.hit("sh_egress_url", "网络", "阻断出站连接 " + host);
                        throw new java.io.IOException("blocked by fucksh");
                    });
            points.computeIfAbsent("egress", key -> new ArrayList<>())
                    .add("java.net.URL#openConnection");
            hookCount++;
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "URL fallback unavailable", error);
        }
    }

    /* ================= 落库 ================= */

    private void installDatabaseHooks() {
        Hooker noop = gated(this::dbBlocked, chain -> {
            reporter.hit("sh_db", "落库", "阻止 SystemHelper 写入本地缓存");
            return null;
        });
        hook("g1.e", "b", void.class, noop, String.class);
        hook("g1.m", "c", void.class, noop, String.class);
    }

    /* ================= 定位 ================= */

    private void installLocationHooks() {
        Hooker noop = gated(this::locationBlocked, chain -> {
            reporter.hit("sh_location", "定位", "跳过基站/经纬度采集");
            return null;
        });
        String receiver = "com.mobiletools.systemhelper.control.GetSignalInfoReceiver";
        hook(receiver, "c", boolean.class, gated(this::locationBlocked, chain -> false), Context.class);
        hook(receiver, "d", boolean.class, gated(this::locationBlocked, chain -> false), Context.class);
        hook(receiver, "onReceive", void.class, gated(this::locationBlocked, chain -> null),
                Context.class, Intent.class);
        // 定位回调入口，部分版本为 void d(callback)。
        for (Method method : declaredMethods(loadClass(receiver))) {
            if (!method.getName().equals("d") || method.getParameterCount() != 1) continue;
            if (method.getReturnType() != void.class) continue;
            try {
                module.hook(method).setId("fucksh-location-callback")
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(noop);
                hookCount++;
            } catch (Throwable error) {
                module.log(4, ModuleMain.TAG, "location callback hook failed", error);
            }
        }
    }

    /* ================= 广播接收器 ================= */

    private void installReceiverHooks() {
        for (String name : SystemHelperSchedules.RECEIVERS) {
            BooleanSupplier condition = name.equals("GetSignalInfoReceiver")
                    ? this::locationBlocked : this::collectBlocked;
            String layer = name.equals("GetSignalInfoReceiver") ? "定位" : "采集";
            hook(SystemHelperSchedules.RECEIVER_PREFIX + name, "onReceive", void.class,
                    gated(condition, chain -> {
                        reporter.hit("sh_receiver", layer, "跳过 " + name + " 触发");
                        return null;
                    }), Context.class, Intent.class);
        }
    }

    /* ================= 通话/数据诊断 ================= */

    private void installDiagnosticsHooks() {
        hook("e1.a", "onCallDisconnectCauseChanged", void.class,
                gated(this::collectBlocked, chain -> null), int.class, int.class);
        hook("e1.a", "onPreciseDataConnectionStateChanged", void.class,
                gated(this::collectBlocked, chain -> null), PreciseDataConnectionState.class);
    }

    /* ================= 定时任务 ================= */

    private void installAlarmHooks() {
        Class<?> alarms = loadClass("g1.a");
        if (alarms == null) {
            missing.add("g1.a");
            return;
        }
        Hooker noop = gated(this::collectBlocked, chain -> {
            reporter.hit("sh_alarm", "采集", "阻止注册定时上报任务");
            return null;
        });
        for (Method method : declaredMethods(alarms)) {
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            if (method.getReturnType() != void.class) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length != 2 && params.length != 3) continue;
            if (params[0] != Context.class) continue;
            if (params[1] != long.class) continue;
            if (params.length == 3 && params[2] != long.class) continue;
            try {
                module.hook(method).setId("fucksh-alarm-" + method.getName())
                        .setExceptionMode(ExceptionMode.PROTECTIVE)
                        .intercept(noop);
                hookCount++;
            } catch (Throwable error) {
                module.log(4, ModuleMain.TAG, "alarm hook failed " + method.getName(), error);
            }
        }
    }

    /* ================= UsageStatsManager ================= */

    private void installUsageStatsHooks() {
        try {
            Method aggregate = UsageStatsManager.class.getDeclaredMethod(
                    "queryAndAggregateUsageStats", long.class, long.class);
            module.hook(aggregate).setId("fucksh-usagestats-aggregate")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!config.collect() && !config.analytics()) return chain.proceed();
                        reporter.hit("sh_appinfo", "采集", "应用使用统计查询返回空结果");
                        return new java.util.HashMap<String, Object>();
                    });
            points.computeIfAbsent("usage_stats", key -> new ArrayList<>())
                    .add("UsageStatsManager#queryAndAggregateUsageStats");
            hookCount++;
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "UsageStatsManager aggregate hook failed", error);
        }
        try {
            Method query = UsageStatsManager.class.getDeclaredMethod(
                    "queryUsageStats", int.class, long.class, long.class);
            module.hook(query).setId("fucksh-usagestats-query")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        if (!config.collect() && !config.analytics()) return chain.proceed();
                        reporter.hit("sh_appinfo", "采集", "应用使用统计查询返回空列表");
                        return new java.util.ArrayList<>();
                    });
            points.computeIfAbsent("usage_stats", key -> new ArrayList<>())
                    .add("UsageStatsManager#queryUsageStats");
            hookCount++;
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "UsageStatsManager query hook failed", error);
        }
    }

    /* ================= 生命周期 / 动态特征点 ================= */

    private void installLifecycleHooks() {
        try {
            Method call = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            module.hook(call).setId("fucksh-app-create")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object argument = chain.getArg(0);
                        if (argument instanceof Application) {
                            onApplicationCreated((Application) argument);
                        }
                        return chain.proceed();
                    });
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "Instrumentation hook failed", error);
        }
        try {
            Method onCreate = Application.class.getDeclaredMethod("onCreate");
            module.hook(onCreate).setId("fucksh-application-oncreate")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Application) onApplicationCreated((Application) self);
                        return result;
                    });
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "Application.onCreate hook failed", error);
        }
        try {
            Class<?> dm = Class.forName("com.mobiletools.systemhelper.control.DMApplication",
                    false, classLoader);
            Method onCreate = dm.getDeclaredMethod("onCreate");
            module.hook(onCreate).setId("fucksh-dmapplication-oncreate")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                        Object result = chain.proceed();
                        Object self = chain.getThisObject();
                        if (self instanceof Application) onApplicationCreated((Application) self);
                        return result;
                    });
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "DMApplication.onCreate hook miss", error);
        }
    }

    private void onApplicationCreated(Application application) {
        if (!applicationReady.compareAndSet(false, true)) return;
        reporter.attach(application);
        installFeaturePoints(application.getApplicationInfo());
        SystemHelperSchedules schedules =
                SystemHelperSchedules.create(module, config, classLoader);
        if (schedules != null) schedules.attach(application);
        if (config.wipe()) wipeDatabases(application);
        reportEnvironment();
        report();
    }

    private void installFeaturePoints(ApplicationInfo info) {
        if (!featureInstalled.compareAndSet(false, true)) return;
        Map<String, List<FeatureLocator.MethodRef>> found;
        try {
            found = FeatureLocator.locate(info, FEATURES);
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "feature scan failed", error);
            found = Collections.emptyMap();
        }

        boolean dynamic = false;
        int installed = 0;
        for (FeatureLocator.MethodRef ref : found.getOrDefault(DEVICE_INFO.id,
                Collections.emptyList())) {
            Method method = FeatureLocator.resolve(ref, classLoader);
            if (method == null) continue;
            hookDeviceInfo(method);
            addPoint(DEVICE_INFO.id, ref.describe());
            installed++;
            dynamic = true;
        }
        for (FeatureLocator.MethodRef ref : found.getOrDefault(SIGNAL_RECORD.id,
                Collections.emptyList())) {
            Method method = FeatureLocator.resolve(ref, classLoader);
            if (method == null) continue;
            hookSignalRecord(method);
            addPoint(SIGNAL_RECORD.id, ref.describe());
            installed++;
            dynamic = true;
        }

        if (installed == 0) {
            Method device = FeatureLocator.resolveKnown(classLoader, "e1.b", "b");
            if (device != null) {
                hookDeviceInfo(device);
                addPoint(DEVICE_INFO.id, "e1.b#b (fallback)");
                installed++;
            }
            Method signal = FeatureLocator.resolveKnown(classLoader, "g1.n", "a");
            if (signal != null) {
                hookSignalRecord(signal);
                addPoint(SIGNAL_RECORD.id, "g1.n#a (fallback)");
                installed++;
            }
            featureNote = "DEX 特征扫描未命中，已回退到已知混淆名";
        } else {
            featureNote = "DEX 特征扫描命中 " + installed + " 个方法";
        }
        dynamicFeaturePoints = dynamic;
        hookCount += installed;
        module.log(4, ModuleMain.TAG, "feature points installed=" + installed
                + " dynamic=" + dynamic + " note=" + featureNote);
    }

    private void hookDeviceInfo(Method method) {
        try {
            module.hook(method).setId("fucksh-device-info")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> filterDeviceInfo(chain.proceed()));
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "device info hook failed", error);
        }
    }

    private void hookSignalRecord(Method method) {
        try {
            module.hook(method).setId("fucksh-signal-record")
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(chain -> filterSignalRecord(chain.proceed()));
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "signal record hook failed", error);
        }
    }

    private Object filterDeviceInfo(Object result) {
        if (!(result instanceof String)) return result;
        boolean blockCollect = config.collect();
        boolean blockLocation = config.location();
        boolean blockAnalytics = config.analytics();
        boolean maskIdentity = config.maskOaid();
        boolean cleanSandbox = config.cleanSandbox();
        if (!blockCollect && !blockLocation && !blockAnalytics && !maskIdentity && !cleanSandbox) {
            return result;
        }
        if (blockCollect) {
            reporter.hit("sh_device_info", "采集", "设备信息采集返回空对象");
            return "{}";
        }
        try {
            JSONObject json = new JSONObject((String) result);
            boolean changed = false;
            if (blockLocation) changed |= clear(json, LOCATION_KEYS);
            if (blockAnalytics) changed |= clear(json, ANALYTICS_KEYS);
            if (maskIdentity) changed |= clear(json, IDENTITY_KEYS);
            if (cleanSandbox) changed |= clear(json, SANDBOX_KEYS);
            if (changed) {
                reporter.hit("sh_device_info", "采集", "清理设备信息中的敏感字段");
                return json.toString();
            }
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "filter device info failed", error);
        }
        return result;
    }

    private Object filterSignalRecord(Object result) {
        if (!(result instanceof JSONObject)) return result;
        boolean blockCollect = config.collect();
        boolean blockLocation = config.location();
        if (!blockCollect && !blockLocation) return result;
        if (blockCollect) {
            reporter.hit("sh_signal", "采集", "基站记录返回空对象");
            return new JSONObject();
        }
        try {
            JSONObject json = (JSONObject) result;
            if (clear(json, LOCATION_KEYS)) {
                reporter.hit("sh_signal", "定位", "移除基站记录中的经纬度与 Wi-Fi");
            }
            return json;
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "filter signal record failed", error);
            return result;
        }
    }

    private static boolean clear(JSONObject json, Set<String> keys) {
        boolean changed = false;
        for (String key : keys) {
            if (!json.has(key)) continue;
            try {
                Object value = json.opt(key);
                if (value instanceof JSONArray) json.put(key, new JSONArray());
                else if (value instanceof JSONObject) json.put(key, new JSONObject());
                else json.put(key, "");
                changed = true;
            } catch (Throwable ignored) {
            }
        }
        return changed;
    }

    /* ================= 清库 / 上报 ================= */

    private void wipeDatabases(Context context) {
        try {
            String[] databases = context.databaseList();
            if (databases == null) return;
            for (String database : databases) {
                String lower = database.toLowerCase();
                if (lower.contains("signal") || lower.contains("dmregister")
                        || lower.contains("systemhelper")) {
                    boolean deleted = context.deleteDatabase(database);
                    module.log(4, ModuleMain.TAG, "wipe " + database + " -> " + deleted);
                }
            }
        } catch (Throwable error) {
            module.log(5, ModuleMain.TAG, "wipe databases failed", error);
        }
    }

    private void reportEnvironment() {
        try {
            reporter.reportEnvironment(module.getFrameworkName(),
                    module.getFrameworkVersion(), module.getFrameworkVersionCode(),
                    module.getApiVersion());
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "report environment failed", error);
        }
    }

    private void report() {
        try {
            reporter.reportPoints(points, hookCount, dynamicFeaturePoints, featureNote);
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "report points failed", error);
        }
    }

    /* ================= 工具 ================= */

    private boolean collectBlocked() {
        return config.collect();
    }

    private boolean locationBlocked() {
        return config.location();
    }

    private boolean dbBlocked() {
        return config.db();
    }

    private boolean egressBlocked() {
        return config.egress();
    }

    private Hooker gated(BooleanSupplier condition, Hooker blocked) {
        return chain -> condition.getAsBoolean() ? blocked.intercept(chain) : chain.proceed();
    }

    private void hook(String owner, String name, Class<?> result, Hooker hooker,
                      Class<?>... params) {
        try {
            Class<?> type = Class.forName(owner, false, classLoader);
            Method method = type.getDeclaredMethod(name, params);
            if (method.getReturnType() != result) {
                missing.add(owner + "#" + name);
                return;
            }
            method.setAccessible(true);
            module.hook(method).setId("fucksh-" + owner + "-" + name)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            hookCount++;
        } catch (Throwable error) {
            missing.add(owner + "#" + name);
        }
    }

    private void hookStatic(String owner, String name, Class<?> result, Hooker hooker,
                            Class<?>... params) {
        try {
            Class<?> type = Class.forName(owner, false, classLoader);
            Method method = type.getDeclaredMethod(name, params);
            if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    || method.getReturnType() != result) {
                missing.add(owner + "#" + name);
                return;
            }
            method.setAccessible(true);
            module.hook(method).setId("fucksh-" + owner + "-" + name)
                    .setExceptionMode(ExceptionMode.PROTECTIVE)
                    .intercept(hooker);
            hookCount++;
        } catch (Throwable error) {
            missing.add(owner + "#" + name);
        }
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, classLoader);
        } catch (Throwable error) {
            return null;
        }
    }

    private static Method[] declaredMethods(Class<?> type) {
        if (type == null) return new Method[0];
        try {
            return type.getDeclaredMethods();
        } catch (Throwable error) {
            return new Method[0];
        }
    }

    private void addPoint(String feature, String method) {
        points.computeIfAbsent(feature, key -> new ArrayList<>()).add(method);
    }
}
