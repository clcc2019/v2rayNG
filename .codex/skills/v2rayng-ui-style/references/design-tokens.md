# V2rayNG UI Style Tokens (Optimized)

## 颜色令牌 (Color Tokens)

### 🌕 Light Mode (亮色模式)

#### 基础层 (Base Surface)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_background` | `#F5F4F1` | 页面/背景灰 |
| `md_theme_surface` | `#FFFFFF` | 卡片/浮层基础色 |
| `md_theme_surfaceVariant` | `#F3F4F7` | 次要表面 |
| `md_theme_onSurface` | `#1B2330` | 主文字色 |
| `md_theme_onSurfaceVariant` | `#636F82` | 次要文字色 |
| `md_theme_outline` | `#C6CEDA` | 描边主色 |
| `md_theme_outlineVariant` | `#E5E9F0` | 描边次色 |
| `color_card_outline` | `#E4E8EF` | 统一卡片描边 |
| `colorSelectionFill` | `#FFFFFF` | 列表选中填充（与 surface 一致） |

#### 品牌主色 (Primary — Electric Blue)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_primary` | `#2D63E9` | 品牌主色、主要按钮、激活状态 |
| `md_theme_primaryContainer` | `#E8EDFF` | 主色调容器背景 (如 Drawer Header) |
| `md_theme_onPrimaryContainer` | `#1A2B55` | 容器内文字与图标色 |
| `md_theme_surfaceTint` | `#2D63E9` | 表面着色 |
| `color_fab_active` | `#2D63E9` | 悬浮按钮激活色 |
| `colorSelectionIndicator` | `#C6D7FF` | 列表选中指示器 |
| `colorConfigType` | `#1E4FBF` | 配置类型高亮色 |

#### 强调次色 (Secondary — Teal/Cyan)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_secondary` | `#2A8684` | 强调色，与主色形成冷暖/明暗对比 |
| `md_theme_secondaryContainer` | `#D7EEEC` | 次要容器背景 |
| `md_theme_onSecondaryContainer` | `#143C3A` | 次要容器内文字色 |

#### 语义状态色 (Semantic Status)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_error` | `#D25757` | 错误、断开连接、删除警告 |
| `md_theme_warning` | `#E08A2E` | 警告、高延迟 (Ping)、即将过期 |
| `md_theme_success` | `#2C9567` | 成功、已连接、低延迟 (Ping) |

---

### 🌑 Dark Mode (暗色模式)

#### 基础层 (Base Surface)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_background` | `#000000` | 系统真黑（OLED 省电） |
| `md_theme_surface` | `#0A0D11` | 卡片基础色 |
| `md_theme_surfaceVariant` | `#131922` | 次要表面 |
| `md_theme_onSurface` | `#E4E9F1` | 主文字色 |
| `md_theme_onSurfaceVariant` | `#9AA7B9` | 次要文字色 |
| `md_theme_outline` | `#293342` | 描边主色 |
| `md_theme_outlineVariant` | `#1B2230` | 描边次色 |
| `color_card_outline` | `#1C2430` | 统一卡片描边 |
| `colorSelectionFill` | `#0B0E12` | 列表选中填充 |

#### 品牌主色 (Primary — Electric Blue)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_primary` | `#76A0FF` | 品牌主色、主要按钮、激活状态 |
| `md_theme_primaryContainer` | `#1B2A4D` | 主色调容器背景 (如 Drawer Header) |
| `md_theme_onPrimaryContainer` | `#DCE9FF` | 容器内文字与图标色 |
| `md_theme_surfaceTint` | `#76A0FF` | 表面着色 |
| `color_fab_active` | `#76A0FF` | 悬浮按钮激活色 |
| `colorSelectionIndicator` | `#34558C` | 列表选中指示器 |
| `colorConfigType` | `#A0BAFF` | 配置类型高亮色 |

#### 强调次色 (Secondary — Teal/Cyan)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_secondary` | `#56BDB9` | 强调色，与主色形成对比 |
| `md_theme_secondaryContainer` | `#0D3837` | 次要容器背景 |
| `md_theme_onSecondaryContainer` | `#CAE9E8` | 次要容器内文字色 |

#### 语义状态色 (Semantic Status)
| Token | Value | 说明 |
|-------|-------|------|
| `md_theme_error` | `#FF7373` | 错误、断开连接、删除警告 |
| `md_theme_warning` | `#FFC067` | 警告、高延迟 (Ping)、即将过期 |
| `md_theme_success` | `#53CE8F` | 成功、已连接、低延迟 (Ping) |

---

## 令牌应用规范 (Component Application Rules)

> 💡 **设计原则**：按 UI 组件分类应用 Token，而非硬编码绑定 XML 文件路径，以便于后续的重构与维护。

### 1. 页面背景 (Screen Backgrounds)
* **适用场景**：主 Activity 背景、Fragment 根节点、AppBarLayout 等大面积底层视图。
* **应用 Token**：统一使用 `md_theme_background`。

### 2. 卡片与容器 (Cards & Containers)
* **适用场景**：节点列表项、设置项面板、信息卡片。
* **应用 Token**：
  * **背景 (Background)**：使用 `md_theme_surface`（亮色下为纯白，暗色下为 `#0F0F0F`）。
  * **描边 (Stroke/Border)**：统一使用 `color_card_outline`。
  * **选中状态 (Selected)**：背景保持 `md_theme_surface` 不变，仅通过改变指示器 (Indicator) 颜色来区分，避免大面积色块跳跃。

### 3. 底部弹窗与弹出层 (Bottom Sheets & Popups)
* **适用场景**：底部操作菜单、下拉选项弹窗。
* **应用 Token**：
  * **弹窗底层背景**：使用 `md_theme_background`（亮色模式下产生灰色背景，与操作行形成物理层级区分）。
  * **操作行/菜单项背景**：使用 `md_theme_surface`（白底色）。
  * **悬浮层描边**：通常无需额外描边，依靠系统阴影 (Elevation) 区分。

### 4. 导航抽屉头部 (Navigation Drawer Header)
* **适用场景**：侧边栏顶部用户信息与状态区域。
* **应用 Token**：
  * 废弃原有的微弱渐变，统一使用 `md_theme_primaryContainer` 作为底色，以符合 MD3 扁平化规范。
---
## 2. 交互设计规范 (Interaction Design)

### ⚡ 核心连接逻辑 (Connection Logic)
* **连接按钮状态 (FAB/Button)**: 
    * **未连接**: 使用 `md_theme_outline` (灰色)，点击后开始旋转动画。
    * **连接中**: 按钮呈现呼吸灯效果（不透明度在 60%-100% 循环）。
    * **已连接**: 瞬间变为 `md_theme_primary` 并伴随轻微的回弹 (Bounce) 动画。
* **延迟显示**: 点击节点后，Ping 值显示区域先显示 `---ms`，并在 300ms 内通过淡入动画替换为具体数值。

### 👆 手势操作 (Gestures)
* **侧滑快捷操作**: 
    * 列表项向左滑动 25%：显示“编辑”与“分享”。
    * 列表项向左滑动 >50%：触发“测试延迟”并伴随震动反馈。
* **长按管理**: 长按卡片触发多选模式，顶部 Toolbar 切换为 `md_theme_surfaceVariant` 色调。
* **全局下拉**: 主界面下拉不仅更新订阅，还应触发所有活动节点的健康检查。

### 🎨 动效与视觉反馈 (Motion & Feedback)
* **微动效 (Micro-interactions)**: 
    * 切换开关 (Switch) 时，滑块应有 150ms 的位移过渡。
    * 列表加载时，使用从左向右滑动的骨架屏渐变 (Shimmer Effect)。
* **触感反馈 (Haptics)**: 
    * 成功连接：`HapticFeedback.LIGHT_IMPACT`。
    * 节点切换：`HapticFeedback.VIRTUAL_KEY`。
    * 严重错误：`HapticFeedback.LONG_PRESS` 连续两次。

### ♿ 无障碍与易用性 (Accessibility)
* **色彩辅助**: 延迟数值除颜色区分外（绿/黄/红），应根据状态附带小图标（如 ✅/⚠️/❌），照顾色弱用户。
* **点击区域**: 所有按钮和可点击列表项的高度不低于 **48dp**，确保高频操作不误触。

---

## 3. 组件应用规范 (Component Application Rules)

| 组件类型 | 背景 Token | 描边/指示器 Token | 交互行为描述 |
|:---|:---|:---|:---|
| **节点卡片** | `md_theme_surface` | `color_card_outline` | 选中时添加 `colorSelectionIndicator` 左侧装饰条 |
| **底部弹窗** | `md_theme_background` | 无 | 内容项使用 `md_theme_surface` 承载，产生浮层感 |
| **配置页组** | `md_theme_surfaceVariant` | `md_theme_outlineVariant` | 用于区分不同协议段落的背景色 |
| **通知栏** | N/A | N/A | 连接状态应在系统通知栏同步显示品牌主色图标 |

---

## 4. UI 变更快速检查清单 (Checklist)

- [ ] **视觉一致性**: 列表背景是否为 `#F2F2F2` (Light) 或 `#000000` (Dark)？
- [ ] **对比度检查**: 状态色文字在卡片上的对比度是否满足 WCAG AA 级标准？
- [ ] **层级验证**: 底部弹窗的操作项是否使用了 `md_theme_surface` 以区别于底色？
- [ ] **反馈闭环**: 用户执行连接、删除、更新订阅后，是否有明确的 Toast 或触感反馈？
- [ ] **描边统一**: 是否所有容器均撤销了硬编码色值，统一改用 `color_card_outline`？
