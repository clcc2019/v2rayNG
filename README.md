# v2rayNG

Android client for V2Ray/Xray, compatible with [Xray core](https://github.com/XTLS/Xray-core) and [v2fly core](https://github.com/v2fly/v2ray-core).

[![API](https://img.shields.io/badge/API-24%2B-yellow.svg?style=flat)](https://developer.android.com/about/versions/lollipop)
[![Kotlin Version](https://img.shields.io/badge/Kotlin-2.3.0-blue.svg)](https://kotlinlang.org)
[![GitHub commit activity](https://img.shields.io/github/commit-activity/m/2dust/v2rayNG)](https://github.com/2dust/v2rayNG/commits/master)
[![CodeFactor](https://www.codefactor.io/repository/github/2dust/v2rayng/badge)](https://www.codefactor.io/repository/github/2dust/v2rayng)
[![GitHub Releases](https://img.shields.io/github/downloads/2dust/v2rayNG/latest/total?logo=github)](https://github.com/2dust/v2rayNG/releases)
[![Chat on Telegram](https://img.shields.io/badge/Chat%20on-Telegram-brightgreen.svg)](https://t.me/v2rayn)

## Branch Focus

本分支基于上游 `v2rayNG`，重点不是改协议能力，而是优化 Android 客户端体验：

- 更现代的 Material 3 UI 视觉
- 更顺滑的列表交互和页面动效
- 更稳定的高刷新场景表现
- 更清晰的主页状态反馈与设置页结构

如果你想找的是核心协议支持、订阅能力、路由规则、Xray/V2Fly 兼容性，这些能力仍然继承自上游；本分支主要增强的是 `UI`、`性能` 和日常使用体验。

## Main Features

### UI

- 全局主题升级到 `Material 3 Day/Night`，统一 Toolbar、表单、卡片、弹出菜单和底部弹窗风格。
- 首页重做了视觉层级，增加连接状态卡片、状态 Badge、顶部分组 Tab 容器，信息密度更高但更易读。
- 主要列表页采用统一卡片化设计，包括节点列表、路由设置、订阅设置、按应用代理、用户资源、日志等页面。
- 设置页使用更现代的 `Preference` 样式，减少传统系统设置页的割裂感。
- 启动页、抽屉、底部操作面板和列表项补充了轻量动画与按压反馈，交互更自然。

### Performance

- 主节点列表使用 `AsyncListDiffer + DiffUtil + stable ids`，减少整表刷新，节点状态更新时优先走局部刷新。
- 多个 RecyclerView 页面统一做了高刷新优化，关闭高成本 change animation，缩短增删改动画时长，并提升缓存命中。
- TCP ping / real ping 测试使用受控并行度，避免一次性拉满线程导致 UI 抖动或后台竞争。
- 主页连接卡片支持更细粒度刷新，节点测试结果和运行状态变化时无需整页重绘。
- 按应用代理页针对大量应用场景做了专项优化，减少批量选择时的重复持久化和重复 adapter 重建。
- 选中节点配置支持预热，降低常见操作链路上的等待感。

### Product Experience

- 首页的“当前连接”信息更集中，包含连接状态、节点名、协议类型和附加元信息。
- 节点测试结果支持更直观的 Badge 展示和状态反馈。
- 分组切换、节点选择、底部菜单操作和搜索体验更统一。
- 保持传统 View 系统实现，不强行迁移 Compose，降低维护风险并兼顾兼容性。

## Compatibility

- Min SDK: `24`
- Compile / Target SDK: `36`
- Kotlin: `2.3.x`
- Android UI stack: `ViewBinding + RecyclerView + Material 3`

## Usage

### GeoIP / GeoSite

- `geoip.dat` and `geosite.dat` are stored in `Android/data/com.v2ray.ang/files/assets` on most devices.
- Built-in download can fetch enhanced rules from [Loyalsoldier/v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat). A working proxy may be required.
- Official domain list and IP list can also be imported manually from [v2ray-rules-dat](https://github.com/Loyalsoldier/v2ray-rules-dat) and [geoip](https://github.com/Loyalsoldier/geoip).
- Third-party data files can be placed in the same folder if needed.

更多使用说明可参考上游 Wiki: [v2rayNG Wiki](https://github.com/2dust/v2rayNG/wiki)

## Development

Android project is under `V2rayNG/` and can be built directly in Android Studio or with Gradle wrapper.

### Quick Start

```bash
cd V2rayNG
./gradlew assemblePlaystoreDebug
```

### Notes

- The bundled AAR core may be outdated depending on your checkout.
- If you need to rebuild the core AAR, see [AndroidLibV2rayLite](https://github.com/2dust/AndroidLibV2rayLite) or [AndroidLibXrayLite](https://github.com/2dust/AndroidLibXrayLite).
- For Go mobile related setup, see [Go Mobile](https://github.com/golang/go/wiki/Mobile).

### Emulator / WSA

v2rayNG can run on Android Emulators.  
For WSA, VPN permission may need to be granted manually:

```bash
appops set [package name] ACTIVATE_VPN allow
```

## Community

- Telegram chat: [v2rayn](https://t.me/v2rayn)
- Telegram channel: [github_2dust](https://t.me/github_2dust)
