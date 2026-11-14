#!/usr/bin/env bash
# ============================================================================
# B-11：kind 告警端到端演练（nexus 规则真集群 firing → Alertmanager → webhook sink）
# ============================================================================
# 用法：
#   deploy/scripts/kind-alert-drill.sh up      # 建集群 → 装栈 → 补丁/载荷 → 就绪等待
#   deploy/scripts/kind-alert-drill.sh verify  # 读三层证据（Prometheus / Alertmanager / sink access log）
#   deploy/scripts/kind-alert-drill.sh logs    # sink 原始日志
#   deploy/scripts/kind-alert-drill.sh down    # 删集群
#
# 设计要点（2026-11-14 首次演练沉淀；每一条都对应一类真实环境坑，逐条内化）：
#   1. 图表离线位 CHART_TGZ：本机 github.com 443 有持续瞬断窗，可经反代离线下载 tgz
#      （下载命令见下方注释）后经本地文件安装；
#   2. --wait=false + 自行轮询：本机 Docker Desktop 曾有 API 悬停窗，helm client-wait 会死于
#      "context deadline exceeded"；改为 apply 后自己等就绪；
#   3. 幂等：集群 / secret / 载荷均可重复 apply；helm 重试 ×5；
#   4. 受限网络镜像四招：registry.k8s.io → m.daocloud（values 内覆盖，注意 registry/repository
#      两段都要改）；docker.io 经节点内 ctr 从 m.daocloud 拉取后 tag 回原名；quay.io 直连可达
#      但预拉更稳；全部经 crictl/ctr 在节点内预拉（绕开 kubelet 拉取卡死）；
#   5. admission webhooks 关闭（演练不需要），但 operator 仍挂 tls-secret ⇒ 脚本自签补齐；
#      **键名必须是 cert/key/ca**（operator 参数 --web.cert-file=/cert/cert，实测崩因）；
#   6. chart 的 config 与默认合并会产生 route.receiver="null" 怪癖 ⇒ AM 配置 secret 以
#      演练版直补（assets/am-drill.yaml）；Prometheus v3 对抓取 Content-Type 严判 ⇒
#      静态源用 .txt 后缀让 nginx 以 text/plain 供出（operator 0.95.2 CRD 不支持
#      fallbackScrapeProtocol 字段，实测另一路）。
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/../.."

CLUSTER=nc-alert-drill
NS=monitoring
KPS=kps
DIR=deploy/monitoring/kind-alert-drill

# 图表来源：默认官方 repo（版本钉住）。受限网络离线位：
#   curl -sL -o F:/tmp/kps-93.3.0.tgz \
#     "https://ghproxy.net/https://github.com/prometheus-community/helm-charts/releases/download/kube-prometheus-stack-93.3.0/kube-prometheus-stack-93.3.0.tgz"
#   CHART_TGZ=F:/tmp/kps-93.3.0.tgz deploy/scripts/kind-alert-drill.sh up
if [ -n "${CHART_TGZ:-}" ]; then
  [ -f "$CHART_TGZ" ] || { echo "CHART_TGZ 指向的文件不存在：$CHART_TGZ"; exit 2; }
  CHART="$CHART_TGZ"
  HELM_VER_ARGS=()
else
  CHART="prometheus-community/kube-prometheus-stack"
  HELM_VER_ARGS=(--version 93.3.0)
fi

wait_pods_ready() { # 等关键四件（operator/ksm/alertmanager/prometheus）就绪，≤10min
  local deadline=$((SECONDS + 600)) ready
  while [ $SECONDS -lt $deadline ]; do
    ready=$(kubectl -n "$NS" get pods --no-headers 2>/dev/null \
      | awk '{split($2,a,"/"); if ($3=="Running" && a[1]==a[2] && a[1]>0) print $1}' \
      | grep -c -e operator -e kube-state-metrics -e alertmanager -e prometheus-kps || true)
    if [ "${ready:-0}" -ge 4 ]; then echo "关键四件已就绪"; return 0; fi
    sleep 15
  done
  echo "就绪等待超时（10min）——可继续 verify 或查 pods"; return 0
}

prepull() { # 节点内强拉镜像（docker.io 自动换源 m.daocloud 并 tag 回原名），每枚重试 ×3
  local node="${CLUSTER}-control-plane" img ok mirror
  for img in "$@"; do
    [ -z "$img" ] && continue
    ok=""
    for r in 1 2 3; do
      if docker exec "$node" crictl pull "$img" >/dev/null 2>&1; then ok=1; break; fi
      case "$img" in
        docker.io/*|nginx:*|busybox:*)
          case "$img" in docker.io/*) mirror="m.daocloud.io/$img" ;; *) mirror="m.daocloud.io/docker.io/library/$img" ;; esac
          if docker exec "$node" ctr -n k8s.io i pull "$mirror" >/dev/null 2>&1; then
            case "$img" in
              docker.io/*) docker exec "$node" ctr -n k8s.io i tag --force "$mirror" "$img" >/dev/null 2>&1 ;;
              *) docker exec "$node" ctr -n k8s.io i tag --force "$mirror" "docker.io/library/$img" >/dev/null 2>&1 ;;
            esac
            ok=1; break
          fi ;;
      esac
      sleep 10
    done
    if [ "$ok" = 1 ]; then echo "  prepull OK: $img"; else echo "  prepull FAILED: $img（kubelet 仍会自动重试）"; fi
  done
}

case "${1:-}" in
  up)
    if ! kind get clusters | grep -qx "$CLUSTER"; then
      kind create cluster --name "$CLUSTER"
    else
      kind export kubeconfig --name "$CLUSTER" >/dev/null
    fi
    kubectl create namespace nexus --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    ok=""
    for attempt in 1 2 3 4 5; do
      if helm upgrade --install "$KPS" "$CHART" "${HELM_VER_ARGS[@]}" -n "$NS" --create-namespace \
           -f "$DIR/values-kind-alert-drill.yaml" --wait=false --timeout 3m; then
        ok=1; break
      fi
      echo "helm apply 第 ${attempt} 次失败，20s 后重试…"; sleep 20
    done
    [ "$ok" = 1 ] || { echo "helm apply 重试 5 次仍失败，放弃"; exit 1; }

    # operator tls-secret 自签补齐（键名 cert/key/ca，否则 operator 起而复崩）
    if ! kubectl -n "$NS" get secret kps-kube-prometheus-stack-admission >/dev/null 2>&1; then
      CERT_DIR=$(mktemp -d)
      openssl req -x509 -newkey rsa:2048 -nodes -keyout "$CERT_DIR/tls.key" \
        -out "$CERT_DIR/tls.crt" -days 365 -subj "//CN=kps-admission" 2>/dev/null
      kubectl -n "$NS" create secret generic kps-kube-prometheus-stack-admission \
        --from-file=cert="$CERT_DIR/tls.crt" --from-file=key="$CERT_DIR/tls.key" \
        --from-file=ca="$CERT_DIR/tls.crt" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
      [ -n "$CERT_DIR" ] && rm -rf -- "$CERT_DIR"
    fi

    kubectl apply -f deploy/k8s/50-prometheus-rules.yaml >/dev/null
    kubectl apply -f "$DIR/drill-sink.yaml" >/dev/null
    kubectl apply -f "$DIR/drill-metrics.yaml" >/dev/null

    # AM 配置直补（绕开 chart 默认合并的 route.receiver=null）
    kubectl -n "$NS" create secret generic alertmanager-kps-kube-prometheus-stack-alertmanager \
      --from-file=alertmanager.yaml="$DIR/am-drill.yaml" --dry-run=client -o yaml | kubectl apply -f - >/dev/null

    # 镜像预拉：集群内已渲染引用 + 显式补齐（含 operator 主导创建的 prom/am 与 sidecar）
    mapfile -t IMGS < <(kubectl -n "$NS" get deploy,daemonset \
      -o jsonpath='{range .items[*].spec.template.spec.containers[*]}{.image}{"\n"}{end}' 2>/dev/null | sort -u)
    prepull "${IMGS[@]}" nginx:1.27-alpine \
      quay.io/prometheus-operator/prometheus-config-reloader:v0.95.2 \
      quay.io/prometheus/prometheus:v3.16.0-distroless \
      quay.io/prometheus/alertmanager:v0.35.0

    wait_pods_ready
    echo "UP 完成。约 6-8 分钟后跑 verify（告警 for: 5m）。"
    ;;
  verify)
    echo "== 1) Prometheus 侧告警状态（firing/pending）=="
    kubectl -n "$NS" exec deploy/drill-sink -- wget -qO- \
      "http://${KPS}-kube-prometheus-stack-prometheus:9090/api/v1/alerts" | head -c 4000; echo
    echo "== 2) Alertmanager 侧收到的告警 =="
    kubectl -n "$NS" exec deploy/drill-sink -- wget -qO- \
      "http://${KPS}-kube-prometheus-stack-alertmanager:9093/api/v2/alerts" | head -c 4000; echo
    echo "== 3) sink 收据（/alert POST + 200）=="
    kubectl -n "$NS" logs deploy/drill-sink --tail=200 | grep -F '/alert' || \
      echo "（暂无 POST——若第 1/2 步未出现 firing，稍后再跑 verify）"
    ;;
  logs)
    kubectl -n "$NS" logs deploy/drill-sink --tail=200
    ;;
  down)
    kind delete cluster --name "$CLUSTER"
    ;;
  *)
    echo "用法: $0 up|verify|logs|down"
    exit 2
    ;;
esac
