# XrayNG

`XrayNG` 是一个基于上游 `v2rayNG` 演进的 Android 客户端分支。

它保留了上游成熟的协议兼容、订阅、路由和内核生态，同时把重心放在 Android 客户端本身：界面一致性、交互体验、滚动性能、状态反馈和日常可用性。

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/2dust/v2rayNG)](https://github.com/2dust/v2rayNG/commits/master)
[![CodeFactor](https://www.codefactor.io/repository/github/2dust/v2rayng/badge)](https://www.codefactor.io/repository/github/2dust/v2rayng)
[![GitHub Releases](https://img.shields.io/github/downloads/2dust/v2rayNG/latest/total?logo=github)](https://github.com/2dust/v2rayNG/releases)
[![Chat on Telegram](https://img.shields.io/badge/Chat%20on-Telegram-brightgreen.svg)](https://t.me/v2rayn)

## Screenshots

<p align="center">
  <img src="./Screenshot_20260315_133914.png" alt="XrayNG home screen" width="260" />
  <img src="./Screenshot_20260315_133952.png" alt="XrayNG more screen" width="260" />
</p>

## Project Positioning

如果你需要的是：

- `Xray core` / `v2fly core` 兼容
- 订阅导入与更新
- 路由规则与 Geo 数据支持
- Android 上成熟稳定的代理客户端基础能力

这些能力仍然继承自上游 `v2rayNG`。

这个分支主要额外解决的是：

- 更统一的 `Material 3 Day/Night` 视觉
- 更清晰的主页信息层级和状态反馈
- 更顺滑的列表交互与高刷新场景表现
- 更一致的设置页、日志页、订阅页和工具页体验

## Highlights

### UI

- 全局视觉升级到 `Material 3 Day/Night`，统一 Toolbar、卡片、表单、菜单和底部弹窗风格。
- 首页重做了连接状态区、分组切换和列表层级，让节点信息更集中、更易读。
- 主要功能页统一为卡片化和分组化界面，包括节点列表、订阅、路由、按应用代理、日志和资源页。
- 设置页不再保留传统系统偏好页的割裂感，整体更接近同一套产品界面语言。

### Performance

- 主节点列表采用 `AsyncListDiffer + DiffUtil + stable ids`，减少整表刷新和不必要重绘。
- 多个 RecyclerView 页面做了高刷新优化，缩短动画时长并降低 change animation 成本。
- TCP ping / real ping 测试采用受控并行度，避免瞬时拉满线程带来的卡顿和竞争。
- 主页连接状态与节点测试结果支持更细粒度刷新，不必每次整页更新。

### Product Experience

- 当前连接信息更集中，包含连接状态、节点名、协议类型和附加元信息。
- 节点测试结果支持更直观的 badge 和状态展示。
- 搜索、分组切换、节点操作和底部操作面板的交互更统一。
- 保持传统 View 系统实现，不强行迁移 Compose，在兼容性和维护成本之间做了更稳妥的取舍。

## Compatibility

- Min SDK: `24`
- Target SDK: `36`
- Compile SDK: `36`
- Kotlin: `2.3.x`
- UI stack: `ViewBinding + RecyclerView + Material 3`

## Core And Data Files

- 支持 `Xray core` 与 `v2fly core`
- `geoip.dat` 和 `geosite.dat` 通常位于 `Android/data/com.xray.ang/files/assets`
- 内置下载可拉取 [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat) 的增强规则
- 官方域名/IP 数据也可手动导入：
  - [v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat)
  - [geoip](https://github.com/Loyalsoldier/geoip)

更多使用说明可参考上游 Wiki: [v2rayNG Wiki](https://github.com/2dust/v2rayNG/wiki)

## Development

Android 工程位于 `V2rayNG/`，可直接通过 Android Studio 打开，或使用 Gradle Wrapper 构建。

### Quick Start

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug
```

### Build Variants

- `playstoreDebug` / `playstoreRelease`
- `fdroidDebug` / `fdroidRelease`

当前应用信息：

- App name: `XrayNG`
- Application ID: `com.xray.ang`
- F-Droid Application ID suffix: `.fdroid`

### Notes

- 仓库内打包的 AAR core 可能不是最新版本，具体取决于你的 checkout。
- 如果需要重编 core AAR，可参考：
  - [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite)
  - [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite)
- Go mobile 相关环境可参考 [Go Mobile](https://github.com/golang/go/wiki/Mobile)

### Emulator / WSA

`XrayNG` 可以运行在 Android Emulator 上。

对于 WSA，可能需要手动授予 VPN 权限：

```bash
appops set [package name] ACTIVATE_VPN allow
```

## Architecture

当前架构说明见 [V2rayNG/ARCHITECTURE.md](V2rayNG/ARCHITECTURE.md)。

当前方向不是激进拆模块，而是在现有 `:app` 内部逐步清理依赖方向：

`UI -> ViewModel -> repository/notifier -> handler/storage`

## Upstream

本分支基于上游 `v2rayNG` 演进。

- Upstream repository: [2dust/v2rayNG](https://github.com/2dust/v2rayNG)
- Upstream wiki: [v2rayNG Wiki](https://github.com/2dust/v2rayNG/wiki)

如果你的需求更偏向协议能力、规则生态或上游兼容性，优先参考上游文档和实现。

## Migration From v2rayNG

`XrayNG` 采用新的包名（`com.xray.ang`），因此无法直接读取 `v2rayNG` 的私有数据。请按以下方式迁移：

1. 在 `v2rayNG` 中进入 `备份 & 还原`，执行本地备份并导出 ZIP 文件。
2. 在 `XrayNG` 中进入 `备份 & 还原`，点击「从 v2rayNG 导入」，选择刚才的 ZIP 备份文件。
3. 导入会覆盖当前 `XrayNG` 配置，请在导入前确认是否需要保留现有数据。

## Community

- Telegram chat: [v2rayn](https://t.me/v2rayn)
- Telegram channel: [github_2dust](https://t.me/github_2dust)
