# Material 3 与 xrayNG UI 融合规范

## 何时读取

- 任务涉及 Android Views 组件、主题、表单、列表、设置页、编辑页、弹层、导航和系统栏时必读
- 用户提到 `Material 3`、`Material You`、`MD3`、`material-design` 时必读
- 用户要求“组件用 Material 3，实现风格按 xrayNG”时必读

## 总原则

- `Material 3` 是组件和设计 token 的实现基底
- `xrayng-ui-style` 是产品视觉层和风格约束层
- 先用 `Theme.Material3.DayNight`、Material 组件、语义 color roles、styles/themes 搭好结构，再在其上施加 xrayNG 的冷白灰、白卡片、近黑文字、克制状态色和留白系统
- 当任务是“重构”时，这里的默认语义是“替换组件实现，不改变视觉结果”；组件升级应尽量是视觉透明的

## Material 3 负责什么

- 组件语义：卡片、输入框、开关、分隔线、工具栏、按钮、进度、弹层、对话框
- 主题系统：color roles、shape、typography、state layer、theme overlay
- 可访问性：对比度、touch target、语义色映射
- 系统适配：夜间模式、动态色、edge-to-edge、insets、系统栏保护

## xrayNG 风格层负责什么

- 产品气质：冷静、轻盈、克制、近黑文字、冷白灰背景
- 页面层次：白卡片、弱阴影、细高光、冷灰选中态、克制分隔
- 信息表达：状态色只留给真实状态，不扩散到整块正文和容器
- 品牌收敛：首页比设置页更活跃，但不能长成另一套 App

## 组件实现优先级

### 优先直接使用 Material 组件

- 容器：`MaterialCardView`
- 分隔：`MaterialDivider`
- 开关：`MaterialSwitch` 或 `SwitchPreferenceCompat`
- 顶栏：`MaterialToolbar`、`AppBarLayout`
- 按钮：`MaterialButton`
- 加载与反馈：`CircularProgressIndicator`、`LinearProgressIndicator`
- 弹层：Material bottom sheet、Material alert dialog

### 可以保留现有控件但必须 MD3 化

- `EditText`
- `Spinner`
- `RecyclerView` item root
- `Preference` 自定义 row

这些场景可以继续使用现有控件或框架，但必须通过 theme、style、drawable 和 token 让它们读起来属于 Material 3 体系，而不是纯手工无语义组件。

## 色彩与 token

- 优先使用语义 role，而不是到处直接写颜色值
- 自定义颜色应先映射到 `md_theme_*` 角色，再由组件和样式消费
- `primary / secondary / tertiary / surface / surfaceVariant / outline / on*` 是默认分配入口
- `xrayNG` 的冷白灰和近黑焦点可以继续保留，但应作为静态 brand scheme 映射进 MD3 color roles
- 动态色不是强制要求；如果产品有明确的冷白灰品牌基线，可以保留静态主题
- 如果以后局部引入动态色，优先考虑 harmonized 方式，不要破坏首页与关键主界面的产品识别度

## 最新 MD3 方向的采纳方式

结合最新公开的 MD3 / Material 3 Expressive 方向，xrayNG 应采纳的是：

- 更强的 token 化和语义色使用
- 更明确的组件分工和状态层
- 更自然的 edge-to-edge 和系统栏保护
- 更一致的 motion language 和自适应布局思路
- 更有层次的 shape / color / typography 组合能力

不应直接照搬的是：

- 过度情绪化、玩具化、年轻化的高表现力样式
- 高变化形状、夸张 shape morphing、明显 playful 风格
- 大面积亮色、夸张字体轴动画、强烈营销视觉

这是基于官方最新方向做的产品化推断：xrayNG 属于工具型网络 App，适合采用“克制版 MD3”，而不是完整 expressive 外放风格。

## 视图系统中的执行规则

- 能通过 theme + style 解决，就不要优先写 runtime view mutation
- 能通过共享 `styles_ui.xml`、`colors.xml`、`drawable` 解决，就不要逐页散改
- 表单页优先把 section、field、option row、switch row、popup surface 抽成共享 form family
- 设置页优先把 preference row、section surface、divider、switch widget 抽成共享 settings family
- 首页虽然视觉更强，但底层依然优先用 Material 组件承载
- 如果 Material 3 组件的默认外观与当前产品效果不一致，优先通过 style / theme / token 调整组件外观，而不是让产品效果去适应默认组件外观

## 表单与编辑页规则

- section 容器优先用 `MaterialCardView`
- 输入框优先通过共享 field background、padding、typography 和 state color 实现统一
- 如果使用外置 label + hint，不要再额外模拟一层伪 label 动画；保持清晰稳定即可
- 下拉和弹出面板保持 Material surface 关系，不要做成无来源的悬浮灰块
- 布尔选项优先用 `MaterialSwitch` 和标准 option row，不继续混入多套手工开关风格

## 设置页和列表规则

- 分组设置列表优先是 `MaterialCardView + MaterialDivider + SwitchPreferenceCompat/MaterialSwitch`
- 即使最终视觉是 xrayNG 的白卡分组，也应让承载组件保持 Material 语义
- 列表项、表单项、设置项的点击反馈优先使用 Material selectable / ripple 体系
- 对设置页和编辑页，`更多` 页是视觉金标准之一：白色卡片、无明显描边、冷灰分隔、稳定 row 背景
- 使用 Material 3 组件时，如果换完后卡片背景、边框、分隔、色阶关系明显偏离 `更多` 页，说明实现不合格

## Edge-to-edge 与系统栏

- 可滚动页面默认按 edge-to-edge 思路设计
- 顶栏如果不粘连状态栏，需要匹配背景的渐变或保护层
- 不要叠加多层 system bar protection
- 背景和分隔可以延展到边缘，真正需要点击和阅读的内容要根据 inset 留安全区

## 不要这样做

- 不要把 Material 3 理解为默认紫色、默认大圆角、默认组件外观直接照搬
- 不要为了套 MD3 而把 xrayNG 的产品语言洗掉
- 不要为了保风格而完全绕开 Material 组件和语义 token
- 不要把自定义 drawable 当成替代 Material 组件的首选方案
- 不要把最新 expressive 方向误读成“越花越像新版 MD3”
- 不要因为换成 `MaterialCardView` / `MaterialSwitch` / `MaterialDivider` 就默认接受它们的现成视觉结果
