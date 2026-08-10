# NexusChain Explorer — 设计规范（设计契约）

> 审计整改基线，对齐 R1 / R3 / R5（Tailwind 构建化 + Token 化 + 图标统一）。
> **暗色优先（dark-first）**，token 同时支持浅色。删除浅色死组件后保持暗色为先。
> 配套文件：`design-tokens.json`（机器可读）、`tailwind.config.ts`（token 骨架）、`src/styles/tokens.css`（CSS 变量源）。

## 1. Visual Theme & Atmosphere
- 关键词：专业、可信、链上实时、克制科技感。
- 暗色界面；中性深灰为底，单一品牌靛蓝为强调，emerald / amber / red 仅作语义状态。
- 禁用：紫粉渐变、emoji 图标、装饰性毛玻璃、过度圆角（>16px）、散用 `indigo-*` / `gray-*` 工具类。

## 2. Color Palette & Roles（暗色优先）
品牌强调色保留靛蓝家族，但**收进 token**，不再散用 `indigo-400/500/600`。每屏强调使用 ≤2 处。

| Token | 暗色值 | 角色 |
|-------|--------|------|
| `--bg` | `#0A0C10` | 页面背景 |
| `--surface` | `#111418` | 卡片/容器 |
| `--surface-2` | `#161A20` | 悬浮/次级表面 |
| `--border` | `#232830` | 默认边框 |
| `--border-soft` | `#1A1E24` | 行分隔 |
| `--fg` | `#E6E9EF` | 主文本 |
| `--fg-2` | `#AEB4C0` | 次级文本 |
| `--muted` | `#8B93A1` | 辅助/元数据（已提亮满足 4.5:1 对比度） |
| `--accent` | `#5B6CF0` | 品牌强调/链接/激活 |
| `--accent-hover` | `color-mix(accent 88% black)` | 悬停 |
| `--accent-active` | `color-mix(accent 80% black)` | 激活 |
| `--accent-on` | `#FFFFFF` | accent 背景上的文字 |
| `--accent-soft` | `rgba(91,108,240,0.12)` | hover 背景/焦点环底 |
| `--success` | `#34D399` | 成功 |
| `--warn` | `#FBBF24` | 处理中/待确认 |
| `--danger` | `#F87171` | 失败/错误 |

浅色（未来，`[data-theme='light']`）：bg `#F7F8FA` / surface `#FFFFFF` / border `#E5E7EB` / fg `#0F172A` / muted `#64748B` / accent 同。

## 3. Typography Rules
- 字体栈：`--font-display` / `--font-body` = `"Inter", "Noto Sans SC", system-ui, sans-serif`；`--font-mono` = `"JetBrains Mono", "Fira Code", monospace`（哈希/地址专用，沿用现状）。
- 字号：xs 12 / sm 14 / base 16 / lg 18 / xl 20 / 2xl 24 / 3xl 32 / 4xl 40。
- 字重：400 正文 / 510 小标题 / 590 大标题 & CTA。
- 字距：正文 `0`；ALL CAPS 小标签 `0.06em`；标题(≥32px) `-0.01em`。

## 4. Component Stylings
- **按钮**：Primary = `bg-accent text-accent-on rounded-md` + padding 10/16；Secondary = 透明 + 1px `border-accent text-accent`；Ghost = `bg-surface-2 text-fg`。覆盖 9 态（default/hover/focus/active/disabled/loading/error/empty/success）。
- **列表行**（替代首页内联重写 & 孤儿组件）：统一用 `<Link>` / `<button>`，hover = `bg-surface-2` + `border-accent-soft`；`focus-visible` 用 `--focus-ring`（2px accent 环）。
- **状态标签**：lucide 图标 + 文字（不只靠颜色）。success = `CheckCircle2`、processing = `Loader2`(spin)、failed = `XCircle`。
- **输入框**：`bg-surface border-border`，focus = `border-accent` + ring `accent-soft`；placeholder `muted`。

## 5. Layout Principles
- 栅格：桌面 12 / 平板 8 / 手机 4；容器 `max-w-6xl`（首页）/ `max-w-4xl`（详情）。
- 节区节奏：桌面 80 / 平板 48 / 手机 32。
- 共享布局：抽 `Layout` + `Header`（品牌 + 全局搜索 + 链状态 + 导航入口，含 `/orchestration`），消除孤立路由与双套页头。

## 6. Depth & Elevation
- flat: none；ring: `0 0 0 1px var(--border)`；raised: `0 1px 2px rgba(0,0,0,.4), 0 8px 24px rgba(0,0,0,.25)`。
- 深色靠亮度递进 + 边框，不靠重阴影。

## 7. Do's and Don'ts
- ✅ 令牌化颜色、统一 lucide 图标、暗色优先、状态配图标+文字、焦点环可见、`prefers-reduced-motion` 兜底。
- ❌ 硬编码 hex（除 `#fff`/`#000`）、emoji 图标、紫粉渐变、散用 `indigo-*`/`gray-*` 工具类、装饰毛玻璃、>16px 圆角、仅颜色传达状态。

## 8. Responsive Behavior
- 断点 640 / 768 / 1024 / 1280；移动单列、桌面双列（首页）/ 四列（详情）。
- 触摸目标 ≥44×44；图标按钮带 `aria-label`；`prefers-reduced-motion` 关闭 pulse/spin。

## 9. Agent / 图标契约（前端锁定，禁止另选）
- **图标库**：`lucide-react@0.460.0`。尺寸 16（行内）/ 20（按钮）/ 24（独立）。全项目禁 emoji 作功能图标。
- **图标映射**（替换现有内联 SVG 与文字标记）：
  - 搜索 `Search`；区块 `Boxes`；交易 `ArrowLeftRight`；复制哈希 `Copy`；外链 `ExternalLink`
  - 返回 `ArrowLeft`；翻页 `ChevronLeft` / `ChevronRight`
  - 状态 success `CheckCircle2` / processing `Loader2` / failed `XCircle`
  - Live 指示 `Circle`（实心）+ 文字 "Live"；地址 `Wallet`；节点/Peers `Network`；路由策略 `GitBranch`
  - 空态 `Inbox` 或 `SearchX`；错误 `AlertCircle`
- **数据层 / DTO 提示**：所有请求走 `api/client.ts`（统一 base）。`types/index.ts` 当前含后端未返回的字段（`AccountInfo.publicKeyHash/nonce`、`BlockInfo.difficulty/size`、`TransactionInfo.fee/type/nonce/blockHash/payload`），被 `request<T>()` 强转成 `undefined` 静默呈现。**详情页行必须容忍缺失字段**（用 `—` 占位，而非渲染 `undefined`）。待后端给出权威 DTO 后，类型层一次性对齐，不要按前端想象补字段。
