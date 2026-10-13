#!/usr/bin/env bash
# NexusChain K8s One-Command Deploy
# Usage: ./deploy.sh
# Prerequisites: kubectl configured, cluster accessible
# Applies all manifests in dependency order.

set -e
DIR="$(cd "$(dirname "$0")" && pwd)"

echo "=== NexusChain K8s Deploy ==="
echo "Applying manifests in order..."

# 1. Namespace + Config + Secrets
kubectl apply -f "$DIR/00-namespace-config.yml"
echo "[1/12] Namespace + ConfigMap + Secret"

# 2. genesis ConfigMap —— L3 预演缺陷 #28：改为**可选**。
#    core 缺省用镜像内置 classpath genesis（schema 以
#    nexus-core/src/main/resources/genesis/NexusChain-genesis-generator.json 为准）。
#    $DIR/genesis.json / validators.json 是旧占位且 schema 与 core 不符，不能再直接建 ConfigMap。
#    自定义链参数：把裁剪后的两个文件放 $DIR/custom-genesis/ 下（键名须为
#    nexus-genesis-generator.json / validators.json），本步骤自动建 ConfigMap；
#    并需在 20-core-statefulset.yml 按其中注释加回 GENESIS_FILE/VALIDATORS env 与 /genesis 卷。
if [ -d "$DIR/custom-genesis" ]; then
  kubectl create configmap nexus-genesis \
    --from-file=nexus-genesis-generator.json="$DIR/custom-genesis/nexus-genesis-generator.json" \
    --from-file=validators.json="$DIR/custom-genesis/validators.json" \
    --namespace=nexus \
    --dry-run=client -o yaml | kubectl apply -f -
  echo "[2/12] Genesis ConfigMap（自定义，来自 $DIR/custom-genesis/）"
else
  echo "[2/12] Genesis ConfigMap 跳过（使用 core 镜像内置 genesis；自定义见 05-core-genesis.yml.example）"
fi

# 3. Infrastructure (MySQL + Redis)
kubectl apply -f "$DIR/30-infrastructure.yml"
echo "[3/12] Infrastructure (MySQL 8 + Redis)"

# Wait for infra to be ready
echo "Waiting for MySQL..."
kubectl wait --for=condition=ready pod -l app=mysql -n nexus --timeout=180s 2>/dev/null || true
echo "Waiting for Redis..."
kubectl wait --for=condition=ready pod -l app=redis -n nexus --timeout=60s 2>/dev/null || true

# 4. Core chain nodes (StatefulSet)
kubectl apply -f "$DIR/20-core-statefulset.yml"
echo "[4/12] Core StatefulSet (4 nodes)"

# 5. Gateway (Deployment + HPA + Ingress)
kubectl apply -f "$DIR/10-gateway.yml"
echo "[5/12] Gateway (Deployment + HPA + Ingress)"

# 6. Bridge (Deployment + HPA)
kubectl apply -f "$DIR/15-bridge.yml"
echo "[6/12] Bridge (Deployment + HPA)"

# 7. Signing Service (Deployment + HPA)
kubectl apply -f "$DIR/25-signing.yml"
echo "[7/12] Signing Service (Deployment + HPA)"

# 8. Wallet Service (Deployment + HPA)
kubectl apply -f "$DIR/26-wallet.yml"
echo "[8/12] Wallet Service (Deployment + HPA)"

# 9. API Gateway (Deployment + HPA + Ingress)
kubectl apply -f "$DIR/27-api-gateway.yml"
echo "[9/12] API Gateway (Deployment + HPA + Ingress)"

# 10. Monitoring (exporters + ServiceMonitors + alerts)
kubectl apply -f "$DIR/40-monitoring.yml"
echo "[10/12] Monitoring (PostgreSQL/Redis exporters + ServiceMonitors + PrometheusRule)"

# 11. Backup (PostgreSQL CronJob + PVC)
kubectl apply -f "$DIR/50-backup.yml"
echo "[11/12] Backup (PostgreSQL CronJob + PVC)"

# 12. NetworkPolicy baseline (default-deny + selective allow)
kubectl apply -f "$DIR/60-networkpolicy.yml"
echo "[12/12] NetworkPolicy baseline"

echo ""
echo "=== Deploy Complete ==="
echo "Check status: kubectl get pods -n nexus"
echo "Gateway logs: kubectl logs -f deploy/nexus-gateway -n nexus"
echo "Core logs:    kubectl logs -f nexus-core-0 -n nexus"
