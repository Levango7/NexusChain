#!/usr/bin/env bash
# E4 留言板单点门禁（板 20261105-0445，2026-11-05）
# ------------------------------------------------------------
# 检查共享主树工作区是否存在「写了但从未提交」的 docs/agent-board/ 文件。
#
# 实证背景：0247/0249 两条留言曾只存在于共享主树工作区（git status ??）、
# master 不可见，0249（法币收款方政策裁决请求）因此悬置五日，最终由 zcode
# 代送达（PR #444）才销账。多 Agent 协作的唯一跨线通信介质是留言板，这条
# 单点必须有人盯。
#
# 用法：
#   check-main-tree-board-hygiene.sh [主树路径]           # 检查模式
#   check-main-tree-board-hygiene.sh --install-hook [主树路径]   # 装 pre-push hook
#
#   主树路径默认 F:/Nexus/NexusChain（AGENTS.md §4 约定的共享主树）。
#
# 退出码：
#   0 = 干净（无未提交 board 文件）
#   1 = 存在未提交 board 文件（stderr 逐行列出）
#   2 = 主树路径不可用（非 git 仓 / 不存在）
#
# 覆盖边界（如实声明，勿高估）：
#   · pre-push hook 只在「从主树直接 push」时触发；agent 从各自 worktree push
#     不经过主树 hook。完整覆盖 = 本脚本以检查模式**定时执行**（Windows 计划
#     任务 / 任意 agent 会话的 cron 巡检均可：退出码即结论，stderr 即清单）；
#   · CI 看不到本地工作区，所以本检查只能活在本机——这是设计边界，不是偷懒；
#   · 与 qoder CN 的签收门禁（ack_required/expires）互补：那条管「已投递没人
#     签收」，本条管「压根没投出去」。
set -u

MODE="check"
if [ "${1:-}" = "--install-hook" ]; then
  MODE="install"
  MAIN="${2:-F:/Nexus/NexusChain}"
else
  MAIN="${1:-F:/Nexus/NexusChain}"
fi

if ! git -C "$MAIN" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "ERROR: 主树路径不是 git 仓库: $MAIN" >&2
  exit 2
fi

DIRTY="$(git -C "$MAIN" status --porcelain -uall -- docs/agent-board/ 2>/dev/null)"

if [ "$MODE" = "check" ]; then
  if [ -n "$DIRTY" ]; then
    echo "存在未提交的留言板文件（留言只在你本机，master 上别人看不见）：" >&2
    printf '%s\n' "$DIRTY" >&2
    echo "处置：把上述文件 commit 并 push 投板（或删除误留草稿）。" >&2
    exit 1
  fi
  exit 0
fi

# --install-hook：向主树 .git/hooks/pre-push 安装检查（已有 hook 先备份）
HOOK="$MAIN/.git/hooks/pre-push"
if [ -f "$HOOK" ]; then
  cp "$HOOK" "$HOOK.bak.$(date +%Y%m%d%H%M%S)" || { echo "ERROR: 备份失败" >&2; exit 2; }
  echo "已备份既有 pre-push hook"
fi
SCRIPT_PATH="$MAIN/scripts/check-main-tree-board-hygiene.sh"
{
  echo '#!/bin/sh'
  echo '# E4 留言板单点门禁（板 20261105-0445）。由 check-main-tree-board-hygiene.sh --install-hook 生成。'
  echo "# 注意：只覆盖「从主树直接 push」；worktree push 不经过本 hook，定时巡检请另行挂计划任务。"
  echo "exec '$SCRIPT_PATH' '$MAIN'"
} > "$HOOK"
chmod +x "$HOOK"
echo "pre-push hook 已安装: $HOOK"
echo "注意：主树需先更新到含本脚本的提交，hook 才能正常执行。"
exit 0
