/**
 * NexusChain Explorer — Design Tokens (TypeScript source of truth)
 *
 * 单一事实来源：所有颜色 / 间距 / 字体 / 圆角 / 阴影 / 动效 / 层级常量集中定义。
 * CSS 变量同名定义在 `tokens.css`，本文件提供 JS 侧访问 + Tailwind 扩展映射。
 * 组件应优先使用语义化 token（`bg-surface` / `text-muted` / `gap-md`），
 * 禁止散落魔法数字或裸 hex。
 *
 * ── 颜色值格式（重要）──────────────────────────────────────────────────
 * 全部颜色写作 `rgb(var(--x-rgb) / <alpha-value>)`：
 *   - `<alpha-value>` 由 Tailwind 在编译期替换；不带透明度修饰符时为 1
 *   - 该形式使 `bg-surface/80` / `bg-success/15` / `border-danger/30` 等
 *     透明度修饰符能够正常生成（`var(--surface)` 这类不可解析值会被 Tailwind
 *     静默丢弃，类不产出）
 *   - 该字符串同时是合法 CSS 颜色值，可直接用于内联样式
 * ─────────────────────────────────────────────────────────────────────
 *
 * 暗色优先（dark-first），浅色由 [data-theme='light'] 覆盖。
 */

/** 把 CSS 变量名转成 Tailwind 可用的 alpha 占位形式。 */
const alpha = (varName: string): string =>
  `rgb(var(--${varName}-rgb) / <alpha-value>)`;

/* ----------------------------- 颜色体系 ----------------------------- */
export const color = {
  /** 品牌色：文字/链接用 accent，实底用 accentSolid（两者在暗色下取值不同） */
  accent: alpha("accent"),
  accentSolid: alpha("accent-solid"),
  accentSolidHover: alpha("accent-solid-hover"),
  accentSolidActive: alpha("accent-solid-active"),
  accentOn: alpha("accent-on"),
  accentSoft: alpha("accent-soft"),

  /** 中性色（surface / fg / border） */
  neutral: {
    bg: alpha("bg"),
    surface: alpha("surface"),
    surface2: alpha("surface-2"),
    border: alpha("border"),
    borderSoft: alpha("border-soft"),
    /** 可交互控件边界（输入框等），满足 WCAG 1.4.11 的 3:1 */
    borderStrong: alpha("border-strong"),
    fg: alpha("fg"),
    fg2: alpha("fg-2"),
    muted: alpha("muted"),
  },

  /** 语义色：文字用 base，浅底用 soft，实底用 solid */
  success: alpha("success"),
  successSoft: alpha("success-soft"),
  warn: alpha("warn"),
  warnSoft: alpha("warn-soft"),
  danger: alpha("danger"),
  dangerSoft: alpha("danger-soft"),
  dangerSolid: alpha("danger-solid"),
  dangerOn: alpha("danger-on"),
} as const;

/* ----------------------------- 间距体系 ----------------------------- */
/** 4 倍数递进：4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48 px */
export const spacing = {
  0: "0px",
  xs: "4px",
  sm: "8px",
  md: "12px",
  base: "16px",
  lg: "20px",
  xl: "24px",
  "2xl": "32px",
  "3xl": "40px",
  "4xl": "48px",
} as const;

/* ----------------------------- 字体体系 ----------------------------- */
export const fontFamily = {
  display: "var(--font-display)",
  body: "var(--font-body)",
  mono: "var(--font-mono)",
} as const;

export const fontSize = {
  caption: "0.75rem", // 12px
  body: "0.875rem", // 14px
  base: "1rem", // 16px
  heading: "1.125rem", // 18px
  title: "1.25rem", // 20px
} as const;

/** 字重：与 DESIGN.md §3 对齐（400 正文 / 500 小标题 / 600 大标题 & CTA）。 */
export const fontWeight = {
  regular: 400,
  medium: 500,
  semibold: 600,
  bold: 700,
} as const;

export const lineHeight = {
  tight: 1.25,
  normal: 1.5,
  relaxed: 1.75,
} as const;

/** 字距：ALL CAPS 小标签按 DESIGN.md §3 使用 0.06em。 */
export const letterSpacing = {
  caps: "var(--tracking-caps)", // 0.06em
} as const;

/* ----------------------------- 圆角体系 ----------------------------- */
export const radius = {
  none: "0px",
  sm: "var(--radius-sm)", // 8px
  md: "var(--radius-md)", // 12px
  lg: "var(--radius-lg)", // 16px
  pill: "var(--radius-pill)", // 9999px
} as const;

/* ----------------------------- 阴影体系 ----------------------------- */
export const shadow = {
  none: "var(--elev-flat)",
  ring: "var(--elev-ring)",
  raised: "var(--elev-raised)",
  focus: "var(--focus-ring)",
} as const;

/* ----------------------------- 动效体系 ----------------------------- */
export const motion = {
  fast: "var(--motion-fast)", // 150ms
  base: "var(--motion-base)", // 200ms
  ease: "var(--ease-standard)",
} as const;

/* ----------------------------- z-index 层级 ----------------------------- */
export const zIndex = {
  base: 0,
  sticky: 10,
  dropdown: 20,
  modal: 50,
  toast: 60,
} as const;

/* ----------------------------- 尺寸体系 ----------------------------- */
/** 触摸目标最小边长（DESIGN.md §8：≥44px，对应 WCAG 2.5.5 AAA 建议值）。 */
export const TOUCH_TARGET_MIN_PX = 44;

/* ----------------------------- Tailwind 主题映射 ----------------------------- */
/**
 * 供 `tailwind.config.js` 直接引用，避免在 config 中重复定义。
 * 与 `tokens.css` 的 CSS 变量保持同名。
 *
 * 注意：此处必须**逐类**映射，遗漏的类别会导致对应工具类不生成
 * （例如缺少 `zIndex` 会让 `z-sticky`/`z-modal` 静默失效，
 *   缺少 `transitionTimingFunction` 会让 `ease-standard` 静默失效）。
 */
export const tailwindThemeExtend = {
  colors: {
    // 中性
    bg: color.neutral.bg,
    surface: color.neutral.surface,
    "surface-2": color.neutral.surface2,
    border: color.neutral.border,
    "border-soft": color.neutral.borderSoft,
    "border-strong": color.neutral.borderStrong,
    fg: color.neutral.fg,
    "fg-2": color.neutral.fg2,
    muted: color.neutral.muted,
    // 品牌
    accent: {
      DEFAULT: color.accent,
      solid: color.accentSolid,
      "solid-hover": color.accentSolidHover,
      "solid-active": color.accentSolidActive,
      on: color.accentOn,
      soft: color.accentSoft,
    },
    // 语义
    success: { DEFAULT: color.success, soft: color.successSoft },
    warn: { DEFAULT: color.warn, soft: color.warnSoft },
    danger: {
      DEFAULT: color.danger,
      soft: color.dangerSoft,
      solid: color.dangerSolid,
      on: color.dangerOn,
    },
    // 兼容旧引用
    warning: color.warn,
  },
  fontFamily: {
    sans: fontFamily.body,
    display: fontFamily.display,
    body: fontFamily.body,
    mono: fontFamily.mono,
  },
  borderRadius: {
    sm: radius.sm,
    md: radius.md,
    lg: radius.lg,
    full: radius.pill,
    pill: radius.pill,
  },
  boxShadow: {
    ring: shadow.ring,
    raised: shadow.raised,
    focus: shadow.focus,
  },
  transitionDuration: {
    fast: "150ms",
    base: "200ms",
  },
  transitionTimingFunction: {
    standard: motion.ease,
  },
  letterSpacing: {
    caps: letterSpacing.caps,
  },
  zIndex: {
    base: String(zIndex.base),
    sticky: String(zIndex.sticky),
    dropdown: String(zIndex.dropdown),
    modal: String(zIndex.modal),
    toast: String(zIndex.toast),
  },
} as const;

export type ColorToken = typeof color;
export type SpacingToken = typeof spacing;
export type RadiusToken = typeof radius;
export type ShadowToken = typeof shadow;
export type ZIndexToken = typeof zIndex;
