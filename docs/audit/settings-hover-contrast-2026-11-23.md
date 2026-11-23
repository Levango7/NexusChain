# Settings ghost hover 对比度修复（2026-11-23）

## 发现

#767 的 post-merge Explorer Frontend 在 `/settings` 报告 `color-contrast`：
`hover:text-accent` / `hover:bg-accent-soft` 的中间状态对比度 **4.15:1 < 4.5:1**。
这是实际 WCAG 缺陷，不是普通 flaky。

axe 给出的颜色 `#494f5f` / `#b6b9bd` 并非最终 token，而是 Button 共用
`transition-colors duration-base` 在 200ms 颜色渐变中的中间帧。仅把最终色值调深不能消除
其它中间帧，正确修法是取消文字/背景色插值。

## 修复

Button 统一改为 `transition-shadow`：hover 的文字、背景、边框颜色立即切换，焦点阴影仍可
过渡。组件结构、变体颜色 token、触摸目标与业务行为不变。

新增两层判据：

- `Button.test.tsx`：必须含 `transition-shadow`，不得含 `transition-colors`；
- 亮色设置页真实 Chrome：hover ghost 后 axe 零违规，并断言 computed
  `transitionProperty` 不含 color。

## 证据

- 修前：组件新判据红；真实 Chrome 的 transitionProperty 包含
  `color, background-color, border-color, text-decoration-color, fill, stroke`。
- 修后：Button **20/20**、亮色 hover 浏览器通过。
- 全量 frontend：**22 files / 222 tests**、format/lint/build/verify:build/verify:contrast 全绿。
- 完整浏览器：**17/17**，含全部公开/保护页 axe、亮色设置页 hover 与既有键盘/四态矩阵。

边界：本修复针对颜色渐变导致的中间低对比，不宣称完成人工屏幕阅读器或所有动画路径的
形式化证明；`prefers-reduced-motion` 既有支持不变。
