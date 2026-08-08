#!/usr/bin/env bash
# =============================================================================
# NexusChain 本地 Trivy 安全扫描脚本
# -----------------------------------------------------------------------------
# 用途：在本地运行与 CI 一致的 Trivy 依赖漏洞扫描，方便开发者在提交前
#       发现并修复 CRITICAL 漏洞，避免 CI 流水线被阻断。
#
# 前置：已安装 Trivy
#   https://aquasecurity.github.io/trivy/latest/getting-started/installation/
#
# 用法：
#   bash scripts/run-trivy-scan.sh              # 文件系统扫描（默认）
#   bash scripts/run-trivy-scan.sh --docker     # 额外扫描 Docker 镜像
#   bash scripts/run-trivy-scan.sh --sarif      # 输出 SARIF 格式到 trivy-results.sarif
#   bash scripts/run-trivy-scan.sh --help
#
# 退出码：
#   0 — 无 CRITICAL 漏洞
#   1 — 发现 CRITICAL 漏洞（需修复后再提交）
#   2 — 环境错误（Trivy 未安装等）
# =============================================================================
set -uo pipefail

# ---------- 配置 ----------
# 跳过构建产物、缓存、IDE、JDK 等无关目录（与 CI security-scan.yml 保持一致）
SKIP_DIRS="build,out,target,.gradle,gradle,node_modules,jdk17,jdk-provision,.codeartsdoer"
# 阻断级别：仅 CRITICAL 导致退出码 1
BLOCK_SEVERITY="CRITICAL"
# 全量展示级别
REPORT_SEVERITY="CRITICAL,HIGH,MEDIUM,LOW"
# 需要扫描 Docker 镜像的模块（含 Dockerfile）
DOCKER_MODULES=("nexus-gateway" "nexus-bridge")

# ---------- 参数解析 ----------
MODE_FS=true
MODE_DOCKER=false
OUTPUT_FORMAT="table"
OUTPUT_FILE=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --docker)  MODE_DOCKER=true; shift ;;
    --sarif)   OUTPUT_FORMAT="sarif"; OUTPUT_FILE="trivy-results.sarif"; shift ;;
    --help|-h)
      sed -n '2,25p' "$0"
      exit 0 ;;
    *) echo "未知参数：$1（使用 --help 查看用法）" >&2; exit 2 ;;
  esac
done

# ---------- 颜色 ----------
if [[ -t 1 ]]; then
  C_GREEN=$'\033[32m'; C_RED=$'\033[31m'; C_YELLOW=$'\033[33m'; C_CYAN=$'\033[36m'; C_RESET=$'\033[0m'
else
  C_GREEN=""; C_RED=""; C_YELLOW=""; C_CYAN=""; C_RESET=""
fi

# ---------- 前置检查 ----------
if ! command -v trivy >/dev/null 2>&1; then
  printf '%s[ERROR]%s 未找到 trivy，请先安装：\n' "$C_RED" "$C_RESET" >&2
  printf '  https://aquasecurity.github.io/trivy/latest/getting-started/installation/\n' >&2
  exit 2
fi

printf '%s================================================================%s\n' "$C_CYAN" "$C_RESET"
printf '%s NexusChain 本地 Trivy 安全扫描（%s）%s\n' "$C_CYAN" "$(date '+%Y-%m-%d %H:%M:%S')" "$C_RESET"
printf '%s================================================================%s\n' "$C_CYAN" "$C_RESET"
printf 'Trivy 版本：%s\n' "$(trivy --version | head -n1)"
printf '跳过目录：%s\n' "$SKIP_DIRS"
printf '阻断级别：%s\n\n' "$BLOCK_SEVERITY"

EXIT_CODE=0

# =============================================================================
# 1. 文件系统扫描（Gradle 依赖 + 源码配置）
# =============================================================================
if [[ "$MODE_FS" == true ]]; then
  printf '%s[1/2] 文件系统扫描（fs mode）%s\n' "$C_CYAN" "$C_RESET"

  # 先展示全量漏洞（不阻断）
  if [[ -n "$OUTPUT_FILE" ]]; then
    trivy fs --severity "$REPORT_SEVERITY" \
             --skip-dirs "$SKIP_DIRS" \
             --format "$OUTPUT_FORMAT" \
             --output "$OUTPUT_FILE" \
             . || true
    printf 'SARIF 报告已写入：%s\n' "$OUTPUT_FILE"
  else
    trivy fs --severity "$REPORT_SEVERITY" \
             --skip-dirs "$SKIP_DIRS" \
             --format "$OUTPUT_FORMAT" \
             . || true
  fi

  # 再以 CRITICAL 级别判定是否阻断
  printf '\n%s--- 阻断判定（仅 CRITICAL）---%s\n' "$C_YELLOW" "$C_RESET"
  if trivy fs --severity "$BLOCK_SEVERITY" \
              --skip-dirs "$SKIP_DIRS" \
              --exit-code 1 \
              --format table \
              . ; then
    printf '%s[PASS]%s 未发现 CRITICAL 漏洞\n' "$C_GREEN" "$C_RESET"
  else
    printf '%s[FAIL]%s 发现 CRITICAL 漏洞，请修复后再提交\n' "$C_RED" "$C_RESET"
    EXIT_CODE=1
  fi
fi

# =============================================================================
# 2. Docker 镜像扫描（可选，--docker 启用）
# =============================================================================
if [[ "$MODE_DOCKER" == true ]]; then
  printf '\n%s[2/2] Docker 镜像扫描%s\n' "$C_CYAN" "$C_RESET"
  for module in "${DOCKER_MODULES[@]}"; do
    if [[ ! -f "$module/Dockerfile" ]]; then
      printf '%s[SKIP]%s %s/Dockerfile 不存在\n' "$C_YELLOW" "$C_RESET" "$module"
      continue
    fi
    printf '\n%s--- %s --%s\n' "$C_CYAN" "$module" "$C_RESET"
    IMAGE_TAG="${module}:local-scan"
    if ! docker build -t "$IMAGE_TAG" "./$module" 2>/dev/null; then
      printf '%s[FAIL]%s %s 镜像构建失败\n' "$C_RED" "$C_RESET" "$module"
      EXIT_CODE=1
      continue
    fi
    if trivy image --severity "$BLOCK_SEVERITY" \
                   --exit-code 1 \
                   --format table \
                   "$IMAGE_TAG" ; then
      printf '%s[PASS]%s %s 无 CRITICAL 漏洞\n' "$C_GREEN" "$C_RESET" "$module"
    else
      printf '%s[FAIL]%s %s 发现 CRITICAL 漏洞\n' "$C_RED" "$C_RESET" "$module"
      EXIT_CODE=1
    fi
    docker rmi "$IMAGE_TAG" >/dev/null 2>&1 || true
  done
fi

# =============================================================================
# 汇总
# =============================================================================
printf '\n----------------------------------------------------------------\n'
if [[ "$EXIT_CODE" -eq 0 ]]; then
  printf '%s安全扫描通过：未发现 CRITICAL 漏洞。%s\n' "$C_GREEN" "$C_RESET"
else
  printf '%s安全扫描未通过：存在 CRITICAL 漏洞，请修复后再提交。%s\n' "$C_RED" "$C_RESET"
fi
exit "$EXIT_CODE"