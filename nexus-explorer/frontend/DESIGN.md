# NexusChain Explorer — 设计规范（设计契约）

> 审计整改基线，对齐 R1 / R3 / R5（Tailwind 构建化 + Token 化 + 图标统一）。
> **暗色优先（dark-first）**，token 同时支持浅色。
> 配套文件：`design-tokens.json`（机器可读）、`tailwind.config.js`（token 骨架，**唯一配置**）、`src/styles/tokens.css`（CSS 变量源）、`src/styles/tokens.ts`（TS 侧访问 + Tailwind 映射）。
>
> **修订记录 2026-09-16（全面质量审查修复）**
> 1. 颜色新增「双形式令牌」约定：每个颜色同时导出 `--x`（hex，供原生 CSS）与
>    `--x-rgb`（通道，供 Tailwind 的 `rgb(var(--x-rgb) / <alpha-value>)`）。
>    原因是 Tailwind v3 对 `var(--x)` 这类不可解析值不做 alpha 合成，
>    `bg-surface/80` 等透明度修饰符会被**静默丢弃**。
> 2. 品牌色拆分 `--accent`（文字/链接）与 `--accent-solid`（实底按钮）。
>    同一值无法同时满足「暗背景上的亮文字」与「白字下的暗底」两个 ≥4.5:1 要求。
> 3. 语义浅底拆出独立 token（`--success-soft` / `--warn-soft` / `--danger-soft`）。
>    原「文字色 alpha 稀释」法在浅色主题下会让底色趋近文字色，对比度随不透明度
>    升高反而**恶化**（实测 3.81:1）。
> 4. 全部前景/背景组合已按 WCAG 2.1 AA 重算，两主题均达标（见 §2 末）。
> 5. 新增 `--border-strong` 用于可交互控件边界（WCAG 1.4.11 的 3:1）；
>    `--border` 保留为装饰性分隔。
> 6. 删除 `tailwind.config.ts`（Tailwind 解析顺序 `.js` 优先，`.ts` 永不加载，
>    其独有令牌全部失效）。§3 字重裁定为 400/500/600/700（原文档的 510/590 与
>    `design-tokens.json`、`tokens.ts` 三方不一致，现统一）。

## 1. Visual Theme & Atmosphere
- 关键词：专业、可信、链上实时、克制科技感。
- 暗色界面；中性深灰为底，单一品牌靛蓝为强调，emerald / amber / red 仅作语义状态。
- 禁用：紫粉渐变、emoji 图标、装饰性毛玻璃、过度圆角（>16px）、散用 `indigo-*` / `gray-*` 工具类。

## 2. Color Palette & Roles

### 2.1 双形式令牌约定（强制）

每个颜色必须**同时**定义两种形式，缺一不可：

```css
--surface: #111418;        /* hex：供原生 CSS 直接使用 */
--surface-rgb: 17 20 24;   /* 空格分隔通道：供 Tailwind alpha 合成 */
```

```js
// tailwind.config.js（经 tokens.ts 的 tailwindThemeExtend 注入）
colors: { surface: "rgb(var(--surface-rgb) / <alpha-value>)" }
```

**为什么强制**：颜色若只写成 `var(--surface)`，Tailwind v3 无法解析色值，
`bg-surface/80`、`bg-success/15`、`border-danger/30` 这类透明度修饰符
**不会生成任何 CSS 规则**（且不报错）—— Badge 浅底会退化为实色块、
页头失去半透明。新增颜色时务必同步补 `-rgb` 变量，并跑
`npm run verify:build` 校验。

### 2.2 暗色（默认，`:root`）

| Token | 值 | 角色 |
|-------|-----|------|
| `--bg` | `#0A0C10` | 页面背景 |
| `--surface` | `#111418` | 卡片/容器 |
| `--surface-2` | `#161A20` | 悬浮/次级表面 |
| `--border` | `#232830` | 装饰性边框（分隔、卡片轮廓） |
| `--border-soft` | `#1A1E24` | 行分隔 |
| `--border-strong` | `#5A6370` | **可交互控件边界**（输入框等，3:1） |
| `--fg` | `#E6E9EF` | 主文本 |
| `--fg-2` | `#AEB4C0` | 次级文本 |
| `--muted` | `#8B93A1` | 辅助/元数据 |
| `--accent` | `#8B96FF` | 品牌**文字/链接/激活**（对暗背景 ≥4.5:1） |
| `--accent-solid` | `#4B5AE0` | 品牌**实底**（配 `--accent-on` ≥4.5:1） |
| `--accent-solid-hover` | `#4050CE` | 实底悬停 |
| `--accent-solid-active` | `#3543B8` | 实底激活 |
| `--accent-on` | `#FFFFFF` | accent 实底上的文字 |
| `--accent-soft` | `#1B2033` | hover 背景 / Badge primary 浅底 |
| `--success` | `#34D399` | 成功（文字） |
| `--success-soft` | `#16312B` | 成功浅底（Badge） |
| `--warn` | `#FBBF24` | 处理中/待确认（文字） |
| `--warn-soft` | `#342E1A` | 警告浅底 |
| `--danger` | `#F87171` | 失败/错误（文字） |
| `--danger-soft` | `#342225` | 错误浅底 |
| `--danger-solid` | `#B91C1C` | 破坏性操作实底 |
| `--danger-on` | `#FFFFFF` | danger 实底上的文字 |

### 2.3 浅色（`[data-theme='light']`）

| Token | 值 | 备注 |
|-------|-----|------|
| `--bg` / `--surface` / `--surface-2` | `#F7F8FA` / `#FFFFFF` / `#F1F3F7` | |
| `--border` / `--border-soft` / `--border-strong` | `#E5E7EB` / `#EEF0F4` / `#828C99` | |
| `--fg` / `--fg-2` / `--muted` | `#0F172A` / `#334155` / `#5A6472` | muted 较原 `#64748B` 加深（原值对 bg 仅 4.48:1） |
| `--accent` / `--accent-solid` | `#4338CA` | 文字与实底同值（亮色下两个约束方向一致） |
| `--accent-solid-hover` / `-active` | `#3730A3` / `#2E2882` | |
| `--accent-soft` | `#EEF0FD` | |
| `--success` / `--success-soft` | `#036B4E` / `#E3F3EC` | |
| `--warn` / `--warn-soft` | `#92400E` / `#FBEFDD` | |
| `--danger` / `--danger-soft` / `--danger-solid` | `#B91C1C` / `#F9D5D3` / `#B91C1C` | |

### 2.4 对比度验收（WCAG 2.1 AA）

所有组合均已实算，**两主题全部达标**（正文 4.5:1 / UI 组件 3:1）：

- `text-{fg,fg-2,muted,accent,success,warn,danger}` × `{bg, surface, surface-2}`
- 实底按钮：`--accent-on` on `--accent-solid(-hover/-active)`、`--danger-on` on `--danger-solid`
- Badge 浅底：`text-{tone}` on `--{tone}-soft`
- 半透明 banner：`bg-{tone}/10` 叠 `--bg` 后的合成底
- 可交互控件边界：`--border-strong` on `--surface` / `--bg`

**改动任何色值后必须重跑对比度核算**，不得凭观感判断。

## 3. Typography Rules
- 字体栈：`--font-display` / `--font-body` = `"Inter", "Noto Sans SC", system-ui, sans-serif`；`--font-mono` = `"JetBrains Mono", "Fira Code", monospace`（哈希/地址专用）。
- 字号：xs 12 / sm 14 / base 16 / lg 18 / xl 20 / 2xl 24 / 3xl 32 / 4xl 40。
- **字重（权威值，2026-09-16 裁定）**：`400` 正文 / `500` 小标题 / `600` 大标题 & CTA / `700` 强调。
  > 原文档写 510/590，`design-tokens.json` 与 `tokens.ts` 亦不一致。现统一为 Tailwind 内置档位（400/500/600/700），避免非标准字重在字体缺失时回退。
- 字距：正文 `0`；ALL CAPS 小标签用 `tracking-caps`（= `--tracking-caps` = `0.06em`）；标题(≥32px) `-0.01em`。
  > 此前实现散用 `tracking-wide`（0.025em），与本条不符。

## 4. Component Stylings
- **按钮**（`components/ui/Button.tsx`）
  - Primary = `bg-accent-solid text-accent-on rounded-md`
  - Secondary = **透明底** + `border-accent` + `text-accent`
  - Ghost = `bg-surface-2 text-fg`
  - Danger = `bg-danger-solid text-danger-on`
  - 尺寸与触摸目标：`sm` 36px（满足 WCAG 2.2 SC 2.5.8 AA 的 24px 下限，仅用于表格行内等紧凑场景）/ `md` 44px（默认，满足本条 44px 与 WCAG 2.5.5 AAA）/ `lg` 48px
  - 覆盖状态：default / hover / focus-visible / active / disabled / **loading**（`loading` + `loadingLabel`，自动禁用并标注 `aria-busy`）
- **列表行**：统一用 `<Link>` / `<button>`，hover = `bg-surface-2` + `border-accent`；`focus-visible` 用 `--focus-ring`（2px accent 环）。`Card` 的 `interactive` 模式必须同时处理鼠标点击与 Enter/Space。
- **状态标签**（`Badge`）：**lucide 图标 + 文字（不只靠颜色）**。success = `CheckCircle2`、warning = `AlertTriangle`、danger = `XCircle`，默认渲染；纯文字场景传 `icon={null}`。浅底用 `bg-{tone}-soft`（**不要**用 `bg-{tone}/NN` 稀释法，见 §2.1）。
- **输入框**：`bg-surface border-border-strong`（**可交互边界用 `border-strong`**，`border` 仅用于装饰），focus = `border-accent` + ring `accent-soft`；placeholder `muted`。
- **对话框**（`Modal`）：ESC **始终**可关闭（`disableBackdropClose` 只禁用遮罩点击，不得连带禁用 ESC —— 否则违反 WCAG 2.1.2 No Keyboard Trap）；打开时移焦入对话框、Tab 循环限制在内、关闭后归还焦点；标题 id 用 `useId()` 保证唯一。

## 5. Layout Principles
- 栅格：桌面 12 / 平板 8 / 手机 4；容器 `max-w-6xl`（首页/编排）/ `max-w-4xl`（详情/设置）。
- 节区节奏：桌面 80 / 平板 48 / 手机 32。
- **共享页头**：统一使用 `components/layout/PageHeader`（+ `BackLink`），不得再手写 `<header className="border-b border-border bg-surface/80 backdrop-blur sticky top-0 z-sticky">`。
- **导航入口**：每条路由必须至少有一个 `<Link to>` / `navigate()` 指向。`src/__tests__/routes.test.tsx` 会强制校验，防止「页面只能手敲 URL 访问」。

## 6. Depth & Elevation
- flat: none；ring: `0 0 0 1px var(--border)`；raised: `0 1px 2px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.25)`（浅色对应调整）。
- 深色靠亮度递进 + 边框，不靠重阴影。
- z-index 层级由 token 定义（`zIndex.base/sticky/dropdown/modal/toast`），Tailwind 已映射为 `z-*` 工具类。

## 7. Do's and Don'ts
- ✅ 令牌化颜色（含 `-rgb` 通道）、统一 lucide 图标、暗色优先、状态配图标+文字、焦点环可见、`prefers-reduced-motion` 兜底、缺失值用 `—` 占位。
- ❌ 硬编码 hex、emoji 图标、紫粉渐变、散用 `indigo-*`/`gray-*` 工具类、装饰毛玻璃、>16px 圆角、仅颜色传达状态、**用 `bg-{语义色}/NN` 稀释做浅底**、**直接渲染可能缺失的字段（会显示 `undefined` 或空白数值）**。

## 8. Responsive Behavior
- 断点 640 / 768 / 1024 / 1280；移动单列、桌面双列（首页）/ 四列（详情）。
  > 详情页标签-值布局为 `grid-cols-1 sm:grid-cols-4`（此前固定 4 列，360px 下标签列仅约 80px）。
- 触摸目标：默认按钮 ≥44×44（`sm` 变体 36px，见 §4）；图标按钮带 `aria-label`；`prefers-reduced-motion` 关闭 pulse/spin。

## 9. 图标契约（前端锁定，禁止另选）
- **图标库**：`lucide-react`。**必须使用精确版本号**（不使用 `^` 浮动范围），当前 `package.json` 为准。尺寸 16（行内）/ 20（按钮）/ 24（独立）。全项目禁 emoji 作功能图标。
- **图标映射**：
  - 搜索 `Search`；区块 `Boxes`；交易 `ArrowLeftRight`；复制哈希 `Copy`；外链 `ExternalLink`
  - 返回 `ArrowLeft`；翻页 `ChevronLeft` / `ChevronRight`
  - 状态 success `CheckCircle2` / warning `AlertTriangle` / processing `Loader2`(spin) / failed `XCircle`
  - Live 指示实心圆 + 文字；地址 `Wallet`；节点/Peers `Network`；路由策略 `GitBranch`
  - 空态 `Inbox` 或 `SearchX`；错误 `AlertCircle`

## 10. 数据契约（DTO）

**所有请求走 `api/client.ts`。字段名必须与后端逐字一致，不得按前端想象补字段。**

| 前端类型 | 来源 | 说明 |
|---|---|---|
| `types/index.ts` 的 `ChainStatus` / `BlockInfo` / `TransactionInfo` / `AccountInfo` | nexus-core `JsonRpcController`（`doGetNodeStatus` / `toRpcBlock` / `toRpcTransaction`）+ explorer BFF | camelCase |
| `types/orchestration.ts` 的 `OrchestratedPaymentDto` / `ConnectorDto` / `RoutingRuleDto` | nexus-gateway `PaymentOrchestrationController` | snake_case（**不同后端契约，不可强行统一**）；例外：`/connectors/{id}/health` 直出 `ConnectorHealth` 为 camelCase |

**强制要求**
1. `request<T>()` 是**无运行时校验的类型断言**，类型系统在此处失效。故 `api/client.ts` 为每个端点声明必需字段（`REQUIRED_FIELDS`），缺失即抛出可诊断错误。新增端点必须同步补上。
2. 可能缺失的字段一律用 `utils/value.ts` 的 `orDash()` 渲染为 `—`，**不得直接渲染**（React 把 `undefined` 子节点渲染为空串，用户无法区分「值为 0」与「字段缺失」）。
3. 页面测试的 mock 必须取自 `src/__tests__/fixtures/backend.ts`（后端真实字段形状），不得自行编造 —— 这正是 2026-09-16 审查中 P0 得以逃逸的原因。
