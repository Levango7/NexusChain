#!/usr/bin/env bash
# ============================================================================
# build-delivery-bundle.sh — 私有化交付包（离线可装）生成器
# ============================================================================
# 目的：把「仓库」变成「客户/交付方能拿走就装」的东西：
#       ① helm chart 包（可离线 install）；
#       ② 机器可读的依赖清单（镜像 + 版本 + 来源路径）；
#       ③ 校验和（SHA256SUMS）；
#       ④ 可选：镜像 tar（--with-images，需要本地已有镜像）。
#
# 设计原则（与 scripts/doctor.sh 一致）：
#   · **缺前置 ≠ 失败**：helm / docker 缺失时对应产物记 SKIP 并写明缺什么；
#   · **不联网**：镜像拉取是交付方/ops 的事，本脚本只打包本地已有的东西；
#   · **不落仓库**：产物默认写 `build/`（已被 .gitignore），可用 --out 改；
#   · **产物可核对**：每个文件都进 SHA256SUMS，manifest.json 记来源与版本。
#
# 用法：
#   bash scripts/build-delivery-bundle.sh                     # chart + 清单 + 校验和
#   bash scripts/build-delivery-bundle.sh --out D:/delivery   # 指定输出目录
#   bash scripts/build-delivery-bundle.sh --with-images       # 额外 docker save 镜像 tar
#   bash scripts/build-delivery-bundle.sh --compose-images    # 只含 compose 路径的基础设施镜像
#
# 退出码：0=产物已生成（可能含 SKIP 项），1=关键步骤失败，2=环境不满足
# ============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 2

OUT=""
WITH_IMAGES=0
WITH_JARS=0
COMPOSE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) shift; OUT="$1" ;;
    --with-images) WITH_IMAGES=1 ;;
    --with-jars) WITH_JARS=1 ;;
    --compose-images) COMPOSE_ONLY=1 ;;
    -h|--help) sed -n '22,44p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "未知参数：$1（--help）" >&2; exit 2 ;;
  esac
  shift
done

TS="$(date +%Y%m%d-%H%M%S)"
[ -z "$OUT" ] && OUT="$ROOT/build/delivery-bundle-$TS"
mkdir -p "$OUT/charts" "$OUT/images" "$OUT/jars" "$OUT/scripts" "$OUT/evidence" || { echo "无法创建输出目录 $OUT" >&2; exit 2; }

CHART_DIR="$ROOT/deploy/helm"
COMPOSE_FILE="$ROOT/docker-compose.prod.yml"
VALUES_PROD="$ROOT/deploy/helm/values-prod.yaml"
SKIPS=""; N_SKIP=0

skip() { SKIPS="$SKIPS$1\n"; N_SKIP=$((N_SKIP + 1)); }

# ---------------------------------------------------------------------------
# 0. 版本与来源
# ---------------------------------------------------------------------------
CHART_VER="$(grep -E '^version:' "$CHART_DIR/Chart.yaml" 2>/dev/null | head -1 | awk '{print $2}')"
APP_VER="$(grep -E '^appVersion:' "$CHART_DIR/Chart.yaml" 2>/dev/null | head -1 | tr -d '"' | awk '{print $2}')"
GIT_SHA="$(git rev-parse HEAD 2>/dev/null || echo unknown)"
GIT_SHORT="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
[ -z "$CHART_VER" ] && CHART_VER="unknown"

echo "== 私有化交付包生成 =="
echo "版本(chart/app): $CHART_VER / ${APP_VER:-unknown}"
echo "提交: $GIT_SHORT"
echo "输出: $OUT"

# ---------------------------------------------------------------------------
# 1. helm chart 包
# ---------------------------------------------------------------------------
CHART_TGZ=""
if command -v helm >/dev/null 2>&1; then
  if (cd "$CHART_DIR" && helm package . --destination "$OUT/charts" >/dev/null 2>&1); then
    CHART_TGZ="$(ls -1 "$OUT/charts"/*.tgz 2>/dev/null | head -1)"
    echo "chart: 已打包 ${CHART_TGZ#$OUT/}"
  else
    echo "chart: 打包失败（helm package 报错）" >&2
    (cd "$CHART_DIR" && helm package . --destination "$OUT/charts") 2>&1 | tail -n 5
    exit 1
  fi
else
  skip "helm 未安装 ⇒ 无 chart 包（交付方需自带 helm）"
  echo "chart: SKIP（无 helm）"
fi

# ---------------------------------------------------------------------------
# 2. 镜像清单
# ---------------------------------------------------------------------------
IMAGES_FILE="$OUT/images.lock"
: > "$IMAGES_FILE"

add_image() { # add_image <image> <source>
  [ -z "$1" ] && return 0
  grep -qxF "$1" "$IMAGES_FILE" && return 0
  printf '%s\t%s\n' "$1" "$2" >> "$IMAGES_FILE"
}

if [ "$COMPOSE_ONLY" -eq 0 ]; then
  RENDER="$OUT/helm-render.yaml"
  if command -v helm >/dev/null 2>&1; then
    if helm template nexus-chain "$CHART_DIR" -f "$VALUES_PROD" > "$RENDER" 2>/dev/null; then
      grep -E '^[[:space:]]*image:' "$RENDER" | sed 's/^[[:space:]]*image:[[:space:]]*//' | tr -d '"' | sort -u | while read -r img; do
        add_image "$img" "helm:deploy/helm+values-prod.yaml"
      done
      echo "images: helm 路径已渲染（$(grep -cE '^[[:space:]]*image:' "$RENDER") 处 image 声明）"
    else
      skip "helm template 渲染失败 ⇒ helm 路径镜像清单缺失"
      echo "images: helm 渲染失败（SKIP）"
    fi
  else
    skip "helm 未安装 ⇒ 无 helm 路径镜像清单"
  fi
fi

if [ -f "$COMPOSE_FILE" ]; then
  grep -E '^[[:space:]]*image:' "$COMPOSE_FILE" | sed 's/^[[:space:]]*image:[[:space:]]*//' | tr -d '"' | grep -v '^\${' | sort -u | while read -r img; do
    add_image "$img" "compose:docker-compose.prod.yml"
  done
  echo "images: compose 路径已收录"
fi

N_IMAGES="$(wc -l < "$IMAGES_FILE" | tr -d ' ')"
echo "images: 合计 $N_IMAGES 个（见 images.lock）"

# ---------------------------------------------------------------------------
# 3. 依赖清单（机器可读）
# ---------------------------------------------------------------------------
DEP_FILE="$OUT/dependencies.lock"
{
  echo "# NexusChain 私有化交付依赖清单（机器可读）"
  echo "chart_version=$CHART_VER"
  echo "app_version=${APP_VER:-unknown}"
  echo "git_sha=$GIT_SHA"
  echo "generated_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "jdk_required=17"
  echo "gradle_required=8.14"
  echo "rust_required=only-for-mpc-engine"
  echo "helm_chart_path=deploy/helm"
  echo "values_prod=deploy/helm/values-prod.yaml"
  echo "compose_file=docker-compose.prod.yml"
  echo "env_manifest=.env.prod.example"
  echo "self_check=scripts/doctor.sh"
  echo "acceptance=scripts/acceptance-e2e.sh"
  echo "upgrade_drill=scripts/upgrade-rollback-drill.sh"
  echo ""
  echo "# image<TAB>source"
  cat "$IMAGES_FILE"
} > "$DEP_FILE"

# ---------------------------------------------------------------------------
# 4. 预构建 jar（--with-jars）
# ---------------------------------------------------------------------------
# 为什么必须带 jar：docker-compose.prod.yml 里 nexus-* 服务全是 `build:`（从源码构建），
# 离线客户机若没有源码树 + Gradle/Rust 缓存就装不出来 ⇒ 交付包必须给**预构建产物**。
JAR_FILE="$OUT/jars.lock"
: > "$JAR_FILE"
if [ "$WITH_JARS" -eq 1 ]; then
  N_JAR=0
  for j in nexus-gateway nexus-core nexus-signing-service nexus-wallet-service \
           nexus-bridge nexus-api-gateway; do
    src="$(ls -1 "$ROOT/$j/build/libs/"*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | grep -v -- '-javadoc\.jar$' | head -1)"
    if [ -z "$src" ]; then
      skip "$j 无预构建 jar（先 ./gradlew :$j:bootJar）"
      continue
    fi
    cp "$src" "$OUT/jars/" 2>/dev/null || continue
    printf '%s\t%s\t%s\n' "$(basename "$src")" "$j" "$(sha256sum "$src" | awk '{print $1}')" >> "$JAR_FILE"
    N_JAR=$((N_JAR + 1))
  done
  echo "jars: 导出 $N_JAR 个预构建 jar（见 jars.lock）"
  [ "$N_JAR" -eq 0 ] && skip "未找到任何预构建 jar ⇒ 交付包不含裸进程安装路径"
else
  skip "未指定 --with-jars ⇒ 交付包不含预构建 jar（离线机需自备源码+Gradle 缓存）"
fi

# 依赖清单补记 jar 段（jar 在上一步才采集）
{
  echo ""
  echo "# jar<TAB>module<TAB>sha256"
  cat "$JAR_FILE" 2>/dev/null
} >> "$DEP_FILE"

# ---------------------------------------------------------------------------
# 5. 验收脚本 + 许可证/安全证据（始终随包）
# ---------------------------------------------------------------------------
# scripts/：交付方拿到包即可自证（体检 / 端到端 / 升级回滚演练 / 纳管任务入口 / 全部自检脚本）
# （整目录带过去：doctor.sh 会跑 14 项自检，缺脚本会记 SKIP，但交付方拿到全量才有体检价值）
if [ -d "$ROOT/scripts" ]; then
  (cd "$ROOT" && find scripts -type f ! -path '*/__pycache__/*' ! -name '*.pyc' -print0) \
    | while IFS= read -r -d '' f; do
        mkdir -p "$OUT/$(dirname "$f")" 2>/dev/null
        cp "$ROOT/$f" "$OUT/$f" 2>/dev/null
      done
fi
N_SCRIPTS="$(find "$OUT/scripts" -type f 2>/dev/null | wc -l | tr -d ' ')"
echo "scripts: 随包脚本 $N_SCRIPTS 个（含全部自检脚本与纳管入口）"

# 根级交付件：env 模板 + compose 清单（交付方要照着填；prod-env 自检也靠它们）
for f in .env.prod.example docker-compose.prod.yml; do
  [ -f "$ROOT/$f" ] && cp "$ROOT/$f" "$OUT/$f" 2>/dev/null
done

# evidence/：许可证声明 + 已知风险/豁免账 + 依赖清单
# （私有化交付必问「你们豁免了什么、许可怎么声明」——这些必须在包里，不能只在仓库里）
for e in LICENSE NOTICE .trivyignore \
         nexus-core/LICENSE \
         config/trivy-baseline.json \
         config/dependency-check-baseline.json \
         scripts/java-dependencies.json; do
  if [ -f "$ROOT/$e" ]; then
    mkdir -p "$OUT/evidence/$(dirname "$e")" 2>/dev/null
    cp "$ROOT/$e" "$OUT/evidence/$e" 2>/dev/null
  fi
done
N_EVID="$(find "$OUT/evidence" -type f 2>/dev/null | wc -l | tr -d ' ')"
echo "evidence: 许可证/安全基线/依赖清单 $N_EVID 份"

# ---------------------------------------------------------------------------
# 6. 可选：镜像 tar
# ---------------------------------------------------------------------------
if [ "$WITH_IMAGES" -eq 1 ]; then
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    SAVED=0; MISSING=0
    while read -r img _; do
      [ -z "$img" ] && continue
      safe="$(printf '%s' "$img" | tr '/:' '__')"
      if docker image inspect "$img" >/dev/null 2>&1; then
        docker save -o "$OUT/images/$safe.tar" "$img" >/dev/null 2>&1 && SAVED=$((SAVED + 1)) || MISSING=$((MISSING + 1))
      else
        MISSING=$((MISSING + 1))
      fi
    done < "$IMAGES_FILE"
    echo "images.tar: 导出 $SAVED 个，缺失（本地无该镜像）$MISSING 个"
    [ "$MISSING" -gt 0 ] && skip "本地缺 $MISSING 个镜像 ⇒ 离线包不完整，需在有镜像的机器上重跑 --with-images"
  else
    skip "docker 不可用（未安装或 daemon 未起）⇒ 未导出镜像 tar"
    echo "images.tar: SKIP（docker 不可用）"
  fi
else
  skip "未指定 --with-images ⇒ 交付包不含镜像 tar"
fi

# ---------------------------------------------------------------------------
# 5. manifest + 校验和
# ---------------------------------------------------------------------------
MANIFEST="$OUT/manifest.json"
{
  printf '{\n'
  printf '  "name": "nexuschain-private-delivery-bundle",\n'
  printf '  "chartVersion": "%s",\n' "$CHART_VER"
  printf '  "appVersion": "%s",\n' "${APP_VER:-unknown}"
  printf '  "gitSha": "%s",\n' "$GIT_SHA"
  printf '  "generatedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '  "images": [\n'
  first=1
  while read -r img src; do
    [ -z "$img" ] && continue
    [ $first -eq 0 ] && printf ',\n'
    first=0
    printf '    {"image": "%s", "source": "%s"}' "$img" "$src"
  done < "$IMAGES_FILE"
  printf '\n  ],\n'
  printf '  "imageCount": %s,\n' "$N_IMAGES"
  printf '  "contains": ["charts", "scripts", "evidence", "images.lock", "dependencies.lock", "manifest.json", "SHA256SUMS", "helm-render.yaml"],\n'
  printf '  "skips": [\n'
  first=1
  printf '%b' "$SKIPS" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    [ $first -eq 0 ] && printf ',\n'
    first=0
    printf '    "%s"' "$(printf '%s' "$line" | sed 's/"/\\"/g')"
  done
  printf '\n  ]\n}\n'
} > "$MANIFEST"

(cd "$OUT" && find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS 2>/dev/null)

echo
echo "== 产物 =="
(cd "$OUT" && find . -type f | sort | sed 's/^\.\//    /')
echo
echo "校验和: $OUT/SHA256SUMS"
if [ "$N_SKIP" -gt 0 ]; then
  echo "SKIP 项（缺前置，不等于通过）："
  printf '%b' "$SKIPS" | sed 's/^/  /'
fi
echo
echo "交付方下一步：把本目录整体拷到目标环境 → 按 docs/delivery/offline-install.md 安装"
exit 0
