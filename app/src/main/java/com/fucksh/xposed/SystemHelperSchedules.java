package com.fucksh.xposed;

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
 * <p>目标应用会在开机、换卡、网络变化时通过 {@code g1.a} 注册一批 AlarmManager 定时任务，
 * 到点后由 {@code com.mobiletools.systemhelper.control.*Receiver} 触发上报。开启拦截时取消这些
 * PendingIntent，关闭拦截时按原间隔恢复调度。</p>
 */
final class SystemHelperSchedules {

    static final String RECEIVER_PREFIX = "com.mobiletools.systemhelper.control.";
    static final String[] RECEIVERS = {
            "OnBootReceiver", "SIMStateReceiver", "ScheduleRegisterReceiver",
            "AppInfoRegisterReceiver", "SignalInfoRegisterReceiver", "GetSignalInfoReceiver"
    };

    private final ModuleMain module;
    private final HookConfig config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Method[] schedules;
    private final Method[] intervals;
    private Context context;
    private boolean suspended;

    private SystemHelperSchedules(ModuleMain module, HookConfig config, Class<?> alarms,
                                  Class<?> preferences) throws NoSuchMethodException {
        this.module = module;
        this.config = config;
        schedules = new Method[]{
                method(alarms, "b", void.class, Context.class, long.class),
                method(alarms, "c", void.class, Context.class, long.class),
                method(alarms, "d", void.class, Context.class, long.class),
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
        config.onChange(() -> handler.post(this::update));
    }

    static SystemHelperSchedules create(ModuleMain module, HookConfig config, ClassLoader classLoader) {
        try {
            Class<?> alarms = Class.forName("g1.a", false, classLoader);
            Class<?> preferences = Class.forName("g1.l", false, classLoader);
            return new SystemHelperSchedules(module, config, alarms, preferences);
        } catch (Throwable error) {
            module.log(4, ModuleMain.TAG, "schedules unavailable: " + error);
            return null;
        }
    }

    void attach(Context application) {
        if (application == null) return;
        context = application.getApplicationContext();
        if (Looper.myLooper() == handler.getLooper()) update();
        else handler.post(this::update);
    }

    private void update() {
        if (context == null) return;
        if (config.collect()) {
            if (!suspended) {
                suspended = true;
                cancel();
            }
        } else if (suspended) {
            suspended = false;
            restore();
        }
    }

    private void cancel() {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        int count = 0;
        for (String receiver : RECEIVERS) {
            try {
                Intent intent = new Intent().setClassName(ModuleMain.TARGET_PACKAGE,
                        RECEIVER_PREFIX + receiver);
                PendingIntent operation = PendingIntent.getBroadcast(context, 0, intent,
                        PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
                if (operation != null) {
                    alarms.cancel(operation);
                    count++;
                }
            } catch (Throwable error) {
                module.log(4, ModuleMain.TAG, "cancel alarm " + receiver + " failed", error);
            }
        }
        module.log(4, ModuleMain.TAG, "alarm cancellation requests=" + count);
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
                module.log(4, ModuleMain.TAG, "restore alarm failed", error);
            }
        }
    }

    private static Method method(Class<?> owner, String name, Class<?> result,
                                 Class<?>... parameters) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != result) {
            throw new NoSuchMethodException(owner.getName() + "#" + name);
        }
        method.setAccessible(true);
        return method;
    }
}
