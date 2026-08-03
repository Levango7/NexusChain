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
echo "[1/5] Namespace + ConfigMap + Secret"

# 2. Create genesis ConfigMap from JSON files
kubectl create configmap nexus-genesis \
  --from-file=nexus-genesis-generator.json="$DIR/genesis.json" \
  --from-file=validators.json="$DIR/validators.json" \
  --namespace=nexus \
  --dry-run=client -o yaml | kubectl apply -f -
echo "[2/5] Genesis ConfigMap"

# 3. Infrastructure (PostgreSQL + Redis)
kubectl apply -f "$DIR/30-infrastructure.yml"
echo "[3/7] Infrastructure (PostgreSQL + Redis)"

# Wait for infra to be ready
echo "Waiting for PostgreSQL..."
kubectl wait --for=condition=ready pod -l app=postgres -n nexus --timeout=120s 2>/dev/null || true
echo "Waiting for Redis..."
kubectl wait --for=condition=ready pod -l app=redis -n nexus --timeout=60s 2>/dev/null || true

# 4. Core chain nodes (StatefulSet)
kubectl apply -f "$DIR/20-core-statefulset.yml"
echo "[4/7] Core StatefulSet (4 nodes)"

# 5. Gateway (Deployment + HPA + Ingress)
kubectl apply -f "$DIR/10-gateway.yml"
echo "[5/7] Gateway (Deployment + HPA + Ingress)"

# 6. Monitoring (exporters + ServiceMonitors + alerts)
kubectl apply -f "$DIR/40-monitoring.yml"
echo "[6/7] Monitoring (PostgreSQL/Redis exporters + ServiceMonitors + PrometheusRule)"

# 7. Backup (PostgreSQL CronJob + PVC)
kubectl apply -f "$DIR/50-backup.yml"
echo "[7/7] Backup (PostgreSQL CronJob + PVC)"

# 8. NetworkPolicy baseline (default-deny + selective allow)
kubectl apply -f "$DIR/60-networkpolicy.yml"
echo "[8/8] NetworkPolicy baseline"

echo ""
echo "=== Deploy Complete ==="
echo "Check status: kubectl get pods -n nexus"
echo "Gateway logs: kubectl logs -f deploy/nexus-gateway -n nexus"
echo "Core logs:    kubectl logs -f nexus-core-0 -n nexus"
