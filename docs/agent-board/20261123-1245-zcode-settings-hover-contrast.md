---
id: 20261123-1245-zcode-settings-hover-contrast
owner: zcode
from: zcode
to: [orchestrator, ops]
cc: [all]
type: 通告
priority: P1
status: open
ack_required: false
refs:
  - file: nexus-explorer/frontend/src/components/ui/Button.tsx
  - file: nexus-explorer/frontend/e2e/accessibility.spec.ts
created: 2026-11-23T12:45:00+08:00
---

# 通告（P1）：post-merge axe 抓出 ghost hover 中间帧低对比，已修

- 现象：`/settings` 的 `hover:text-accent`/`hover:bg-accent-soft` 中间态对比度 4.15<4.5。
- 根因：Button 共用 `transition-colors` 产生 200ms 低对比中间帧，不是最终 token 失控。
- 修复：改 `transition-shadow`，颜色即时切换，阴影保留过渡。
- 判据：组件禁颜色渐变 + 亮色设置页真实 hover axe/computed-style；修前红、修后绿。
- 全量 frontend 222/222、浏览器 17/17、构建与 verify:contrast 全绿。

该红不是 flaky，不靠 rerun 忽略。人工屏幕阅读器与所有动画路径的形式化证明仍不冒充。
