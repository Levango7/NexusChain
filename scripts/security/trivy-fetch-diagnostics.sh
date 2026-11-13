#!/usr/bin/env bash
# 诊断 setup-trivy 安装路径的真实端点。
#
# 端点依据是源码（不是步名、不是 action.yml 摘要）：aquasecurity/trivy
# @75c4dc0f45c5d7ffd05ae26df1e0c666787bdf2a 的 contrib/install.sh——即
# setup-trivy 里 `bash ./trivy/contrib/install.sh -b ... -c setup-trivy v0.76.2`
# 执行的那份脚本：
#   1. tag 解析   github_release → GET https://github.com/<owner>/<repo>/releases/<tag>
#                 并带 `Accept: application/json`，且只有响应体含 `"tag_name":"`
#                 才算成功（否则报 `crit unable to find '<tag>'`）
#   2. 二进制      GET https://get.trivy.dev/trivy?os=..&arch=..&version=..&type=..&client=..
#                 （302 跳转后取包，install.sh 用 -L 跟随）
#   3. 校验和      GET https://github.com/aquasecurity/trivy/releases/download/<tag>/trivy_<ver>_checksums.txt
#
# 关键事实：install.sh 全程不访问 api.github.com。任何以 api.github.com 为对象的
# 探测，测的都不是失败的那条路径（此前的探针正是如此）。
#
# 输出读法：`rc=0` 而 `err=` 非空 ⇒ 前几次尝试失败、重试后成功（瞬态，非稳定故障）；
#           `rc!=0` ⇒ 三次重试全失败，err 首行即最外层原因；`http=000` ⇒ 未拿到任何响应。
#           `ratelimit=absent` ⇒ 该端点未下发 x-ratelimit-remaining 头（不是"剩余额度为 0"）。
#
# 用法: trivy-fetch-diagnostics.sh {gate|diagnose}
#   gate     ：三个端点任一不满足 install.sh 的前置条件 → 退出 1（保持门禁强度）
#   diagnose ：只打印证据，永远退出 0（setup-trivy 自身失败后复采，不掩盖原始失败）
set -u

MODE="${1:-gate}"
VERSION="${TRIVY_VERSION:-0.76.2}"
TAG="v${VERSION}"
OS=Linux
ARCH=64bit

TAG_URL="https://github.com/aquasecurity/trivy/releases/${TAG}"
ASSET_URL="https://get.trivy.dev/trivy?os=${OS}&arch=${ARCH}&version=${VERSION}&type=tar.gz&client=setup-trivy"
CHECKSUM_URL="https://github.com/aquasecurity/trivy/releases/download/${TAG}/trivy_${VERSION}_checksums.txt"

FAILED=""

# probe <label> <url> [extra curl args...]
# 打印：http / curl 退出码 rc / 生效 URL / ratelimit 头 / retry-after 头 / stderr 首行。
# rc 语义（curl man）：6=DNS 7=连接失败 28=超时 35=SSL 握手 52=空响应 56=接收中断。
probe() {
  label=$1
  url=$2
  shift 2

  hdr=$(mktemp)
  errf=$(mktemp)
  body=$(mktemp)
  out=$(curl -sS -L -D "$hdr" -o "$body" -w '%{http_code} %{url_effective}' \
        --connect-timeout 20 --max-time 300 \
        --retry 3 --retry-all-errors "$@" "$url" 2>"$errf")
  rc=$?
  code=$(printf '%s' "$out" | cut -d' ' -f1)
  eff=$(printf '%s' "$out" | cut -d' ' -f2-)
  eff=${eff%%\?*}   # 去掉签名查询串，只保留 主机/路径（跳转目标看主机即可）
  msg=$(head -n 1 "$errf" | tr -d '\r')
  rl=$(grep -i '^x-ratelimit-remaining:' "$hdr" | tail -n 1 | tr -d '\r' | awk '{print $2}')
  ra=$(grep -i '^retry-after:' "$hdr" | tail -n 1 | tr -d '\r' | awk '{print $2}')

  echo "${label}: http=${code:-000} rc=${rc} eff=${eff:-<空>} ratelimit=${rl:-absent} retry-after=${ra:-absent} err=${msg:-<空>} url=${url}"

  PROBE_BODY="$body"
  PROBE_CODE="${code:-000}"
  rm -f "$hdr" "$errf"
}

# 1) tag 解析：必须 200 且响应体含 "tag_name":"（install.sh 的 sed 依赖这个标记）
probe "Trivy tag" "$TAG_URL" -H 'Accept: application/json'
if [ "$PROBE_CODE" = "200" ] && grep -q '"tag_name":"' "$PROBE_BODY"; then
  echo "Trivy tag: tag_name=present"
else
  echo "Trivy tag: tag_name=absent（install.sh 会因此报 crit unable to find '${TAG}'）"
  FAILED="${FAILED} tag"
fi
rm -f "$PROBE_BODY"

# 2) 二进制资产（get.trivy.dev，302 后取包）
probe "Trivy asset" "$ASSET_URL"
if [ "$PROBE_CODE" != "200" ]; then
  FAILED="${FAILED} asset"
fi
rm -f "$PROBE_BODY"

# 3) 校验和文件
probe "Trivy checksum" "$CHECKSUM_URL"
if [ "$PROBE_CODE" != "200" ]; then
  FAILED="${FAILED} checksum"
fi
rm -f "$PROBE_BODY"

if [ -n "$FAILED" ]; then
  echo "Trivy 取法诊断: failed=${FAILED# }"
  if [ "$MODE" = "gate" ]; then
    echo "::error::Trivy 取法诊断失败（failed=${FAILED# }）——明细见上方三行；setup-trivy 的 install.sh 会在同一端点失败"
  fi
else
  echo "Trivy 取法诊断: failed=<无>"
fi

# diagnose 模式不改判定，只采证据
[ "$MODE" = "diagnose" ] && exit 0
[ -n "$FAILED" ] && exit 1
exit 0
