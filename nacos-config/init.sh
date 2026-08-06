#!/usr/bin/env bash
# =============================================================================
# NexusChain Nacos 初始化脚本
# 
# 在 Nacos 启动后执行，完成：
#   1. 创建 namespace（dev / test / prod）
#   2. 发布共享配置（nexus-common.yaml / nexus-sentinel-rules.yaml / nexus-seata.yaml）
#   3. 发布 Seata Server 配置（seataServer.properties, group=SEATA_GROUP）
#   4. 发布各微服务私有配置占位
# 
# 用法：
#   ./nacos-config/init.sh [NACOS_SERVER] [NAMESPACE]
#   默认 NACOS_SERVER=127.0.0.1:8848, NAMESPACE=public
# 
# 设计文档 §4.2.2 / §4.2.3 / §4.3.3 / §4.2.1（Seata）。
# =============================================================================
set -euo pipefail

NACOS_SERVER="${1:-127.0.0.1:8848}"
GROUP="NEXUS_GROUP"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Seata 配置使用的 group（与 Seata Server / Client 配置对齐，设计文档 §4.2.1）
SEATA_GROUP="SEATA_GROUP"

echo "=========================================="
echo " NexusChain Nacos 初始化"
echo "   server   : ${NACOS_SERVER}"
echo "   group    : ${GROUP}"
echo "   seata grp: ${SEATA_GROUP}"
echo "   config dir: ${SCRIPT_DIR}"
echo "=========================================="

# 等待 Nacos 就绪
echo "[1/5] 等待 Nacos 就绪..."
for i in $(seq 1 30); do
  if curl -sf "http://${NACOS_SERVER}/nacos/v1/console/health/readiness" >/dev/null 2>&1; then
    echo "  Nacos 就绪 (attempt ${i})"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  [ERROR] Nacos 30s 内未就绪，退出"
    exit 1
  fi
  sleep 1
done

# 2. 创建 namespace
echo "[2/5] 创建 namespace..."
create_namespace() {
  local id="$1" name="$2" desc="$3"
  # 幂等：先查询，已存在则跳过
  if curl -sf "http://${NACOS_SERVER}/nacos/v1/console/namespaces" 2>/dev/null | grep -q "\"namespace\":\"${id}\""; then
    echo "  namespace ${id} 已存在，跳过"
    return
  fi
  curl -sf -X POST "http://${NACOS_SERVER}/nacos/v1/console/namespaces" \
    -d "customNamespaceId=${id}&namespaceName=${name}&namespaceDesc=${desc}" >/dev/null
  echo "  namespace ${id} (${name}) 创建成功"
}

create_namespace "dev"  "开发环境" "NexusChain 开发环境命名空间"
create_namespace "test" "测试环境" "NexusChain 测试环境命名空间"
# prod 使用默认 public namespace，不单独创建

# 3. 发布共享配置
echo "[3/5] 发布共享配置..."
publish_config() {
  local dataId="$1" file="$2" ns="${3:-public}" grp="${4:-${GROUP}}" type="${5:-yaml}"
  if [ ! -f "${file}" ]; then
    echo "  [WARN] ${file} 不存在，跳过 ${dataId}"
    return
  fi
  local content
  content="$(cat "${file}")"
  curl -sf -X POST "http://${NACOS_SERVER}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "group=${grp}" \
    --data-urlencode "tenant=${ns}" \
    --data-urlencode "type=${type}" \
    --data-urlencode "content=${content}" >/dev/null
  echo "  ${dataId} (ns=${ns}, group=${grp}, type=${type}) 发布成功"
}

publish_config "nexus-common.yaml"          "${SCRIPT_DIR}/nexus-common.yaml"
publish_config "nexus-sentinel-rules.yaml"  "${SCRIPT_DIR}/nexus-sentinel-rules.yaml"
publish_config "nexus-seata.yaml"           "${SCRIPT_DIR}/nexus-seata.yaml"

# 4. 发布 Seata Server 配置（group=SEATA_GROUP，与 Seata Server / Client 配置对齐）
echo "[4/5] 发布 Seata Server 配置..."
# seata-server.properties: dataId=seataServer.properties, group=SEATA_GROUP, type=properties
# 设计文档 §3.1.3 / §4.2.1
publish_config "seataServer.properties"     "${SCRIPT_DIR}/seata-server.properties" "public" "${SEATA_GROUP}" "properties"

# 5. 发布各微服务私有配置占位（实际配置在 #54/#55 任务中完善）
echo "[5/5] 发布微服务私有配置占位..."
publish_service_placeholder() {
  local service="$1" ns="${2:-public}"
  local placeholder="# ${service} 私有配置占位（由 #54/#55 任务完善）\nspring:\n  application:\n    name: ${service}\n"
  curl -sf -X POST "http://${NACOS_SERVER}/nacos/v1/cs/configs" \
    --data-urlencode "dataId=${service}.yaml" \
    --data-urlencode "group=${GROUP}" \
    --data-urlencode "tenant=${ns}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=${placeholder}" >/dev/null
  echo "  ${service}.yaml (ns=${ns}) 占位发布成功"
}

publish_service_placeholder "nexus-signing-service"
publish_service_placeholder "nexus-wallet-service"
publish_service_placeholder "nexus-bridge"
publish_service_placeholder "nexus-gateway"

echo "=========================================="
echo " Nacos 初始化完成"
echo "   控制台: http://${NACOS_SERVER}/nacos"
echo "   默认账号: nacos / nacos（开发环境已关闭鉴权）"
echo "=========================================="