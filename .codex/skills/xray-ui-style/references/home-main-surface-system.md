# 首页主界面卡片系统

## 目录

- 何时读取
- 实现锚点
- 当前首页稳定观感
- 列表卡片规则
- 状态 chip 与 badge 规则
- Dock 规则
- 顶栏与分组规则
- 文字粗细规则
- 不要这样做

## 何时读取

- 用户提到首页、主页、节点列表卡片、底部 dock、启动按钮、顶部分组、首页工具按钮时必读
- 用户要求“参考当前首页风格”或“沿用最近这版设计”时必读
- 修改 `activity_main.xml`、`item_recycler_main.xml`、`MainRecyclerAdapter.kt`、`MainConnectionCardController.kt` 前先读

## 实现锚点

- 布局：
  `app/src/main/res/layout/activity_main.xml`
  `app/src/main/res/layout/item_recycler_main.xml`
- 行为：
  `app/src/main/java/com/xray/ang/ui/main/MainRecyclerAdapter.kt`
  `app/src/main/java/com/xray/ang/ui/main/MainConnectionCardController.kt`
  `app/src/main/java/com/xray/ang/ui/base/SystemFontWeightHelper.kt`
- 样式与 token：
  `app/src/main/res/values/styles_ui.xml`
  `app/src/main/res/values/colors.xml`
- 关键 drawable：
  `app/src/main/res/drawable/bg_home_list_card_panel.xml`
  `app/src/main/res/drawable/bg_home_list_card_panel_default.xml`
  `app/src/main/res/drawable/bg_home_list_card_panel_selected.xml`
  `app/src/main/res/drawable/bg_connection_dock_panel.xml`
  `app/src/main/res/drawable/bg_connection_action_container.xml`
  `app/src/main/res/drawable/bg_group_tab_item_tab.xml`
  `app/src/main/res/drawable/bg_home_toolbar_action.xml`
  `app/src/main/res/drawable/bg_home_icon_circle_clear_ripple.xml`
  `app/src/main/res/drawable/bg_home_security_badge.xml`

## 当前首页稳定观感

- 页面底色是冷白灰，列表卡片和 dock 都是纯白或近白 surface
- 首页层次靠白卡片、冷灰选中态、双层面板和轻阴影建立，不靠描边和厚阴影
- 近黑文字是主基线，次文字是冷灰，彩色只留给真实状态 chip / badge
- 顶部工具按钮、列表更多按钮都应该是透明点击面，只保留轻 ripple，不带灰色底板
- dock 和列表卡片属于同一套产品语言，但 dock 比列表卡片更完整、更有承托感

## 列表卡片规则

### 结构

- 采用双层结构：外层 `MaterialCardView shell` + 内层 `1dp inset panel`
- 外层负责整体白卡片体积感和阴影，内层负责细腻的高光与选中态
- 卡片背景默认纯白或近白，不使用灰框；边界主要靠阴影、圆角和间距读出
- 当前基线圆角保持在 `18dp` 左右，不继续放大成夸张大胶囊

### 容器与宽度

- `ViewPager2` 左右留白保持 `16dp`
- 列表卡片必须配合 `clipToPadding="false"` 使用，避免阴影和圆角边缘被裁切
- 卡片继续受 `list_card_max_width` 约束，但要保证左右边缘完整露出

### 选中态

- 选中态必须可见，且同时体现在外层 shell 和内层 panel
- 选中背景使用冷灰，不使用彩色块或粗描边
- 如果列表层次已经变白变轻，仍要保留选中态，否则配置切换会失去可辨识性

### 文字与按钮

- 标题维持近黑、高对比、紧凑字距，当前稳定值约 `15sp`
- 地址 / 统计信息维持冷灰，当前稳定值约 `11sp`
- 订阅来源信息维持 `10sp` 到 `10.5sp`，只做弱提示
- 右上角更多按钮保持透明点击面，不再出现灰色背景块

## 状态 chip 与 badge 规则

### 协议 chip

- 协议 chip 继续使用近黑底 + 白字，不引入新主色
- 协议 chip 是识别标签，不承担选中态表达

### 延迟 badge

- 延迟 badge 预留固定长度，按 `3 位数字 + ms` 的长度设计
- 默认始终居中，避免 `9ms / 114ms / 999ms` 导致宽度跳动
- 延迟 badge 内包含深色状态圆点：
  深绿色表示 good
  深黄色表示 warn
  深红色表示 bad
- 延迟 badge 颜色只服务于延迟状态，不扩散到整张卡片或正文

### 安全 badge

- 安全 badge 是圆形小盾牌，盾牌图标固定黑色
- `TLS` 和 `REALITY` 都算安全传输，使用更深一点、同时更亮一点的绿色背景
- 非安全传输使用灰色背景
- 安全 badge 只表达传输层安全，不替代协议 chip 和延迟 badge

## Dock 规则

### 容器

- dock 固定为纯白或近白大卡片，不使用描边
- 层次来自冷灰或白色系阴影，以及内层 panel 的轻微高光
- dock 可以带极弱地图线稿装饰，但装饰必须是背景配角，不能抢文字和主按钮
- 中间分隔线保持完整长度，不要人为缩短成短横线

### 文字层级

- 状态 badge 文本约 `11sp`
- 配置名约 `17sp` 到 `18sp`
- 摘要约 `12sp` 到 `12.5sp`
- 摘要文案优先表达“当前可执行动作”或“当前连接说明”，不要堆第二行状态说明
- dock 的动态摘要不要用绿色、黄色、红色字体，成功 / 警告 / 失败也保持中性色文字

### 启停按钮

- dock 操作区不要描边
- 外层按钮容器靠阴影和轻渐变建立体积感
- 内部真正的启动按钮不要再加边框和灰色背景
- 首页震动反馈只保留底部启动 / 关闭服务；其他列表、菜单、次级按钮不再保留震动反馈

## 顶栏与分组规则

- 左上角优先是横杆菜单图标，和右侧工具按钮保持同一套图标尺度
- 顶栏工具按钮背景保持透明，只保留点击反馈
- 顶部分组之间的容器间隔要紧凑，避免大块空白
- 选中分组使用更深一点的冷灰背景，不用彩色填充

## 文字粗细规则

- App 整体文字粗细应跟随手机系统字重设置
- 需要修改首页或设置页文字时，优先通过共享 `TextAppearance` 和 `SystemFontWeightHelper` 兼容
- 除非语义非常明确，不要在业务代码里继续增加新的硬编码字重策略

## 不要这样做

- 不要把列表卡片和 dock 改回灰框卡片
- 不要把首页按钮改回灰色底板
- 不要用彩色正文做动态提示
- 不要让首页卡片边缘被容器遮挡
- 不要让选中态只存在于逻辑里、视觉上却看不出来
- 不要把 secure / latency 这些真实状态扩散成大面积装饰色
