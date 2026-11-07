package com.fucksh.xposed;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;

/**
 * fucksh 纯 libxposed 模块入口。
 *
 * <p>通过 {@code META-INF/xposed/java_init.list} 注册，被 libxposed 框架在目标进程内实例化。
 * 架构参考 ColorOSGmsNetworkGuard：</p>
 *
 * <ul>
 *   <li>目标进程限定为 {@value #TARGET_PACKAGE}；</li>
 *   <li>配置源完全走 {@link HookConfig}（绑定框架 RemotePreferences），不读写 config.conf；</li>
 *   <li>环境与拦截事件完全走 {@link Reporter}（IPC ContentProvider），不落盘 status.json / events.log；</li>
 *   <li>拦截执行完全由 {@link SystemHelperHooks} 在进程内部完成，彻底废弃内核模块与 iptables。</li>
 * </ul>
 */
public final class ModuleMain extends XposedModule {

    public static final String TAG = "fucksh";
    public static final String TARGET_PACKAGE = "com.mobiletools.systemhelper";

    private final HookConfig config = new HookConfig(this);
    private final Reporter reporter = new Reporter(this, config);
    private volatile String currentProcess = "";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        currentProcess = param.getProcessName();
        config.bind();
        config.onSyncRequested(reporter::syncToManager);
        log(4, TAG, "module loaded in process=" + currentProcess
                + " framework=" + getFrameworkName() + " " + getFrameworkVersion()
                + " (code " + getFrameworkVersionCode() + ", api " + getApiVersion() + ")");
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (!TARGET_PACKAGE.equals(packageName)) return;

        config.bind();
        ClassLoader classLoader = param.getClassLoader();
        log(4, TAG, "attaching hooks to " + packageName
                + " first=" + param.isFirstPackage() + " pid=" + android.os.Process.myPid());

        try {
            SystemHelperHooks hooks = new SystemHelperHooks(this, config, reporter, classLoader);
            hooks.install();
        } catch (Throwable error) {
            log(6, TAG, "fatal error installing SystemHelper hooks", error);
        }
    }
}
