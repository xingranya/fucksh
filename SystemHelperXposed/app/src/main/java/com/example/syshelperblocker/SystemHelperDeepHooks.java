package com.example.syshelperblocker;

import android.content.Context;
import android.content.Intent;
import android.telephony.PreciseDataConnectionState;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

import io.github.libxposed.api.XposedInterface.Hooker;

/**
 * SystemHelper 深度屏蔽点。
 *
 * 思路参考 cvhhji/ColorOSGmsNetworkGuard 的 SystemHelperHooks（按其目标方法重新实现，
 * 不使用对方源码），覆盖采集总闸、后台 worker、缓存落库、HTTP/HTTPS 上报、
 * 通话/数据诊断、定位门控与定时任务入口。
 *
 * 所有 hook 都在调用时读取当前 config.conf 开关，关闭对应开关即恢复原方法。
 */
final class SystemHelperDeepHooks {

    private static final String TAG = "fucksh.deep";
    private static int installed;

    private SystemHelperDeepHooks() {
    }

    static void init(MainHook module, ClassLoader loader) {
        installed = 0;

        // 1. 设备信息采集入口：f1.p.d(Context, String) 返回 e1.b，改为空对象
        try {
            Class<?> info = Class.forName("e1.b", false, loader);
            Constructor<?> constructor = info.getDeclaredConstructor();
            constructor.setAccessible(true);
            hook(module, loader, "f1.p", "d", info, chain -> {
                try {
                    return constructor.newInstance();
                } catch (Throwable error) {
                    module.err("deep empty device info", error);
                    return null;
                }
            }, Context.class, String.class);
            for (String reader : new String[]{"f1.a", "f1.f"}) {
                hookGated(module, loader, reader, "b", void.class,
                        MainHook::collectEnabled, chain -> null, Context.class, info);
            }
        } catch (Throwable error) {
            module.err("deep device collector", error);
        }

        // 2. 采集门控与注册入口
        hookGated(module, loader, "f1.a", "o", void.class,
                MainHook::collectEnabled, chain -> null, Context.class);
        hookGated(module, loader, "f1.a", "c", boolean.class,
                MainHook::collectEnabled, chain -> false, Context.class);
        hookGated(module, loader, "f1.f", "u", boolean.class,
                MainHook::collectEnabled, chain -> false, Context.class);

        // 3. 后台 worker
        for (String worker : new String[]{"e1.c", "e1.e", "e1.f", "e1.g"}) {
            hookGated(module, loader, worker, "run", void.class,
                    MainHook::collectEnabled, chain -> null);
        }

        // 4. 历史 / 信号缓存落库
        hookGated(module, loader, "g1.e", "b", void.class,
                MainHook::dbEnabled, chain -> null, String.class);
        hookGated(module, loader, "g1.m", "c", void.class,
                MainHook::dbEnabled, chain -> null, String.class);

        // 5. HTTP / HTTPS 上报出口：返回 true 伪装成功，避免目标反复补报
        hookGated(module, loader, "g1.f", "f", boolean.class,
                MainHook::egressEnabled, chain -> true, String.class, byte[].class);
        hookGated(module, loader, "g1.f", "g", boolean.class,
                MainHook::egressEnabled, chain -> true, String.class, byte[].class, byte[].class);

        // 6. 通话 / 数据连接诊断
        hookGated(module, loader, "e1.a", "onCallDisconnectCauseChanged", void.class,
                MainHook::collectEnabled, chain -> null, int.class, int.class);
        hookGated(module, loader, "e1.a", "onPreciseDataConnectionStateChanged", void.class,
                MainHook::collectEnabled, chain -> null, PreciseDataConnectionState.class);

        // 7. 定位门控
        String receiver = SystemHelperSchedules.RECEIVER_PREFIX + "GetSignalInfoReceiver";
        for (String gate : new String[]{"c", "d"}) {
            hookGated(module, loader, receiver, gate, boolean.class,
                    MainHook::locationEnabled, chain -> false, Context.class);
        }

        // 8. 定时任务入口（g1.a 的调度方法）
        for (String name : new String[]{"a", "e", "f"}) {
            hookGated(module, loader, "g1.a", name, void.class,
                    MainHook::collectEnabled, chain -> null, Context.class, long.class, long.class);
        }
        for (String name : new String[]{"b", "c", "d"}) {
            hookGated(module, loader, "g1.a", name, void.class,
                    MainHook::collectEnabled, chain -> null, Context.class, long.class);
        }

        // 9. 六个 control.*Receiver 的 onReceive
        for (String name : SystemHelperSchedules.RECEIVERS) {
            hookGated(module, loader, SystemHelperSchedules.RECEIVER_PREFIX + name,
                    "onReceive", void.class, MainHook::collectEnabled, chain -> null,
                    Context.class, Intent.class);
        }

        module.info("deep hooks installed=" + installed);
        Log.d(TAG, "deep hooks installed=" + installed);
    }

    private static void hookGated(
            MainHook module,
            ClassLoader loader,
            String className,
            String methodName,
            Class<?> returnType,
            BooleanSupplier condition,
            Hooker hooker,
            Class<?>... parameterTypes
    ) {
        hook(module, loader, className, methodName, returnType,
                module.guard(condition, hooker), parameterTypes);
    }

    private static void hook(
            MainHook module,
            ClassLoader loader,
            String className,
            String methodName,
            Class<?> returnType,
            Hooker hooker,
            Class<?>... parameterTypes
    ) {
        try {
            Class<?> target = Class.forName(className, false, loader);
            Method method = target.getDeclaredMethod(methodName, parameterTypes);
            if (method.getReturnType() != returnType) {
                module.info("deep skip " + className + "#" + methodName
                        + " return=" + method.getReturnType().getName());
                return;
            }
            method.setAccessible(true);
            module.hook(method)
                    .setId("fucksh_deep_" + className + "_" + methodName)
                    .intercept(hooker);
            installed++;
            Log.d(TAG, "hooked " + className + "#" + methodName);
        } catch (Throwable error) {
            module.info("deep miss " + className + "#" + methodName + ": " + error);
        }
    }
}
