package com.example.syshelperblocker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * SystemHelper 定时任务抑制。
 *
 * 目标应用会在启动/换卡/网络变化时注册一批 AlarmManager 定时任务；
 * 屏蔽开启时取消这些 PendingIntent，并在关闭屏蔽后恢复调度。
 * 思路参考 cvhhji/ColorOSGmsNetworkGuard 的 SystemHelperSchedules。
 */
final class SystemHelperSchedules {

    static final String RECEIVER_PREFIX = "com.mobiletools.systemhelper.control.";
    static final String[] RECEIVERS = {
            "OnBootReceiver", "SIMStateReceiver", "ScheduleRegisterReceiver",
            "AppInfoRegisterReceiver", "SignalInfoRegisterReceiver", "GetSignalInfoReceiver"
    };

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile SystemHelperSchedules instance;

    private final MainHook module;
    private final Method[] schedules;
    private final Method[] intervals;
    private Context context;
    private boolean suspended;

    private SystemHelperSchedules(MainHook module, Class<?> alarms, Class<?> preferences)
            throws NoSuchMethodException {
        this.module = module;
        schedules = new Method[]{
                method(alarms, "b", void.class, Context.class, long.class),
                method(alarms, "c", void.class, Context.class, long.class),
                method(alarms, "f", void.class, Context.class, long.class, long.class),
                method(alarms, "e", void.class, Context.class, long.class, long.class),
                method(alarms, "a", void.class, Context.class, long.class, long.class)
        };
        intervals = new Method[]{
                method(preferences, "m", long.class, Context.class),
                method(preferences, "h", long.class, Context.class),
                method(preferences, "l", long.class, Context.class),
                method(preferences, "g", long.class, Context.class),
                method(preferences, "j", long.class, Context.class),
                method(preferences, "f", long.class, Context.class),
                method(preferences, "c", long.class, Context.class)
        };
    }

    /** 在目标进程里安装：hook DMApplication.onCreate 后接管定时任务。 */
    static void init(MainHook module, ClassLoader loader) {
        try {
            SystemHelperSchedules schedules = new SystemHelperSchedules(
                    module,
                    Class.forName("g1.a", false, loader),
                    Class.forName("g1.l", false, loader)
            );
            instance = schedules;
            module.onReportingChanged(schedules::apply);
            Class<?> application = Class.forName(
                    "com.mobiletools.systemhelper.control.DMApplication", false, loader);
            Method onCreate = application.getDeclaredMethod("onCreate");
            onCreate.setAccessible(true);
            module.hook(onCreate).setId("fucksh_schedules_attach").intercept(chain -> {
                Object result = chain.proceed();
                schedules.attach((Context) chain.getThisObject());
                return result;
            });
            module.info("schedules hooked DMApplication.onCreate");
        } catch (Throwable error) {
            module.err("schedules init", error);
        }
    }

    /** 兜底：如果 DMApplication 类名变化，在 Application.onCreate 后调用。 */
    static void attachIfPossible(Context context) {
        SystemHelperSchedules current = instance;
        if (current != null && context != null) {
            current.attach(context);
        }
    }

    private void attach(Context rawContext) {
        if (rawContext == null) return;
        context = rawContext.getApplicationContext() != null
                ? rawContext.getApplicationContext() : rawContext;
        apply();
    }

    private void apply() {
        if (context == null) return;
        if (module.reportingBlocked()) {
            if (!suspended) {
                suspended = true;
                cancel();
            }
        } else if (suspended) {
            suspended = false;
            MAIN_HANDLER.post(this::restore);
        }
    }

    private void cancel() {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            module.err("alarm service unavailable", new IllegalStateException("AlarmManager null"));
            return;
        }
        int count = 0;
        for (String receiver : RECEIVERS) {
            try {
                Intent intent = new Intent()
                        .setClassName(MainHook.SYSTEM_HELPER, RECEIVER_PREFIX + receiver);
                PendingIntent operation = PendingIntent.getBroadcast(
                        context, 0, intent,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (operation != null) {
                    alarms.cancel(operation);
                    count++;
                }
            } catch (Throwable error) {
                module.err("cancel alarm " + receiver, error);
            }
        }
        module.info("alarm cancellation requests=" + count);
    }

    private void restore() {
        int index = 0;
        for (int i = 0; i < schedules.length; i++) {
            try {
                if (i == 0) {
                    schedules[i].invoke(null, context, 240_000L);
                } else if (i == 1) {
                    schedules[i].invoke(null, context, intervals[index++].invoke(null, context));
                } else {
                    Method first = intervals[index++];
                    Method second = intervals[index++];
                    schedules[i].invoke(null, context,
                            first.invoke(null, context), second.invoke(null, context));
                }
            } catch (Throwable error) {
                module.err("restore alarm " + schedules[i].getName(), error);
            }
        }
        module.info("alarms restored");
    }

    private static Method method(Class<?> owner, String name, Class<?> returnType, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != returnType) {
            throw new NoSuchMethodException(name + " return " + method.getReturnType());
        }
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(name + " is not static");
        }
        method.setAccessible(true);
        return method;
    }
}
