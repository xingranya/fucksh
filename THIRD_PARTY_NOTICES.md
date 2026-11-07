# 第三方声明与致谢

fucksh 的实现参考并受益于以下项目，特此致谢。

## ColorOSGmsNetworkGuard

- 仓库：https://github.com/cvhhji/ColorOSGmsNetworkGuard
- 作者：cvhhji
- 用途：本项目的 SystemHelper 深度屏蔽点（设备采集器、后台 worker、缓存落库、
  HTTP/HTTPS 上报、通话/数据诊断、定位门控、定时任务抑制）参考了该项目的思路与目标方法。
- 说明：该项目未附带开源许可证文件。本项目按其公开思路重新实现，未直接复制源码；
  如需再分发相关实现，请先与原作者确认授权。

## SystemHelper Blocker（上游项目）

- 仓库：https://github.com/zhang010416/systemhelper-blocker
- 作者：躺平老咸鱼
- 许可证：MIT
- 用途：本项目沿用其 libxposed 模块骨架、共享目录协议（config.conf / status.json /
  events.log）与 KernelSU 内核模块脚本。原始 MIT 许可证全文见
  `SystemHelperXposed/LICENSE-MIT`（保留原始版权声明）。

## compose-miuix-ui

- 仓库：https://github.com/compose-miuix-ui/miuix
- 文档：https://compose-miuix-ui.github.io/miuix/
- 许可证：Apache-2.0
- 用途：fucksh 管理端的全部 UI 组件、主题、图标与模糊效果。

## KernelSU / libxposed

- KernelSU：https://kernelsu.org/（内核层模块运行环境）
- libxposed API：https://github.com/libxposed/api（模块 API 102）

## 其他

- MiSans 字体由小米提供，本项目仅在系统中使用，不内嵌字体文件。
