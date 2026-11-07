package com.example.syshelperblocker;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * SystemHelperBlocker —— 基于 libxposed API 102 的现代 Xposed 模块
 *
 * 目标: com.mobiletools.systemhelper（OPPO 定制机预装 / 中国联通 DM 上报组件）
 *
 * 与旧版（de.robv.android.xposed:api:82）的区别：
 *   - 入口 extends XposedModule，注册在 META-INF/xposed/java_init.list
 *   - 回调改用 onPackageReady(PackageReadyParam)，直接拿 ClassLoader
 *   - hook 改为拦截器链：hook(m).intercept(chain -> ...)，不调 proceed() 即短路
 *   - 元数据走 META-INF/xposed/{module.prop,scope.list}，不再用 AndroidManifest meta-data
 *
 * hook 点（逆向验证过，与 BSH 插件版一致）：
 *   L1 出口 : g1.f.b(String url, byte[] body, byte[] key) : boolean  -> 返回 true，不出网且伪装成功
 *   L2 总闸 : e1.b.a(Context, String regType) : e1.b             -> 返回空 DeviceInfo，不采集
 *   L2 门控 : f1.a.c(Context) / g1.l.a(Context)
 *            GetSignalInfoReceiver.b/c(Context) : boolean         -> false
 *   L3 定位 : GetSignalInfoReceiver.d(回调) : void                -> 不执行
 *   L4 落库 : g1.m.c(String) / g1.e.b(String) : void              -> 不执行 + 启动时清库
 *
 * 注意: 类名是本版本(3.0.1-20250822)的混淆结果，OTA 换版后可能需要更新。
 */
public class MainHook extends XposedModule {

    private static final String TAG = "SHBlocker";
    private static final String PKG = "com.mobiletools.systemhelper";
    private static final String PREF = "sh_blocker_stats";

    /** 与 KernelSU 模块共享的目录（app-specific media，双方都有权限） */
    private static final String SHARED_SUBDIR = "SHBlocker";

    /** 已安装的 hook 摘要，写进 status.json 供内核侧查看 */
    private static volatile String hookSummary = "none";
    private static volatile long lastStatusWrite = 0L;

    /** 各层实际挂载数量（写进 hookSummary） */
    private static volatile int cL1, cL2e, cL2g, cL3, cL4;

    /** 事件日志节流，避免高频 hook 把 events.log 刷爆 */
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> LAST_EVENT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile String todayKey = "";

    /** 配置缓存（启动时从 config.conf 读一次） */
    private static volatile boolean cfgEgress = true;
    private static volatile boolean cfgCollect = true;
    private static volatile boolean cfgLocation = true;
    private static volatile boolean cfgDb = true;
    private static volatile boolean cfgWipe = true;

    /** granular 开关（APP 写入），未写入时由旧 5 个开关回退 */
    private static volatile boolean cfgAnalytics = true;
    private static volatile boolean cfgAdDomains = true;
    private static volatile boolean cfgDoh = true;
    private static volatile boolean cfgMaskOaid = true;
    private static volatile boolean cfgSysLog = true;
    private static volatile boolean cfgBlurLocation = true;
    private static volatile boolean cfgCleanSandbox = true;

    /** 目标包名，供深度 hook 与定时任务抑制复用 */
    static final String SYSTEM_HELPER = PKG;

    private static volatile boolean appReadyDone = false;
    private static volatile long lastConfigCheck = 0L;
    private static final java.util.List<Runnable> REPORTING_LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** e1.b 的无参构造器缓存（L2 总闸每次都要返回一个空实例） */
    private static Constructor<?> emptyDeviceInfoCtor;

    private static volatile MainHook INSTANCE;

    /* ================= 生命周期 ================= */

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        INSTANCE = this;
        log("module loaded: process=" + param.getProcessName()
                + " framework=" + getFrameworkName() + " " + getFrameworkVersionCode()
                + " api=" + getApiVersion() + " libApi=" + LIB_API);
    }

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageReady(@NonNull PackageReadyParam param) {
        if (!PKG.equals(param.getPackageName())) return;
        final ClassLoader cl = param.getClassLoader();
        
        SystemHelperDeepHooks.init(this, cl);
        SystemHelperSchedules.init(this, cl);

        log("enter package " + param.getPackageName() + " pid=" + android.os.Process.myPid());

        try { cL1 = hookEgress(cl); }      catch (Throwable t) { log("L1 fail: " + t); }
        try { cL2e = hookEntry(cl); }      catch (Throwable t) { log("L2 entry fail: " + t); }
        try { cL2g = hookGates(cl); }      catch (Throwable t) { log("L2 gates fail: " + t); }
        try { cL3 = hookLocation(cl); }    catch (Throwable t) { log("L3 fail: " + t); }
        try { cL4 = hookDb(cl); }          catch (Throwable t) { log("L4 fail: " + t); }
        try { hookAppReady(cl); }          catch (Throwable t) { log("app-ready fail: " + t); }

        hookSummary = "L1=" + cL1 + " L2e=" + cL2e + " L2g=" + cL2g + " L3=" + cL3 + " L4=" + cL4;
        log("all hooks installed " + hookSummary + " (wipe/report 会在 Application.onCreate 之后执行)");
    }

    /* ================= L1: 掐断唯一 HTTP 出口 ================= */

    private int hookEgress(ClassLoader cl) throws Throwable {
        Class<?> c = cl.loadClass("g1.f");
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3 && m.getReturnType() == boolean.class
                    && p[0] == String.class && p[1] == byte[].class && p[2] == byte[].class) {
                hook(m).setId("sh_l1").intercept(chain -> {
                    maybeReloadConfig();
                    if (!cfgEgress) return chain.proceed();
                    String url = String.valueOf(chain.getArg(0));
                    byte[] body = (byte[]) chain.getArg(1);
                    int len = (body == null) ? 0 : body.length;
                    long c1 = bump("egress_count", 1);
                    long b1 = bump("egress_bytes", len);
                    maybeWriteStatus();
                    log("L1 blocked #" + c1 + " -> " + url + " (" + len + "B, total " + b1 + "B)");
                    eventThrottled("L1", "L1 egress blocked url=" + url);
                    // 关键：返回 true 才会让上层认为“已上报成功”，否则会写补报库并反复重试
                    return Boolean.TRUE;
                });
                n++;
            }
        }
        log("L1 hooked " + n + " method(s) on " + c.getName());
        return n;
    }

    /* ================= L2 总闸: 调度入口 ================= */

    private int hookEntry(final ClassLoader cl) throws Throwable {
        final Class<?> b = cl.loadClass("e1.b");
        try {
            emptyDeviceInfoCtor = b.getDeclaredConstructor();
            emptyDeviceInfoCtor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            emptyDeviceInfoCtor = null;
            log("L2: e1.b 没有无参构造，改为每次按需构造");
        }
        int n = 0;
        for (Method m : b.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (!m.getName().equals("a") || m.getReturnType() != b) continue;
            if (p.length != 2 || p[0] != Context.class || p[1] != String.class) continue;
            hook(m).setId("sh_l2_entry").intercept(chain -> {
                maybeReloadConfig();
                if (!cfgCollect) return chain.proceed();
                String regType = (String) chain.getArg(1);
                bump("reg_" + (regType == null || regType.isEmpty() ? "empty" : regType), 1);
                long c = bump("l2_short", 1);
                writeStatus();   // 总闸调用不频繁，立即落盘，避免体检看到滞后数据
                log("L2 entry short-circuit regType=" + regType + " (total " + c + ")");
                eventThrottled("L2", "L2 collect short-circuit regType=" + regType);
                // 返回空对象而不是 null，避免调用方 NPE
                return newEmptyDeviceInfo(b, (Context) chain.getArg(0));
            });
            n++;
        }
        log("L2 entry hooked " + n + " method(s): e1.b.a(Context,String)");
        return n;
    }

    @Nullable
    private Object newEmptyDeviceInfo(Class<?> b, Context ctx) {
        try {
            if (emptyDeviceInfoCtor != null) return emptyDeviceInfoCtor.newInstance();
            for (Constructor<?> ctor : b.getDeclaredConstructors()) {
                ctor.setAccessible(true);
                Class<?>[] p = ctor.getParameterTypes();
                Object[] args = new Object[p.length];
                for (int i = 0; i < p.length; i++) {
                    args[i] = (p[i] == Context.class) ? ctx : defaultFor(p[i]);
                }
                try {
                    return ctor.newInstance(args);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("L2 construct empty e1.b fail: " + t);
        }
        return null;
    }

    @Nullable
    private static Object defaultFor(Class<?> t) {
        if (!t.isPrimitive()) return null;
        if (t == boolean.class) return Boolean.FALSE;
        if (t == byte.class) return (byte) 0;
        if (t == char.class) return (char) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        return null;
    }

    /* ================= L2 门控 ================= */

    private int hookGates(ClassLoader cl) throws Throwable {
        int n = 0;
        n += hookFalseWithContext(cl.loadClass("f1.a"), "c", "DeviceInfoReaderBase");
        n += hookFalseWithContext(cl.loadClass("g1.l"), "a", "RegisterUtils");
        Class<?> r = cl.loadClass("com.mobiletools.systemhelper.control.GetSignalInfoReceiver");
        n += hookFalseWithContext(r, null, "GetSignalInfoReceiver");
        return n;
    }

    private int hookFalseWithContext(Class<?> c, @Nullable String name, String tag) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (name != null && !m.getName().equals(name)) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != Context.class || m.getReturnType() != boolean.class) continue;
            try {
                hook(m).setId("sh_gate_" + tag + "_" + m.getName()).intercept(chain -> {
                    maybeReloadConfig();
                    if (!cfgCollect) return chain.proceed();
                    // 不 proceed()，直接返回 false → 采集链整体降级
                    return Boolean.FALSE;
                });
                n++;
                log("L2 gate: " + tag + "#" + m.getName() + "(Context) -> false");
            } catch (Throwable t) {
                log("L2 gate hook fail " + tag + "#" + m.getName() + ": " + t);
            }
        }
        if (n == 0) log("L2 gate miss: " + tag);
        return n;
    }

    /* ================= L3: 定位不启动 ================= */

    private int hookLocation(ClassLoader cl) throws Throwable {
        Class<?> r = cl.loadClass("com.mobiletools.systemhelper.control.GetSignalInfoReceiver");
        int n = 0;
        for (Method m : r.getDeclaredMethods()) {
            if (!m.getName().equals("d") || m.getParameterTypes().length != 1
                    || m.getReturnType() != void.class) continue;
            hook(m).setId("sh_l3").intercept(chain -> {
                maybeReloadConfig();
                if (!cfgLocation) return chain.proceed();
                bump("loc_block", 1);
                log("L3 location start blocked");
                eventThrottled("L3", "L3 location blocked");
                return null;   // void：直接返回 null 即不执行原方法
            });
            n++;
        }
        log("L3 hooked " + n + " method(s)");
        return n;
    }

    /* ================= L4: 禁止写库 ================= */

    private int hookDb(ClassLoader cl) throws Throwable {
        int n = hookVoidString(cl.loadClass("g1.m"), "SignalDB");
        n += hookVoidString(cl.loadClass("g1.e"), "HistoryDB");
        return n;
    }

    private int hookVoidString(Class<?> c, String tag) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0] != String.class || m.getReturnType() != void.class) continue;
            try {
                hook(m).setId("sh_l4_" + tag + "_" + m.getName()).intercept(chain -> {
                    maybeReloadConfig();
                    if (!cfgDb) return chain.proceed();
                    long c4 = bump("db_block", 1);
                    maybeWriteStatus();
                    log("L4 write blocked (" + c4 + ")");
                    eventThrottled("L4", "L4 db write blocked");
                    return null;
                });
                n++;
                log("L4 blocked: " + tag + "#" + m.getName() + "(String)");
            } catch (Throwable t) {
                log("L4 hook fail " + tag + "#" + m.getName() + ": " + t);
            }
        }
        return n;
    }

    /* ================= 等 Application 就绪后再清库/出报告 ================= */

    // onPackageReady 阶段 Application 还没创建，拿不到 Context，
    // 所以清库与统计汇总要挂到 Application.onCreate 之后。
    // 目标应用可能重写 onCreate（DMApplication），两处都挂，用 appReadyDone 去重。
    private void hookAppReady(ClassLoader cl) throws Throwable {
        Method onCreate = Application.class.getDeclaredMethod("onCreate");
        hook(onCreate).setId("sh_app_ready").intercept(chain -> {
            Object result = chain.proceed();
            onAppReady();
            return result;
        });
        log("app-ready hooked: Application.onCreate");
        try {
            Class<?> application = Class.forName(
                    "com.mobiletools.systemhelper.control.DMApplication", false, cl);
            Method dmOnCreate = application.getDeclaredMethod("onCreate");
            dmOnCreate.setAccessible(true);
            hook(dmOnCreate).setId("sh_app_ready_dm").intercept(chain -> {
                Object result = chain.proceed();
                onAppReady();
                return result;
            });
            log("app-ready hooked: DMApplication.onCreate");
        } catch (Throwable t) {
            log("DMApplication.onCreate hook miss: " + t);
        }
    }

    private void onAppReady() {
        if (appReadyDone) return;
        appReadyDone = true;
        ensureConfig();
        if (cfgWipe) {
            try { wipeDatabases(); } catch (Throwable t) { log("wipe fail " + t); }
        }
        report();
        writeStatus();
        appendEvent("boot: hooks=" + hookSummary);
        SystemHelperSchedules.attachIfPossible(appContext());
    }

    /* ================= 清空历史积压库 ================= */

    private void wipeDatabases() {
        Context ctx = appContext();
        if (ctx == null) { log("wipe skipped: no context"); return; }
        String[] dbs = ctx.databaseList();
        if (dbs == null) return;
        for (String db : dbs) {
            String low = db.toLowerCase();
            if (low.contains("signal") || low.contains("dmregister") || low.contains("systemhelper")) {
                boolean ok = ctx.deleteDatabase(db);
                log("wipe: delete " + db + " -> " + ok);
            }
        }
    }

    /* ================= 与 KernelSU 模块的联动 ================= */

    /**
     * 共享目录: /storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/
     *   config.conf  <- 内核侧/用户写，Xposed 读（每层开关）
     *   status.json  <- Xposed 写，内核侧读（hook 摘要 + 拦截统计 + 心跳）
     *   events.log   <- Xposed 追加事件，供内核侧 tail
     */
    @Nullable
    private static File sharedDir() {
        try {
            Context c = appContext();
            if (c == null) return null;
            File[] dirs = c.getExternalMediaDirs();
            if (dirs == null || dirs.length == 0 || dirs[0] == null) return null;
            File d = new File(dirs[0], SHARED_SUBDIR);
            if (!d.exists() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static final String DEFAULT_CONFIG =
        "# SystemHelperBlocker 开关（1=启用，0=关闭）\n" +
        "# 修改后重启目标进程生效（su -c \"am force-stop " + PKG + "\"）\n" +
        "BLOCK_EGRESS=1\n" +
        "BLOCK_COLLECT=1\n" +
        "BLOCK_LOCATION=1\n" +
        "BLOCK_DB=1\n" +
        "WIPE_DB=1\n";

    private static class Config {
        boolean egress = true, collect = true, location = true, db = true, wipe = true;
        // granular 开关；null 表示 config.conf 未写入，回退到旧 5 个开关
        Boolean analytics, adDomains, doh, maskOaid, sysLog, blurLocation, cleanSandbox;

        Config resolveGranular() {
            if (analytics == null) analytics = egress;
            if (adDomains == null) adDomains = egress;
            if (doh == null) doh = egress;
            if (maskOaid == null) maskOaid = collect;
            if (sysLog == null) sysLog = collect;
            if (blurLocation == null) blurLocation = location;
            if (cleanSandbox == null) cleanSandbox = db;
            return this;
        }
    }

    private static Config readConfig() {
        Config c = new Config();
        try {
            File dir = sharedDir();
            if (dir == null) return c.resolveGranular();
            File f = new File(dir, "config.conf");
            if (!f.exists()) {
                writeFile(f, DEFAULT_CONFIG);
                return c.resolveGranular();
            }
            BufferedReader r = new BufferedReader(new FileReader(f));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int i = line.indexOf('=');
                if (i <= 0) continue;
                String k = line.substring(0, i).trim();
                String v = line.substring(i + 1).trim();
                boolean on = v.equals("1") || v.equalsIgnoreCase("true");
                if ("BLOCK_EGRESS".equals(k)) c.egress = on;
                else if ("BLOCK_COLLECT".equals(k)) c.collect = on;
                else if ("BLOCK_LOCATION".equals(k)) c.location = on;
                else if ("BLOCK_DB".equals(k)) c.db = on;
                else if ("WIPE_DB".equals(k)) c.wipe = on;
                else if ("BLOCK_ANALYTICS".equals(k)) c.analytics = on;
                else if ("BLOCK_AD_DOMAINS".equals(k)) c.adDomains = on;
                else if ("BLOCK_DOH".equals(k)) c.doh = on;
                else if ("MASK_OAID".equals(k)) c.maskOaid = on;
                else if ("BLOCK_SYS_LOG".equals(k)) c.sysLog = on;
                else if ("BLUR_LOCATION".equals(k)) c.blurLocation = on;
                else if ("CLEAN_SANDBOX".equals(k)) c.cleanSandbox = on;
            }
            r.close();
        } catch (Throwable t) {
            log("readConfig fail " + t);
        }
        return c.resolveGranular();
    }

    private static void ensureConfig() {
        try {
            Config c = readConfig();
            boolean changed =
                    cfgEgress != c.egress || cfgCollect != c.collect || cfgLocation != c.location
                            || cfgDb != c.db || cfgWipe != c.wipe
                            || cfgAnalytics != c.analytics || cfgAdDomains != c.adDomains
                            || cfgDoh != c.doh || cfgMaskOaid != c.maskOaid
                            || cfgSysLog != c.sysLog || cfgBlurLocation != c.blurLocation
                            || cfgCleanSandbox != c.cleanSandbox;
            cfgEgress = c.egress;
            cfgCollect = c.collect;
            cfgLocation = c.location;
            cfgDb = c.db;
            cfgWipe = c.wipe;
            cfgAnalytics = c.analytics;
            cfgAdDomains = c.adDomains;
            cfgDoh = c.doh;
            cfgMaskOaid = c.maskOaid;
            cfgSysLog = c.sysLog;
            cfgBlurLocation = c.blurLocation;
            cfgCleanSandbox = c.cleanSandbox;
            if (changed) {
                log("config: egress=" + cfgEgress + " collect=" + cfgCollect
                        + " location=" + cfgLocation + " db=" + cfgDb + " wipe=" + cfgWipe
                        + " | analytics=" + cfgAnalytics + " ads=" + cfgAdDomains
                        + " doh=" + cfgDoh + " oaid=" + cfgMaskOaid + " syslog=" + cfgSysLog
                        + " blurLoc=" + cfgBlurLocation + " sandbox=" + cfgCleanSandbox);
                notifyReportingChanged();
            }
        } catch (Throwable t) {
            log("ensureConfig fail " + t);
        }
    }

    /* ================= 深度屏蔽 / 定时任务协作接口 ================= */

    static boolean egressEnabled() { return cfgEgress; }

    static boolean collectEnabled() { return cfgCollect; }

    static boolean locationEnabled() { return cfgLocation; }

    static boolean dbEnabled() { return cfgDb; }

    static boolean wipeEnabled() { return cfgWipe; }

    static boolean reportingBlocked() { return cfgCollect; }

    static void onReportingChanged(Runnable listener) {
        if (listener != null) REPORTING_LISTENERS.add(listener);
    }

    private static void notifyReportingChanged() {
        for (Runnable listener : REPORTING_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable t) {
                log("reporting listener fail " + t);
            }
        }
    }

    /** 包装 hook：每次调用先尝试热重载 config.conf，再按条件决定是否短路。 */
    static Hooker guard(java.util.function.BooleanSupplier condition, Hooker hooker) {
        return chain -> {
            maybeReloadConfig();
            if (condition.getAsBoolean()) return hooker.intercept(chain);
            return chain.proceed();
        };
    }

    /** 2 秒节流地检查 config.conf 是否变化（APP 改开关后无需 force-stop 也能生效）。 */
    static void maybeReloadConfig() {
        long now = System.currentTimeMillis();
        if (now - lastConfigCheck < 2_000L) return;
        lastConfigCheck = now;
        ensureConfig();
    }

    static void info(String message) {
        log(message);
    }

    static void err(String message, Throwable t) {
        log(message + ": " + t);
    }

    /** 供内核侧读取的运行状态（含心跳时间戳） */
    private static void writeStatus() {
        try {
            File dir = sharedDir();
            if (dir == null) return;
            long now = System.currentTimeMillis();
            lastStatusWrite = now;
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(now));
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"module\": \"xposed\",\n");
            sb.append("  \"api\": \"libxposed/").append(LIB_API).append("\",\n");
            sb.append("  \"package\": \"").append(PKG).append("\",\n");
            sb.append("  \"pid\": ").append(android.os.Process.myPid()).append(",\n");
            sb.append("  \"updated\": ").append(now).append(",\n");
            sb.append("  \"updated_readable\": \"").append(ts).append("\",\n");
            sb.append("  \"hooks\": \"").append(hookSummary).append("\",\n");
            sb.append("  \"config\": {")
              .append("\"egress\":").append(cfgEgress).append(",")
              .append("\"collect\":").append(cfgCollect).append(",")
              .append("\"location\":").append(cfgLocation).append(",")
              .append("\"db\":").append(cfgDb).append(",")
              .append("\"wipe\":").append(cfgWipe).append("},\n");
            sb.append("  \"stats\": {\n");
            sb.append("    \"egress_count\": ").append(get("egress_count")).append(",\n");
            sb.append("    \"egress_bytes\": ").append(get("egress_bytes")).append(",\n");
            sb.append("    \"l2_short\": ").append(get("l2_short")).append(",\n");
            sb.append("    \"db_block\": ").append(get("db_block")).append(",\n");
            sb.append("    \"loc_block\": ").append(get("loc_block")).append(",\n");
            sb.append("    \"today_count\": ").append(get("today_count")).append(",\n");
            sb.append("    \"total_count\": ").append(get("total_count")).append(",\n");
            sb.append("    \"reg_F\": ").append(get("reg_F")).append(",\n");
            sb.append("    \"reg_B\": ").append(get("reg_B")).append(",\n");
            sb.append("    \"reg_S\": ").append(get("reg_S")).append(",\n");
            sb.append("    \"reg_T\": ").append(get("reg_T")).append(",\n");
            sb.append("    \"reg_A\": ").append(get("reg_A")).append(",\n");
            sb.append("    \"reg_R\": ").append(get("reg_R")).append("\n");
            sb.append("  }\n");
            sb.append("}\n");
            writeFile(new File(dir, "status.json"), sb.toString());
        } catch (Throwable t) {
            log("writeStatus fail " + t);
        }
    }

    /** 拦截频繁时节流，最多每 3 秒落盘一次状态 */
    private static void maybeWriteStatus() {
        long now = System.currentTimeMillis();
        if (now - lastStatusWrite < 3000) return;
        writeStatus();
    }

    private static void appendEvent(String msg) {
        try {
            File dir = sharedDir();
            if (dir == null) return;
            File f = new File(dir, "events.log");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                    .format(new Date(System.currentTimeMillis()));
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((ts + "  " + msg + "\n").getBytes("UTF-8"));
            fos.close();
            // 超过 256KB 就截断，避免无限增长
            if (f.length() > 262144L) writeFile(f, "");
        } catch (Throwable t) {
            // ignore
        }
    }

    private static void writeFile(File f, String content) {
        try {
            FileOutputStream fos = new FileOutputStream(f, false);
            fos.write(content.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable t) {
            log("writeFile fail " + t);
        }
    }

    /* ================= 工具 ================= */

    @Nullable
    private static Context appContext() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method m = at.getMethod("currentApplication");
            return (Context) m.invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static SharedPreferences sp() {
        Context c = appContext();
        return c == null ? null : c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private static long bump(String key, long delta) {
        try {
            SharedPreferences p = sp();
            if (p == null) return -1;
            long v = p.getLong(key, 0L) + delta;
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            SharedPreferences.Editor editor = p.edit().putLong(key, v);
            if (!day.equals(todayKey)) {
                todayKey = day;
                editor.putLong("today_count", delta);
            } else {
                editor.putLong("today_count", p.getLong("today_count", 0L) + delta);
            }
            editor.putLong("total_count", p.getLong("total_count", 0L) + delta);
            editor.apply();
            // 注意：这里不再立刻落盘 status.json。
            // 一次调用要更新多个计数（如 reg_* + l2_short），若在第一个 bump 里就写，
            // status.json 会停留在“第一个计数已加、第二个还没加”的中间状态。
            // 统一由各 hook 在更新完计数后调用 maybeWriteStatus()。
            return v;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long get(String key) {
        try {
            SharedPreferences p = sp();
            return p == null ? 0L : p.getLong(key, 0L);
        } catch (Throwable t) { return 0L; }
    }

    private static void report() {
        StringBuilder sb = new StringBuilder();
        sb.append("===== stats =====\n");
        sb.append("egress blocked: ").append(get("egress_count"))
          .append(" times, ").append(get("egress_bytes")).append(" bytes\n");
        sb.append("L2 entry short-circuit: ").append(get("l2_short"))
          .append(" | L4 db blocked: ").append(get("db_block")).append("\n");
        sb.append("reg types: F=").append(get("reg_F")).append(" B=").append(get("reg_B"))
          .append(" S=").append(get("reg_S")).append(" T=").append(get("reg_T"))
          .append(" A=").append(get("reg_A")).append(" R=").append(get("reg_R"));
        log(sb.toString());
    }

    /** 统一走框架日志（对应旧的 XposedBridge.log） */
    /** 同一类事件 5 秒内只写一次，避免审计日志被高频调用淹没。 */
    private static void eventThrottled(String tag, String message) {
        long now = System.currentTimeMillis();
        Long last = LAST_EVENT.get(tag);
        if (last != null && now - last < 5_000L) return;
        LAST_EVENT.put(tag, now);
        appendEvent(message);
    }

    private static void log(String msg) {
        MainHook m = INSTANCE;
        if (m != null) {
            m.log(Log.INFO, TAG, msg);
        } else {
            Log.i(TAG, msg);
        }
    }
}
