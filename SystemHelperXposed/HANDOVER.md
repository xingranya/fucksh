# SystemHelper 拦截工程 · 交接文档

> 生成时间：2026-11-05
> 目标设备：OPPO 定制机（型号 PLP110），Android 13
> 目标应用：`com.mobiletools.systemhelper`（系统 priv-app，`/product/priv-app/SystemHelper-oppo-20250822/`）

---

## 0. 一句话概括

这是一个**中国联通定制的终端信息上报组件**（DM = Device Management），会把设备的 IMEI / ICCID / IMSI / 基站信息 / **经纬度** / 应用使用时长 / 流量明细加密上报到 `http://dm.wo.com.cn:18080/register`。
本工程用**三层手段**（DNS 屏蔽 / 内核 iptables / 应用层 Hook）把它彻底哑化。

> **2026-11-05 更新**：应用层已迁移到官方 [libxposed API 102](https://github.com/libxposed/api)。
> 本文里 `setResult(x)` 的旧表述，在新 API 中对应「拦截器不调 `chain.proceed()`、直接 `return x`」；
> `XposedHelpers` 换成 `ClassLoader.loadClass`，入口类从 `IXposedHookLoadPackage` 改为 `extends XposedModule`。
> hook 点本身（类名/方法签名/返回值要求）完全没有变。

---

## 1. 目标应用功能认定

| 项 | 值 |
|---|---|
| 包名 | `com.mobiletools.systemhelper` |
| 版本 | 3.0.1-20250822（versionCode 31） |
| 上报地址 | `http://dm.wo.com.cn:18080/register`（默认值，存于 SharedPreferences `serverAddress`，可被远程改） |
| 传输加密 | MD5(json) 作 AES-128 key；AES/ECB/PKCS5 加密 body；RSA(1024) 加密 AES key 作为 URL 的 `sign` |
| 证书 | `assets/pub.cer`，Subject/Issuer = `C=CN, ST=BJ, L=BJ, O=UNICOM, OU=UNICOM, CN=UNICOM` |
| 定位 | 内嵌百度定位 SDK 精简版（`com.baidu.location.f` 服务跑在 `:remote` 进程） |

### 上报的三类数据

| RegType | 含义 | 触发者 |
|---|---|---|
| F | 首次开机 | `OnBootReceiver` |
| B | 开机（延迟 4 分钟） | `OnBootReceiver` |
| S | 定时（默认 24h，带随机偏移） | `ScheduleRegisterReceiver` |
| T | SIM 卡变更（ICCID 变化） | `SIMStateReceiver` / `ConnectStateReceiver` |
| A | 已装应用使用情况（appLabel/pkgName/runTime/移动流量/WiFi 流量） | `AppInfoRegisterReceiver` |
| R | 信号+位置补报（从本地库批量回放） | `SignalInfoRegisterReceiver` |

### 上报字段（逆向自 `e1.b.b()` 与 `g1.n.a()`）

- **DeviceInfo**：Manuf / Model / HWVersion / SWVersion / OS / OSVersion / IMEI1,2 / ICCID1,2 / IMSI1,2 / MNC1,2 / NetworkType1,2 / TAC1,2 / CID1,2 / PCI1,2 / VolteEnabled1,2 / 5GEnabled1,2 / VoNREnable1,2 / DataSlot / AccType / IPv6 / DeviceType / SpecVersion=5.0 / ClientVersion=3.0.1
- **SigInfo**：Time / IMEI / ICCID / IMSI / MNC / TAC / CID / PCI / NetworkType(SA/NSA/LTE) / ARFCN / Bands / Bandwidth / RSRP / RSRQ / SINR / CSIRSRP / CSIRSRQ / CSISINR / CQI / Uspeed / Dspeed / CallDisconnectCause / DataFailCause / EPSFallback / **LONGITUDE / LATITUDE / ALTITUDE / SPEED / BEARING**
- **AppInfo**：每个已装应用的 appLabel / pkgName / runTime / rx1,tx1,rx2,tx2 / totalDataWiFi

### 用户同意门控（重要，且名称有误导性）

| 分支 | 判断逻辑 | 实际含义 |
|---|---|---|
| 非 vivo（本站 OPPO） | `f1.a.c()` / `GetSignalInfoReceiver.c()` 只查 `Settings.Global.device_provisioned` | **只要开机向导过完就上报**，完全不看"运营商服务改进计划"开关 |
| vivo | `g1.l.a()` / `GetSignalInfoReceiver.b()` 额外查 `Settings.Secure.operator_service_improve_plan` | 才真正受用户开关控制 |

> 结论：在 OPPO 定制机上，用户在设置里关掉相关开关**并不能**阻止采集上报。

---

## 2. 调用链与拦截点

```
触发(广播/Alarm/JobService/Binder/Activity)
  → e1.b.a(Context, regType)            ← 【L2 总闸】唯一调度入口
     → f1.o.d(Context, regType)         ← DeviceManager，采集分发
        ├─ k() i() j()  网络/SIM 变化检测
        ├─ f1.a   DeviceInfoReaderBase（13 厂商适配，反射私有 API 取 5G 状态）
        ├─ e1.d   信号采集（TelephonyCallback PreciseDataConnectionState）
        └─ e1.g / e1.c / e1.f / e1.e  上报线程
             → g1.f.b(String url, byte[] body, byte[] key)   ← 【L1】唯一 HTTP 出口
                （全 App 只有 e1.g.a 和 e1.e.a 两个调用者）

信号链路：GetSignalInfoReceiver.onReceive
  → 【L2 门控】b()/c() 返回 false 直接放弃
  → 【L3】d(回调) 启动百度定位 → 经纬度写入 SigInfo → 落库

落库：g1.m.c(String)（signalhelper.db / signal 表）
      g1.e.b(String)（systemhelper.db / dmregister 补报表）
```

### 关键类索引

| 类 | 作用 |
|---|---|
| `e1.b` | 调度入口 + DeviceInfo JSON 组装（`b()` 方法 251 行） |
| `f1.o` | DeviceManager：regType 分发、厂商判断、SIM 变化、匿名化 |
| `f1.a` | DeviceInfoReaderBase：设备/网络信息读取，厂商私有 API 反射 |
| `g1.n` | SignalInfo JSON 组装（含经纬度） |
| `g1.b` | AppInfo JSON 组装 |
| `g1.f` | HttpHelper：唯一 HTTP 出口 |
| `g1.c` | 加密（MD5 / AES / RSA） |
| `g1.l` | RegisterUtils：配置、权限门控、服务器地址 |
| `g1.d` | 全局静态配置（默认 URL、各种间隔） |
| `e1.g` / `e1.e` | RegisterThread（实时）/ HistoryPostThread（补报） |
| `g1.a` | AlarmHelper：定时调度 |
| `g1.m` / `g1.e` | SignalDatabaseHelper / HistoryDatabaseHelper |

---

## 3. 三层防护架构

| 层 | 由谁负责 | 拦什么 | 状态 |
|---|---|---|---|
| DNS | 设备上已有的 DNS 过滤模块 | `dm.wo.com.cn` 被解析成 `127.0.0.1` | 既有 |
| 内核 | KernelSU 模块 `sh_blocker` | uid 10100 的全部 IPv4/IPv6 出站（**含 native 层**） | 已上线 |
| 应用 | LSPosed 模块 `com.example.syshelperblocker` | L1~L4：出口、采集、定位、落库 | 已上线 |

### 应用层 L1~L4 明细

| 层 | 目标 | 动作 | 说明 |
|---|---|---|---|
| L1 | `g1.f.b(String,byte[],byte[]) : boolean` | `setResult(true)` | **必须返回 true**：返回 false 会让上层写 dmregister 补报库并反复重试 |
| L2 总闸 | `e1.b.a(Context, String) : e1.b` | `setResult(空对象)` | 返回空对象而非 null，避免 TestActivity 调 `.b()` NPE |
| L2 门控 | `f1.a.c(Context)` / `g1.l.a(Context)` / `GetSignalInfoReceiver.b,c(Context)` | `setResult(false)` | 采集降级 |
| L3 | `GetSignalInfoReceiver.d(回调) : void` | `setResult(null)` | 定位不启动 |
| L4 | `g1.m.c(String)` / `g1.e.b(String)` | `setResult(null)` | 不写库；启动时另调 `deleteDatabase` 清空历史积压 |

---

## 4. 产物清单

| 产物 | 位置 | 说明 |
|---|---|---|
| KernelSU 模块 | `sh_blocker_1.0.zip` | 内核层 uid 阻断 + 联动体检脚本（源码在仓库 `kernel-module/`） |
| Xposed 工程 | 仓库 `SystemHelperXposed/` | Android Studio 工程，编译出独立 Xposed 模块 |
| 体检脚本 | 内核模块内 `action.sh` | KernelSU 管理器「操作」按钮调用同一套逻辑 |
| LSPosed 模块 APK | `app/build/outputs/apk/debug/app-debug.apk` | 源码见 `app/src/main/` |

### KernelSU 模块结构

```
sh_blocker/
├── module.prop        id=sh_blocker  v1.3  versionCode=4
├── post-fs-data.sh    仅记日志
├── service.sh         探测 uid → iptables/ip6tables REJECT → 每 60s 自愈
├── action.sh          「操作」按钮：6 段体检 + 自动补规则
├── uninstall.sh       卸载时删规则
└── skip_mount
```

### Xposed 工程结构

```
SystemHelperXposed/
├── settings.gradle / build.gradle / gradle.properties
└── app/
    ├── build.gradle                     compileOnly 'de.robv.android.xposed:api:82'
    └── src/main/
        ├── AndroidManifest.xml          xposedmodule / xposedscope
        ├── assets/xposed_init           com.example.syshelperblocker.MainHook
        ├── res/values/arrays.xml        作用域预填 com.mobiletools.systemhelper
        └── java/.../MainHook.java       446 行
```

---

## 5. 两边联动

共享目录（App 与 root 都能访问）：

```
/storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/
├── config.conf   ← 内核侧生成 / 用户编辑，Xposed 读（5 个开关）
├── status.json   ← Xposed 写（心跳 + hook 摘要 + 统计，最多 3 秒落盘一次）
└── events.log    ← Xposed 追加，超 256KB 自动清空
```

开关项（改完需重启目标进程）：

```ini
BLOCK_EGRESS=1     # L1 出口
BLOCK_COLLECT=1    # L2 总闸 + 门控
BLOCK_LOCATION=1   # L3 定位
BLOCK_DB=1         # L4 写库
WIPE_DB=1          # 启动清库
```

---

## 6. 踩坑记录（复现时最省时间的部分）

1. **`hookBefore(Method)` 在早期的脚本插件环境里挂 `e1.b.a` 不触发**，但同一个方法用 `hookAllMethodsBefore(Class,"a")` 就能挂上（L1 挂 `g1.f.b` 用 Method 版却正常）。现象是「日志显示已挂载，但回调一次都不进」。解决办法：总闸改用 `hookAllMethodsBefore`。（迁移到 libxposed API 102 后没有这个问题，见下节）

2. **`skipWith(true/false)` 不是"改返回值"，而是"不执行原方法"**。要拦截必须有这个语义；若用 after-hook 改返回值，HTTP 请求已经发出去了。

3. **L1 必须返回 `true` 而不是 `false`**。`e1.g.c()` 里 `if (a(bVar))` 为 false 会走 `"sava to database!"` 分支写补报库，之后反复重试。返回 true 伪装成功才干净。

4. **`hookBefore(Method)` 对 boot class 不生效**（本环境实测）：hook `java.net.Socket.connect` 句柄正常返回，但回调从不触发，导致 socket 层兜底失败。另外 `hookMethodBefore(Class,String,cb)` 这个重载在 BSH 里根本没注册（报 `Command not found`）。

5. **BSH 里 `findMethod().usingStrings()` 会卡死插件加载线程**（进程活着但插件初始化停住，日志断在半路）。方法级搜索要么不用，要么改用 `findClass().usingStrings()` 类级搜索 + 反射遍历。

6. **BSH 方法内访问脚本级变量不可靠**：`isBlockedTarget()` 里读 `BLOCKED_PAT` 恒失败，改用函数内局部变量 + `setAdditionalField` 后才正常。

7. **插件热重载时旧 hook 会残留**：`cleanOld()` 的注销清单漏了 `sh_l2_entry`，导致再次加载时 `e1.b.a` 被**上一次的**总闸短路成 null（现象：自检返回空、统计全 0）。清单要覆盖所有用过的 hook id。

8. **Xposed 的 `handleLoadPackage` 早于 Application 创建**，拿不到 Context。清库与统计汇总必须挂到 `Application.onCreate` 之后。

9. **`grep -o '[0-9]*'` 会把 key 名里的数字也吃掉**（`"l2_short": 6` 会输出 `2` 和 `6` 两行）。取 JSON 数字用 `sed -n 's/.*"key": *\([0-9]*\).*/\1/p'`。

10. **KernelSU 早期阶段拿不到 uid**：`stat /data/data/<pkg>` 会被 SELinux 拒，`dumpsys` 输出里是 `sharedUser=.../10100` 而非 `userId=`。可行方案是 `pm list packages -U`（返回 `package:com.x uid:10100`）。

---

## 7. 验证与运维

### 一键体检（KernelSU 模块「操作」按钮）

```
[1] 目标进程      uid=10100  pid=xxxx
[2] 内核规则      IPv4: OK  IPv6: OK（缺失会自动补）
[3] 拦截计数      IPv4: N 包 / N 字节
[4] 当前连接      (空) 没有任何外连
[5] 应用层插件    出网拦截 / 总闸短路 / 写库拦截 / 各 regType 次数
[6] 模块自愈记录
[7] Xposed 模块   心跳时间、hook 摘要、拦截统计、当前开关
[结论] ✅ 正常 / ⚠️ / ❌
```

### 命令行版

```bash
su -c "sh /sdcard/Download/check_sh.sh"
# 或
su -c "iptables -L OUTPUT -nv | grep 10100"
```

### 判读标准

| 现象 | 含义 |
|---|---|
| 规则在、命中数为 0、连接表空 | ✅ 最理想：应用层就拦住了 |
| 命中数在涨 | ⚠️ 有请求绕过应用层，被内核兜住（查 events.log 找路径） |
| 规则不见了 | ❌ 被 netd 清了，60 秒守护会自愈 |
| `status.json` 心跳超 10 分钟 | Xposed 层没工作（进程没跑或模块没启用） |

---

## 8. 后续风险

1. **OTA 换版**：`g1.f` / `e1.b` / `f1.a` / `g1.l` / `g1.m` / `g1.e` 都是 3.0.1-20250822 的混淆名。BSH 版靠 DexKit 字符串定位较抗变；Xposed 版是硬编码（README 里写了接 DexKit 的方法）。
2. **百度 SDK 的 native 网络**：Java 层无 `HttpURLConnection`/`Socket`/`okhttp`/`apache-http` 引用，网络在 JNI 里，只能靠内核层 uid 阻断。
3. **`config.conf` 的两个开关要一起看**：关 `WIPE_DB` 只停清库，`BLOCK_DB=1` 仍然禁止写入。

---

## 9. 快速恢复/撤销

```bash
# 撤销内核层
# KernelSU 管理器里禁用/卸载 sh_blocker 模块（uninstall.sh 会自动删规则）

# 撤销应用层（Xposed）
# LSPosed 里停用模块 + 重启目标进程

# 撤销应用层（LSPosed 模块）
# 删除/禁用 SystemHelperBlocker 插件 + 重启目标进程
```
