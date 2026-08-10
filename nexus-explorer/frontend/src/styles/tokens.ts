/**
 * NexusChain Explorer — Design Tokens (TypeScript source of truth)
 *
 * 单一事实来源：所有颜色 / 间距 / 字体 / 圆角 / 阴影 / 动效常量集中定义。
 * CSS 变量同名定义在 `tokens.css`，本文件提供 JS 侧类型安全访问 + Tailwind
 * 扩展映射。组件应优先使用语义化 token（color.accent / spacing.md / ...），
 * 禁止散落魔法数字或裸 hex。
 *
 * 暗色优先（dark-first），浅色由 [data-theme='light'] 覆盖。
 */

/* ----------------------------- 颜色体系 ----------------------------- */
/**
 * 50–900 色阶：以 CSS 变量引用，避免在 JS 中硬编码 hex。
 * 语义化别名（primary/secondary/success/warning/danger/neutral）映射到
 * Tailwind 主题，组件通过 `bg-accent` / `text-success` 等使用。
 */
export const color = {
  // 品牌主色（靛蓝家族）
  primary: {
    DEFAULT: "var(--accent)",
    50: "rgba(91, 108, 240, 0.04)",
    100: "rgba(91, 108, 240, 0.08)",
    200: "rgba(91, 108, 240, 0.12)",
    300: "rgba(91, 108, 240, 0.20)",
    400: "rgba(91, 108, 240, 0.32)",
    500: "var(--accent)",
    600: "var(--accent-hover)",
    700: "var(--accent-active)",
    800: "rgba(91, 108, 240, 0.85)",
    900: "rgba(91, 108, 240, 0.92)",
    on: "var(--accent-on)",
    soft: "var(--accent-soft)",
  },
  // 中性色（surface / fg / border）
  neutral: {
    bg: "var(--bg)",
    surface: "var(--surface)",
    surface2: "var(--surface-2)",
    border: "var(--border)",
    borderSoft: "var(--border-soft)",
    fg: "var(--fg)",
    fg2: "var(--fg-2)",
    muted: "var(--muted)",
  },
  // 语义色
  success: "var(--success)",
  warning: "var(--warn)",
  danger: "var(--danger)",
  // 兼容旧命名
  accent: "var(--accent)",
  accentOn: "var(--accent-on)",
  accentSoft: "var(--accent-soft)",
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

/* ----------------------------- 圆角体系 ----------------------------- */
export const radius = {
  none: "0px",
  sm: "var(--radius-sm)", // 8px
  md: "var(--radius-md)", // 12px
  lg: "var(--radius-lg)", // 16px
  full: "var(--radius-pill)", // 9999px
} as const;

/* ----------------------------- 阴影体系 ----------------------------- */
export const shadow = {
  none: "var(--elev-flat)",
  ring: "var(--elev-ring)",
  sm: "0 1px 2px rgba(0, 0, 0, 0.30)",
  md: "0 2px 6px rgba(0, 0, 0, 0.35)",
  lg: "var(--elev-raised)",
  xl: "0 12px 32px rgba(0, 0, 0, 0.45)",
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

/* ----------------------------- Tailwind 主题映射 ----------------------------- */
/**
 * 供 tailwind.config.js 直接引用，避免在 config 中重复定义。
 * 与 `tokens.css` 的 CSS 变量保持同名。
 */
export const tailwindThemeExtend = {
  colors: {
    bg: color.neutral.bg,
    surface: color.neutral.surface,
    "surface-2": color.neutral.surface2,
    border: color.neutral.border,
    "border-soft": color.neutral.borderSoft,
    fg: color.neutral.fg,
    "fg-2": color.neutral.fg2,
    muted: color.neutral.muted,
    accent: {
      DEFAULT: color.accent,
      on: color.accentOn,
      soft: color.accentSoft,
      hover: color.primary[600],
      active: color.primary[700],
    },
    success: color.success,
    warn: color.warning,
    danger: color.danger,
  },
  fontFamily: {
    display: fontFamily.display,
    body: fontFamily.body,
    mono: fontFamily.mono,
  },
  borderRadius: {
    sm: radius.sm,
    md: radius.md,
    lg: radius.lg,
    full: radius.full,
  },
  boxShadow: {
    ring: shadow.ring,
    raised: shadow.lg,
    focus: shadow.focus,
  },
  transitionDuration: {
    fast: "150ms",
    base: "200ms",
  },
} as const;

export type ColorToken = typeof color;
export type SpacingToken = typeof spacing;
export type RadiusToken = typeof radius;
export type ShadowToken = typeof shadow;