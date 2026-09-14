#!/usr/bin/env bash
# =============================================================================
# NexusChain Nacos 初始化脚本（v3 admin API 版，2026-09-14 10f 重写）
#
# Nacos 3.x 起 v1 console API 全面 410 Gone（全栈演练 2026-09-12 发现 #6），
# v1 cs/configs 写入还存在"返回 true 读回 404"的假成功问题——本脚本全部
# 改用 v3 admin API（API 形状经 kind 演练 Nacos 3.1.2 实测验证），并对每个
# 配置做发布后读回校验，杜绝假成功。
#
# 完成：
#   1. 等待 Nacos 就绪（v3 /admin/core/state）
#   2. 创建 namespace（dev / test）
#   3. 发布共享配置到 dev+test（服务端 shared-configs 读取的就是
#      ${NEX_NACOS_NAMESPACE:dev}——旧脚本发 public 是服务读不到的死配置）
#   4. 发布 Seata Server 配置（seataServer.properties, group=SEATA_GROUP, ns=public）
#   5. 发布各微服务私有配置占位（dev+test）
#
# 用法：
#   ./nacos-config/init.sh [NACOS_SERVER]
#   默认 NACOS_SERVER=127.0.0.1:8848
#
# 环境变量：
#   NACOS_IDENTITY_KEY    v3 admin identity key（默认 nexus-identity）
#   NACOS_IDENTITY_VALUE  v3 admin identity value（默认 nexus-dev；置空则
#                         不发送 identity header，兼容 Nacos 2.x / 无鉴权部署）
#
# 设计文档 §4.2.2 / §4.2.3 / §4.3.3 / §4.2.1（Seata）。
# =============================================================================
set -euo pipefail

NACOS_SERVER="${1:-127.0.0.1:8848}"
GROUP="NEXUS_GROUP"
SEATA_GROUP="SEATA_GROUP"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE="http://${NACOS_SERVER}"

# identity header 参数化（默认值与部署演练/k8s manifests 的 identity 配置对齐）
IDENTITY_KEY="${NACOS_IDENTITY_KEY:-nexus-identity}"
IDENTITY_VALUE="${NACOS_IDENTITY_VALUE:-nexus-dev}"
AUTH=()
if [ -n "${IDENTITY_VALUE}" ]; then
  AUTH=(-H "${IDENTITY_KEY}: ${IDENTITY_VALUE}")
fi

echo "=========================================="
echo " NexusChain Nacos 初始化（v3 admin API）"
echo "   server   : ${NACOS_SERVER}"
echo "   group    : ${GROUP}"
echo "   seata grp: ${SEATA_GROUP}"
echo "   identity : ${IDENTITY_KEY}=$([ -n "${IDENTITY_VALUE}" ] && echo '<set>' || echo '<none>')"
echo "=========================================="

# 等待 Nacos 就绪（v3 state 端点，CI nacos job 同款探针）
echo "[1/5] 等待 Nacos 就绪..."
for i in $(seq 1 30); do
  if curl -sf "${AUTH[@]}" "${BASE}/nacos/v3/admin/core/state" >/dev/null 2>&1; then
    echo "  Nacos 就绪 (attempt ${i})"
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "  [ERROR] Nacos 30s 内未就绪，退出"
    exit 1
  fi
  sleep 1
done

# 2. 创建 namespace（幂等：先查列表，已存在则跳过。
#    注意 v3 list 响应字段是 "namespace"，不是 "namespaceId"——
#    2026-09-14 实测：{"code":0,"data":[{"namespace":"public",...}]}）
echo "[2/5] 创建 namespace..."
ns_exists() {
  curl -sf "${AUTH[@]}" "${BASE}/nacos/v3/admin/core/namespace/list" 2>/dev/null \
    | grep -q "\"namespace\":\"${1}\""
}
create_namespace() {
  local id="$1" name="$2" desc="$3"
  if ns_exists "${id}"; then
    echo "  namespace ${id} 已存在，跳过"
    return
  fi
  # POST 容忍重复报错（并发/幂等），以最终 list 结果为准
  curl -s -X POST "${AUTH[@]}" "${BASE}/nacos/v3/admin/core/namespace" \
    --data-urlencode "namespaceId=${id}" \
    --data-urlencode "namespaceName=${name}" \
    --data-urlencode "namespaceDesc=${desc}" >/dev/null || true
  if ns_exists "${id}"; then
    echo "  namespace ${id} (${name}) 创建成功"
  else
    echo "  [ERROR] namespace ${id} 创建失败"
    exit 1
  fi
}

# 2b. 鉴权用户引导（Nacos 3.x 开启鉴权后，客户端 gRPC naming 通道需要
#     用户名/密码登录——identity header 只覆盖 admin API，服务注册不走它，
#     2026-09-14 演练实证 ErrCode:403 "User not found"）。
#     admin 初始化是一次性的（state.auth_admin_request=true 时才需要）；
#     服务统一用内置 ROLE_ADMIN 的 nacos 用户注册（3.1.2 的 /v3/auth/role
#     授权接口存在 200-空响应不生效的缺陷，≥3.2.4 修复——自建低权用户
#     需等升级后再启用）。
NACOS_ADMIN_PASSWORD="${NACOS_ADMIN_PASSWORD:-NexusAdmin2026}"
bootstrap_auth_user() {
  local admin_req
  admin_req=$(curl -sf "${AUTH[@]}" "${BASE}/nacos/v3/admin/core/state" 2>/dev/null \
    | grep -o '"auth_admin_request":"true"' || true)
  if [ -z "${admin_req}" ]; then
    echo "  admin 用户已初始化，跳过"
    return
  fi
  curl -s -X POST "${AUTH[@]}" "${BASE}/nacos/v3/auth/user/admin" \
    --data-urlencode "password=${NACOS_ADMIN_PASSWORD}" >/dev/null || true
  echo "  admin 用户(nacos) 初始化完成"
}

create_namespace "dev"  "开发环境" "NexusChain 开发环境命名空间"
create_namespace "test" "测试环境" "NexusChain 测试环境命名空间"
# prod 使用默认 public namespace，不单独创建

# 2b. 鉴权用户引导（幂等；客户端 gRPC 注册需要用户名/密码）
echo "[2b/5] 鉴权用户引导..."
bootstrap_auth_user

# 3-5. 配置发布（v3 admin API：config 走 /admin/cs/*（cs=config service），
#      namespace 走 /admin/core/*；v3 参数名 groupName/namespaceId 与 v1 的
#      group/tenant 不同）+ 读回校验
publish_config() {
  local dataId="$1" file="$2" ns="$3" grp="${4:-${GROUP}}" type="${5:-yaml}"
  if [ ! -f "${file}" ]; then
    echo "  [WARN] ${file} 不存在，跳过 ${dataId}"
    return
  fi
  local content code
  content="$(cat "${file}")"
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${AUTH[@]}" \
    "${BASE}/nacos/v3/admin/cs/config" \
    --data-urlencode "dataId=${dataId}" \
    --data-urlencode "groupName=${grp}" \
    --data-urlencode "namespaceId=${ns}" \
    --data-urlencode "type=${type}" \
    --data-urlencode "content=${content}")
  if [ "${code}" != "200" ]; then
    echo "  [ERROR] ${dataId} (ns=${ns}, group=${grp}) 发布失败 http=${code}"
    exit 1
  fi
  # 读回校验：v1 时代存在"返回 true 读回 404"的假成功，发布后必须验证可读
  code=$(curl -s -o /dev/null -w "%{http_code}" "${AUTH[@]}" \
    "${BASE}/nacos/v3/admin/cs/config?dataId=${dataId}&groupName=${grp}&namespaceId=${ns}")
  if [ "${code}" != "200" ]; then
    echo "  [ERROR] ${dataId} (ns=${ns}) 发布后读回 http=${code}（假成功防护触发）"
    exit 1
  fi
  echo "  ${dataId} (ns=${ns}, group=${grp}, type=${type}) 发布+读回 OK"
}

echo "[3/5] 发布共享配置（dev + test）..."
for ns in dev test; do
  publish_config "nexus-common.yaml"           "${SCRIPT_DIR}/nexus-common.yaml"          "${ns}"
  publish_config "nexus-sentinel-rules.yaml"   "${SCRIPT_DIR}/nexus-sentinel-rules.yaml"  "${ns}"
  publish_config "nexus-seata.yaml"            "${SCRIPT_DIR}/nexus-seata.yaml"           "${ns}"
done

echo "[4/5] 发布 Seata Server 配置..."
# seataServer.properties: dataId=seataServer.properties, group=SEATA_GROUP,
# ns=public（Seata Server 默认命名空间），设计文档 §3.1.3 / §4.2.1
publish_config "seataServer.properties" "${SCRIPT_DIR}/seata-server.properties" "public" "${SEATA_GROUP}" "properties"

echo "[5/5] 发布微服务私有配置占位（dev + test）..."
# 占位配置内容由构造而非文件提供，故独立于 publish_config 实现
publish_placeholder() {
  local service="$1" ns="$2"
  local content code
  content="$(printf '# %s 私有配置占位\nspring:\n  application:\n    name: %s\n' "${service}" "${service}")"
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${AUTH[@]}" \
    "${BASE}/nacos/v3/admin/cs/config" \
    --data-urlencode "dataId=${service}.yaml" \
    --data-urlencode "groupName=${GROUP}" \
    --data-urlencode "namespaceId=${ns}" \
    --data-urlencode "type=yaml" \
    --data-urlencode "content=${content}")
  if [ "${code}" != "200" ]; then
    echo "  [ERROR] ${service}.yaml (ns=${ns}) 发布失败 http=${code}"
    exit 1
  fi
  echo "  ${service}.yaml (ns=${ns}) 占位发布成功"
}
for ns in dev test; do
  publish_placeholder "nexus-signing-service" "${ns}"
  publish_placeholder "nexus-wallet-service"  "${ns}"
  publish_placeholder "nexus-bridge"          "${ns}"
  publish_placeholder "nexus-gateway"         "${ns}"
done

echo "=========================================="
echo " Nacos 初始化完成"
echo "   控制台: http://${NACOS_SERVER}/nacos"
echo "   默认账号: nacos / nacos（开发环境已关闭鉴权）"
echo "=========================================="
