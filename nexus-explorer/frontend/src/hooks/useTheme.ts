import { useCallback, useEffect, useState } from "react";

/**
 * 主题切换 hook（质量审查 7c，2026-09-10——亮色主题死代码激活）。
 *
 * tokens.css 的主题机制是 `data-theme` 属性（`:root[data-theme='light']`
 * 覆盖暗色默认值），但此前全项目无任何代码设置该属性、也无切换入口——
 * 亮色变量集永远不生效（死代码）。本 hook 补齐：
 *
 * - 默认 "dark"（tokens.css 是 dark-first，`:root` 无属性即暗色）
 * - 首选 localStorage 持久化（键 nexus-theme），其次系统偏好
 *   （prefers-color-scheme: light 时首次访问给亮色）
 * - 切换时写 `document.documentElement.dataset.theme`（light）或
 *   移除属性（dark——回归 :root 默认）
 *
 * 注：Tailwind 的 `darkMode: "class"`（.dark 类）与 CSS 变量的
 * data-theme 是两套机制；组件全部走语义 token（bg-surface 等），无
 * dark: 变体依赖，故不联动 .dark 类。
 */

export type Theme = "dark" | "light";

const STORAGE_KEY = "nexus-theme";

function resolveInitialTheme(): Theme {
  if (typeof window === "undefined") {
    return "dark";
  }
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (stored === "dark" || stored === "light") {
    return stored;
  }
  // 无存储记录：跟随系统偏好（系统亮色则亮色，否则暗色默认）
  const prefersLight = window.matchMedia?.("(prefers-color-scheme: light)").matches;
  return prefersLight ? "light" : "dark";
}

function applyTheme(theme: Theme): void {
  if (typeof document === "undefined") {
    return;
  }
  if (theme === "light") {
    document.documentElement.dataset.theme = "light";
  } else {
    // 移除属性回归 :root 暗色默认
    delete document.documentElement.dataset.theme;
  }
}

export function useTheme(): { theme: Theme; toggleTheme: () => void; setTheme: (t: Theme) => void } {
  const [theme, setThemeState] = useState<Theme>(resolveInitialTheme);

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const setTheme = useCallback((t: Theme) => {
    setThemeState(t);
    try {
      window.localStorage.setItem(STORAGE_KEY, t);
    } catch {
      // localStorage 不可用（隐私模式等）：仅会话内生效，不阻断切换
    }
  }, []);

  const toggleTheme = useCallback(() => {
    setTheme(theme === "dark" ? "light" : "dark");
  }, [theme, setTheme]);

  return { theme, toggleTheme, setTheme };
}
