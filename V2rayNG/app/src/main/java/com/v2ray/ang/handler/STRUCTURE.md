# Handler 目录结构

- `config/`: 配置解析、组装、原生能力桥接。
- `service/`: 服务启动停止、通知、测速等运行期控制。
- `settings/`: 设置读写与设置变更标记。
- `storage/`: 本地持久化与 WebDAV 存储。
- `sync/`: 订阅更新、版本检查等外部同步能力。

当前仍保持原有 `package com.v2ray.ang.handler`，目录拆分仅用于职责归档与可读性提升。
