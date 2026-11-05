# SystemHelperBlocker（Xposed / LSPosed 模块）

拦截 `com.mobiletools.systemhelper`（联通 DM 上报组件）的采集与外发：
**不采集 / 不定位 / 不落库 / 不出网**。

- 框架：LSPosed 2.x，基于官方现代 API [`libxposed/api`](https://github.com/libxposed/api) **102**
- 入口：`com.example.syshelperblocker.MainHook`（`extends XposedModule`）
- 作用域：`com.mobiletools.systemhelper`（`META-INF/xposed/scope.list`，静态作用域）

完整背景、架构与安装说明见仓库根目录的 [README](../README.md)。

## 一、hook 点

类名是目标 App `3.0.1-20250822` 的混淆结果，OTA 后需要重新定位。

| 层 | 目标 | 动作 |
|---|---|---|
| L1 出口 | `g1.f.b(String url, byte[] body, byte[] key) : boolean` | 返回 `true` —— 不出网且伪装成功 |
| L2 总闸 | `e1.b.a(Context, String regType) : e1.b` | 返回空对象，采集链不启动 |
| L2 门控 | `f1.a.c(Context)` / `g1.l.a(Context)` / `GetSignalInfoReceiver.b,c(Context)` | 返回 `false` |
| L3 定位 | `GetSignalInfoReceiver.d(回调) : void` | 不执行，百度定位不启动 |
| L4 落库 | `g1.m.c(String)` / `g1.e.b(String) : void` | 不执行 + 启动时清库 |

L1 必须返回 `true`：返回 `false` 会让上层判定"上报失败"而写补报库并反复重试。

## 二、编译

### Android Studio（推荐）

1. 用 Android Studio 打开本目录（AGP 8.1 / Gradle 8.x / JDK 17）
2. `Build > Build Bundle(s)/APK(s) > Build APK(s)`
3. 产物：`app/build/outputs/apk/debug/app-debug.apk`

### 命令行

```bash
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

依赖只有两个，且都是 `compileOnly`（运行期由 LSPosed 提供）：

```gradle
compileOnly 'io.github.libxposed:api:102.0.0'
compileOnly 'androidx.annotation:annotation:1.9.1'
```

## 三、安装

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 首次需要在 LSPosed 里启用一次（或 lspctl module enable --user=0 com.example.syshelperblocker）
adb shell su -c "am force-stop com.mobiletools.systemhelper"
```

装好后 `status.json` 会写到共享目录，内核模块的体检脚本能读到 hook 摘要和拦截统计：

```bash
adb shell su -c "cat /storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/status.json"
```

## 四、验证

```bash
# 触发目标 App 自带的测试入口，它会调用 e1.b.a()
adb shell su -c "am start -n com.mobiletools.systemhelper/.Activity.TestActivity"
# 日志出现下面这行即说明链路已断
adb shell su -c "grep -a SHBlocker /data/adb/lspd/log/modules_*.log | tail -5"
#   [SHBlocker] L2 entry short-circuit regType= (total 1)
```

## 五、与内核层联动

本模块与 KernelSU 模块 `sh_blocker` 共用 `/storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/`：

| 文件 | 谁写 | 谁读 |
|---|---|---|
| `config.conf` | 内核侧生成 / 用户编辑 | 本模块（5 个开关） |
| `status.json` | 本模块（心跳 + hook 摘要 + 统计，最多 3 秒落盘一次） | 内核侧体检脚本 |
| `events.log` | 本模块追加（超 256KB 自动清空） | 内核侧 `tail` |
