# fucksh

> 一个 APK，三层掐断预装 **SystemHelper** 的上报链路：不采集、不定位、不落库、不出网。

[![libxposed API](https://img.shields.io/badge/libxposed-API%20102-brightgreen.svg)](https://github.com/libxposed/api)
[![KernelSU](https://img.shields.io/badge/KernelSU-module-blue.svg)](https://kernelsu.org/)
[![minSdk](https://img.shields.io/badge/minSdk-33-orange.svg)](#兼容性)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

---

## 背景

部分运营商定制机预装了一个叫 `com.mobiletools.systemhelper` 的组件（priv-app 权限），
它会把设备的 **IMEI / ICCID / IMSI / 基站信息 / 经纬度 / 已装应用使用时长 / 流量明细**
加密后定时上报到运营商的 DM（Device Management）服务器。

问题在于：**它在 OPPO 定制机上几乎不受用户控制**。
设置里那个“运营商服务改进计划”开关只有 vivo 机型的分支才会读取，OPPO 分支只判断系统开机向导是否完成——
也就是说，开机向导过完，它就开始上报了。

fucksh 把这条链路从三个层面掐断，全部离线、不采集任何数据。

---

## 三层架构

| 层 | 组件 | 做什么 | 依赖 |
|---|---|---|---|
| 应用层 | `SystemHelperXposed/`（libxposed API 102） | 进程内拦截：采集总闸、设备采集器、后台 worker、缓存落库、HTTP/HTTPS 上报、通话/数据诊断、定位门控、定时任务抑制 | Root + LSPosed/KernelSU 的 libxposed 环境 |
| 内核层 | `kernel-module/`（KernelSU） | 按 uid 用 `iptables` / `ip6tables` REJECT 目标应用全部 IPv4/IPv6 出站，每 60 秒自愈 | Root + KernelSU |
| 管理端 | `app/`（Jetpack Compose + compose-miuix-ui） | 开关与策略、环境自检、实时审计、关于页；无 Root 时用 VpnService 做「目标应用出站隔离」兜底 | 无（兜底模式不需要 Root） |

三层通过共享目录联动：

```
/storage/emulated/0/Android/media/com.mobiletools.systemhelper/SHBlocker/
├── config.conf   # 管理端写，模块读（每层开关）
├── status.json   # 模块写，管理端读（心跳 / Hook 摘要 / 各层计数）
└── events.log    # 模块追加，管理端 tail（域名级审计）
```

### 一个 APK 同时是模块和管理端

`app/` 通过 Gradle sourceSets 直接复用 `SystemHelperXposed/` 的 Java 源码与
`META-INF/xposed/*` 元数据，所以 `app-debug.apk` 安装后：

- 在 LSPosed / KernelSU 里启用，它就是 fucksh 模块；
- 桌面上打开，它就是 Miuix 风格的管理端。

`SystemHelperXposed/` 仍可单独构建，供只需要模块的用户使用。

---

## 目录结构

```
.
├── app/                        # fucksh 管理端（同时打包模块源码）
│   └── src/main/java/com/fucksh/
│       ├── ui/                 # 四页 UI、主题、组件、图标
│       ├── viewmodel/          # BlockerViewModel
│       ├── data/               # DataStore、规则库、事件日志
│       ├── system/             # RootShell、ModuleBridge
│       └── service/            # 无 Root 兜底 VpnService
├── SystemHelperXposed/         # libxposed 模块（Java）
├── kernel-module/              # KernelSU 模块脚本
├── keystore/debug.keystore     # 与 OneKey 相同的调试签名
└── tools/device-test.sh        # 模拟器实机验收脚本
```

---

## 构建

```bash
# 管理端（同时产出可当模块用的 APK）
./gradlew :app:assembleDebug

# 独立模块 APK
cd SystemHelperXposed && ./gradlew assembleRelease
```

签名使用 `keystore/debug.keystore`（别名 `androiddebugkey`，密码 `android`），
与 OneKey 的调试签名一致，便于覆盖安装。

---

## 兼容性

- 管理端 minSdk 33（miuix-blur 的 RuntimeShader 要求 Android 13+）。
- 模块 API 102，适配 LSPosed 2.x / KernelSU 的 libxposed 环境。
- 目标版本：SystemHelper `3.0.1-20250822`（versionCode 31）。OTA 换版后类名可能变化，
  若失效请带机型、系统版本与 LSPosed 日志提 Issue。

---

## 致谢

- [ColorOSGmsNetworkGuard](https://github.com/cvhhji/ColorOSGmsNetworkGuard)：SystemHelper 深度屏蔽与定时任务抑制思路
- [SystemHelper Blocker](https://github.com/zhang010416/systemhelper-blocker)：原始模块骨架与内核脚本（MIT）
- [compose-miuix-ui](https://compose-miuix-ui.github.io/miuix/)：全部 UI 组件与模糊效果
- [KernelSU](https://kernelsu.org/) / [libxposed](https://github.com/libxposed/api)：模块运行环境

完整第三方声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

---

## 许可证

GPL-3.0。原始 SystemHelper Blocker 的 MIT 许可证全文保留在
[SystemHelperXposed/LICENSE-MIT](SystemHelperXposed/LICENSE-MIT)。
