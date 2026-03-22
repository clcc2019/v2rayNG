---
name: xrayng-ui-style
description: 用于设计、优化、修改、统一 xrayng的UI。适用于布局、颜色、drawable、交互结构、列表样式、设置页、首页、弹窗和夜间模式的一致性调整。
---

# xrayNG UI 设计技能

## 作用

- 指导在现有产品语言内做统一、收敛、可落地的 UI 修改
- 适用：页面改版、风格统一、夜间模式、首页 / 设置页 / 列表页 / 弹层 / 编辑页调整
- 重点目标：先继承 xrayNG 已经形成的产品语言，再做精炼，而不是临时发明一套新视觉

## 严格执行

- `references/design-standards.md`、`references/page-rules.md`、`references/home-main-surface-system.md` 默认都按硬约束理解，除非用户明确要求推翻其中某条
- Android UI 任务默认同时遵循 `material-design` skill 的组件与主题原则；其中 `Material 3` 是实现基底，`xrayng-ui-style` 是产品视觉层
- 当用户说“重构”、“重写实现”、“换成 Material 3 组件”时，默认理解为“重构实现层，不重做视觉层”；除非用户明确要求改视觉，否则背景、边框、颜色、分隔、圆角、阴影关系都应保持原效果
- 当用户说“流畅、轻盈、现代化”时，默认解释为：冷白灰背景、纯白 surface、近黑文字、透明工具按钮、克制动效、轻量阴影、稳定间距，而不是彩色装饰、营销渐变或厚重光效
- 任何视觉优化都不能以明显增加功耗、持续动画、频繁重绘或高频运行时拼装 UI 为代价
- 优先改共享 token、style、drawable、selector 和状态映射；共享层能收敛时，不要直接在单页或单个 view 上写例外
- 优先用 Material 3 组件、主题属性和语义 token 实现，再在它们之上施加 xrayNG 的颜色、圆角、阴影和层次规则
- 对设置页和编辑页，`更多` 页是卡片背景、边框、分隔和行点击效果的默认视觉基准；如果改完后截图不再像 `更多` 页同一产品族，说明方向错了
- 动效必须服务信息变化，不能只为了“更炫”；如果动效会损伤可读性、性能或功耗，就应该删掉
- 文本主次、字重、色彩、圆角和阴影必须在同页内保持单一体系，不允许把一个页面做成多套视觉混合物

## 读取顺序

1. 先判断页面类型：设置式页面 / 首页与列表式页面 / 工具或编辑页
2. 如果任务涉及 Android 组件、主题、表单、列表、设置页、弹层或导航结构，先读：`references/material3-integration.md`
3. 必读：`references/design-standards.md`
4. 必读：`references/page-rules.md`
5. 如果任务涉及首页主界面、节点列表卡片、底部 dock、顶栏分组、首页工具按钮，再读：`references/home-main-surface-system.md`
6. 读取当前页 + 至少 2 个同级页 + 共享 `colors/styles/drawable`
7. 如果是设置页或编辑页任务，默认额外读取 `activity_more.xml`、至少一个设置式页面和至少一个编辑页，锁定现有效果
8. 首页任务默认额外检查这些实现落点：
   `app/src/main/res/layout/activity_main.xml`
   `app/src/main/res/layout/item_recycler_main.xml`
   `app/src/main/res/values/styles_ui.xml`
   `app/src/main/res/values/colors.xml`
   `app/src/main/java/com/xray/ang/ui/main/MainRecyclerAdapter.kt`
   `app/src/main/java/com/xray/ang/ui/main/MainConnectionCardController.kt`
9. 用 4 到 6 句总结“当前产品长什么样”和“这次问题属于不统一、信息层级混乱，还是组件本身不够成熟”
10. 先尝试通过共享 token / style / drawable / selector 解决；共享资源解决不了，再动单页结构和代码

## 页面类型

- 设置式页面：更多 / 设置 / 关于 / 备份与还原 / 路由 / 偏好页
- 列表 / 工具 / 首页页面：节点列表 / 订阅列表 / 日志 / 资产列表 / 连接控制区
- 编辑页：协议编辑、表单输入、扫描与导入相关页面

## 核心原则

- 先继承现有产品语言，再优化
- `Material 3` 做底：组件、theme、token、accessibility、edge-to-edge、系统适配
- `xrayNG` 做面：冷白灰关系、白卡片、近黑文字、克制状态色、产品级层次与留白
- 先保证统一，再谈装饰
- 默认亮色基线使用冷白灰页面背景、纯白卡片和近黑文字
- 首页层次优先靠留白、双层 surface、冷灰选中态和轻阴影建立，不靠粗描边
- 删除无意义的蓝 / 绿 / 黄装饰性高光，语义色只用于真实状态 badge
- 首页 dock 的动态文案、摘要和正文保持中性文字，不用绿 / 黄 / 红直接染正文
- 文字粗细优先跟随系统字重设置；不要在代码里到处强制自定义 Typeface 或额外堆字重逻辑

## 输出边界（摘要）

- 不新增 hero、大封面、大插画头部
- 不把设置页改成首页式重胶囊列表
- 不把首页改回传统设置页或普通工具页
- 不引入新主色体系或新按钮体系
- 不在同页混用多套圆角 / 按钮尺度 / 行高
- 共享样式能解决时，不绕开共享样式
- 不把首页顶部按钮、列表更多按钮、dock 操作区重新做成灰底按钮或描边按钮
- 不让首页卡片被容器左右裁切；页面留白和卡片宽度必须一起检查
- 不用彩色正文去表达连接状态；状态尽量收敛到 badge、chip、延迟与安全标记
- 不为了局部样式而放弃 Material 3 组件基底；能用 `MaterialCardView`、`MaterialDivider`、`MaterialSwitch`、`MaterialToolbar`、`MaterialButton`、`MaterialAlertDialog` 的场景，不退回纯手搓 view
- 不把“重构”偷换成“重新设计”；如果用户没有明确要求改设计效果，默认不改卡片背景、边框、分隔、选项行底色和整体色阶关系
- 不为了“现代化”引入高饱和装饰色、营销渐变、悬浮光斑、长期运行的 shimmer 或无意义 bounce
- 不为了“流畅”滥用持续动画、频繁 alpha 联动或无法量化收益的复杂转场

## 参考文件

- `references/material3-integration.md`：最新公开 MD3 方向与 xrayNG 产品风格的整合方式
- `references/design-standards.md`：产品风格总览、颜色 / 圆角 / 文字 / 夜间规范、首页主题细则
- `references/page-rules.md`：页面级限制、Preference 规则、浮动面板规则、组件家族与流程
- `references/home-main-surface-system.md`：当前首页主界面已经稳定下来的卡片 / dock / 顶栏 / 状态 / 字重规则
