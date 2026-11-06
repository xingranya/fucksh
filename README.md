# SystemHelper Blocker

> 让预装的**联通 DM 上报组件**彻底哑掉：不采集、不定位、不落库、不出网。

[![Xposed API](https://img.shields.io/badge/libxposed-API%20102-brightgreen.svg)](https://github.com/libxposed/api)
[![KernelSU](https://img.shields.io/badge/KernelSU-module-blue.svg)](https://kernelsu.org/)
[![minSdk](https://img.shields.io/badge/minSdk-29-orange.svg)](#兼容性)
[![License](https://img.shields.io/badge/license-MIT-lightgrey.svg)](LICENSE)

---

## 背景

部分运营商定制机预装了一个叫 `com.mobiletools.systemhelper` 的组件（priv-app 权限），
它会把设备的 **IMEI / ICCID / IMSI / 基站信息 / 经纬度 / 已装应用使用时长 / 流量明细**
加密后定时上报到运营商的 DM（Device Management）服务器。

问题在于：**它在 OPPO 定制机上几乎不受用户控制**。
设置里那个"运营商服务改进计划"开关只有 vivo 机型的分支才会读取，OPPO 分支只判断系统开机向导是否完成——
也就是说，开机向导过完，它就开始上报了。

本项目用**三层手段**把这条链路掐断，全部离线、无网络权限、不采集任何数据。

---

## 它拦的是什么

| 项 | 值 |
|---|---|
| 包名 | `com.mobiletools.systemhelper` |
| 版本（逆向目标） | `3.0.1-20250822`（versionCode 31） |
| 安装位置 | `/product/priv-app/SystemHelper-oppo-20250822/` |
| 上报地址 | `http://dm.wo.com.cn:18080/register`（存 SharedPreferences，可被远程改） |
| 传输加密 | `MD5(json)` 作 AES-128 key；AES/ECB/PKCS5 加密 body；RSA-1024 加密 AES key 作为 URL 的 `sign` |

上报分六类（RegType）：`F` 首次开机、`B` 开机、`S` 定时（24h+随机偏移）、`T` SIM 变更、
`A` 应用使用情况、`R` 信号+位置补报。

---

## 三层架构

```
┌─ DNS ─────── dm.wo.com.cn 解析到 127.0.0.1（设备既有的 DNS 过滤，可选）
├─ 内核层 ──── KernelSU 模块 sh_blocker：按 uid 阻断全部 IPv4/IPv6 出站，连 native 层一起拦
└─ 应用层 ──── LSPosed 模块：L1~L4 拦截 HTTP 出口 / 采集 / 定位 / 落库
```

三层互相独立：只装内核层就已经断网，应用层则把采集动作本身也停掉（不产生数据、不写库）。
两层通过共享目录联动，内核模块的「操作」按钮能一次性体检两层的状态。

| 层 | 组件 | 拦什么 | 能单独用吗 |
|---|---|---|---|
| 内核 | KernelSU 模块 `sh_blocker` | uid 10100 的全部出站（含 JNI 发出的） | ✅ 可以 |
| 应用 | LSPosed 模块 `com.example.syshelperblocker` | L1 出口 / L2 总闸与门控 / L3 定位 / L4 落库 | ✅ 可以（落库和采集都停） |

---

## 目录结构

```
.
├── SystemHelperXposed/              # LSPosed 模块（libxposed API 102，Gradle 工程）
│   ├── app/src/main/
│   │   ├── java/.../MainHook.java    # 全部 hook 逻辑
│   │   ├── resources/META-INF/xposed/
│   │   │   ├── java_init.list        # 入口类
│   │   │   ├── module.prop           # minApiVersion / targetApiVersion / staticScope
│   │   │   └── scope.list            # 作用域：com.mobiletools.systemhelper
│   │   └── AndroidManifest.xml       # 只有 label / description
│   └── HANDOVER.md                   # 逆向记录：类映射、调用链、踩坑
├── kernel-module/                   # KernelSU 模块源码
│   ├── module.prop
│   ├── service.sh                   # 探测 uid → iptables/ip6tables REJECT → 60s 自愈
│   ├── action.sh                    # 「操作」按钮：一键体检
│   ├── post-fs-data.sh / uninstall.sh / skip_mount
└── build_module_zip.py              # 打包成可刷入的 zip
```

---

## 体检

装好之后随时跑一次联合体检（等于 KernelSU 管理器里该模块的「操作」按钮）：

```bash
adb shell su -c "sh /data/adb/modules/sh_blocker/action.sh"
```

```
[1] 目标进程
    包名 : com.mobiletools.systemhelper
    uid  : 10100
    pid  : 17148
[2] 内核层规则（iptables / ip6tables）
    IPv4 : OK
    IPv6 : OK
[3] 内核拦截计数（数字涨 = 有请求撞墙）
    IPv4 : 0 包 / 0 字节
    IPv6 : 0 包 / 0 字节
[4] 当前 TCP 连接（应为空）
    (空) 没有任何外连
[5] 模块自愈记录（最近 4 条）
    [2026-11-05 18:26:15] target com.mobiletools.systemhelper uid=10100
    [2026-11-05 18:26:15] iptables: OUTPUT reject uid=10100
[6] Xposed 模块（LSPosed）
    最后心跳 : 2026-11-05 18:44:05   (9s 前)
    hook 摘要: L1=1 L2e=1 L2g=4 L3=1 L4=2
    拦截统计 : 出网 0 次 / 0 字节 | 总闸 1 次 | 写库拦截 0 次
    心跳判定 : ✅ 活跃（10 分钟内）
[结论]
    ✅ 正常：规则在位，当前零外连
```

**怎么读**：

| 现象 | 含义 |
|---|---|
| 规则在、命中数 0、连接表空 | ✅ 最理想：应用层就拦住了，一个包都没发出去 |
| 命中数在涨 | ⚠️ 有路径绕过了应用层，被内核兜住（正常兜底，可查 `events.log`） |
| 规则不见了 | ❌ 被 netd 清了，60 秒守护会自己补回来 |
| `status.json` 心跳超 10 分钟 | ❌ Xposed 层没工作（进程没跑 / 模块没启用） |

---

## 配置

应用层开关放在共享目录，改完重启目标进程即可，**不用重刷模块**：

```bash
adb shell su -c "cat /storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/config.conf"
```

```ini
BLOCK_EGRESS=1     # L1 出口
BLOCK_COLLECT=1    # L2 总闸 + 门控
BLOCK_LOCATION=1   # L3 定位
BLOCK_DB=1         # L4 写库
WIPE_DB=1          # 启动时清空历史积压库
```

同目录下还有两个运行时文件：`status.json`（模块写的心跳+统计，内核侧读）、`events.log`（事件流）。

---

## 工作原理

### 调用链

```
触发（广播 / Alarm / JobService / Binder / Activity）
  → e1.b.a(Context, regType)                    【L2 总闸，唯一调度入口】
     → f1.o.d(Context, regType)                 采集分发
        ├─ f1.a        DeviceInfoReaderBase（13 家厂商适配，反射私有 API 取 5G/VoLTE）
        ├─ e1.d        信号采集（TelephonyCallback PreciseDataConnectionState）
        └─ e1.g / e1.c / e1.f / e1.e   上报线程
             → g1.f.b(String url, byte[] body, byte[] key)   【L1，全 App 唯一 HTTP 出口】
信号链：GetSignalInfoReceiver.onReceive → b()/c() 门控 → d(回调) 启百度定位 → 写 SigInfo → 落库
落库：g1.m.c(String) → signalhelper.db   ／   g1.e.b(String) → systemhelper.db（补报）
```

### hook 点

| 层 | 目标（3.0.1-20250822 的混淆名） | 动作 |
|---|---|---|
| L1 | `g1.f.b(String, byte[], byte[]) : boolean` | 直接返回 `true`（伪装上报成功） |
| L2 总闸 | `e1.b.a(Context, String) : e1.b` | 返回**空对象**，不采集 |
| L2 门控 | `f1.a.c(Context)` / `g1.l.a(Context)` / `GetSignalInfoReceiver.b,c(Context)` | 返回 `false` |
| L3 | `GetSignalInfoReceiver.d(回调) : void` | 不执行，定位不启动 |
| L4 | `g1.m.c(String)` / `g1.e.b(String) : void` | 不执行 + 启动时清库 |

**L1 必须返回 `true` 而不是 `false`** —— 返回 false 会让上层走"落库补报"分支并反复重试，
返回 true 才算真正伪装成功。

### 为什么还需要内核层

百度定位 SDK 的网络收发在 **JNI** 里：全 APK 里 `java.net.HttpURLConnection` 只有 1 个引用（就是 `g1.f`），
`java.net.Socket` 更是零引用，也没有 okhttp / apache-http。
Java 层没有可 hook 的点，只能靠按 uid 的内核阻断兜底——这也是为什么要做双份实现而不是一个 Xposed 模块了事。

### 技术栈

- 应用层用官方现代 API [`libxposed/api`](https://github.com/libxposed/api)（API 102），
  不再依赖已停止维护的 `de.robv.android.xposed:api:82`；
- hook 采用拦截器链模型（`hook(m).intercept(chain -> ...)`），不调 `chain.proceed()` 即短路。

---

## 构建

Xposed 模块（需要 JDK 17 + Android SDK 34）：

```bash
cd SystemHelperXposed
echo "sdk.dir=/path/to/android-sdk" > local.properties   # 指向本机 Android SDK
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 工程自带 Gradle 8.7 wrapper，`distributionUrl` 指向腾讯云镜像（国内直接可用）。
> 海外或镜像失效时，把 `gradle/wrapper/gradle-wrapper.properties` 里那行改回
> `https\://services.gradle.org/distributions/gradle-8.7-bin.zip` 即可。

KernelSU 模块：

```bash
python build_module_zip.py     # 读取 module.prop 的 version，输出 sh_blocker_<version>.zip
```

---

## 兼容性

| 项 | 实测 | 说明 |
|---|---|---|
| 设备 | OPPO PLP110 / Android 16 | |
| 目标 App | 3.0.1-20250822 | 类名取自该版本 |
| Xposed 框架 | LSPosed 2.2.1（API 102） | 需要支持 libxposed 的版本 |
| root 方案 | KernelSU | Magisk 下把 `ksud module install` 换掉即可 |
| 模块 minSdk | 29 | `onPackageReady` 回调要求 Android 10+ |

**已知限制**：

1. `g1.f / e1.b / f1.a / g1.l / g1.m / g1.e` 都是 `3.0.1-20250822` 的混淆名，**OTA 升级后大概率失效**，
   需要重新定位。想抗变更可以接 [DexKit](https://github.com/LuckyPray/DexKit) 按字符串常量搜索。
2. 内核层是按 uid 全量阻断，不做协议识别——目标应用一旦换 uid（重装/换版本）需要重启让 `service.sh` 重新探测。
3. 只覆盖这一个包，不针对任何其他应用。

---

## 验证方法

不想等到定时上报（默认 24 小时）才验证，可以直接触发目标 App 自带的测试入口：

```bash
adb shell su -c "am start -n com.mobiletools.systemhelper/.Activity.TestActivity"
```

它内部会调用 `e1.b.a()`，日志里看到下面这行就说明链路当场断了：

```
[SHBlocker] L2 entry short-circuit regType= (total 1)
```

模块日志在 LSPosed 日志里：

```bash
adb shell su -c "grep -a SHBlocker /data/adb/lspd/log/modules_*.log | tail -30"
```

---

## 免责声明

- 本项目用于**自有设备上的隐私控制**：阻止预装组件在用户不知情的情况下采集并外发设备信息。
- 只对 `com.mobiletools.systemhelper` 生效，不修改任何系统文件，不联网，不上传任何数据。
- 使用需要 root，**后果自负**；不同 ROM / 版本可能有差异，请先备份。
- 请勿用于规避运营商合约、伪造设备信息或任何违法用途。

---

## 许可证

[MIT](LICENSE)
