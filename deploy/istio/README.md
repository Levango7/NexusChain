# NexusChain Istio 服务网格部署指南

> Phase 3 架构演进 · P3-T1 · K8s 集群安装 Istio 服务网格

## 第1章 概述

### 1.1 目标

为 NexusChain 4 个 Spring Boot 服务引入 Istio 服务网格，实现：

- **零信任安全**：mesh 内全量 mTLS 双向认证 + 显式授权策略
- **流量治理**：超时 / 重试 / 金丝雀发布 / 熔断 / 负载均衡统一在数据面
- **可观测性**：Kiali 拓扑 + Jaeger 链路 + Prometheus 指标 + JSON 访问日志
- **外部入口**：Ingress Gateway 承接 HTTPS，TLS 1.3 + cert-manager 自动续期

### 1.2 服务清单

| 服务名 | 端口 | 职责 | mesh 内调用方 |
| --- | --- | --- | --- |
| nexus-gateway | 8080 | 支付网关（外部入口） | Ingress Gateway |
| nexus-bridge | 8084 | 跨链桥 | nexus-gateway |
| nexus-signing-service | 8082 | 签名服务 | nexus-gateway |
| nexus-wallet-service | 8083 | 钱包服务 | nexus-gateway |

### 1.3 与 Sentinel 的职责划分

| 维度 | Istio 负责 | Sentinel 负责 |
| --- | --- | --- |
| 限流 | 基础设施级连接池（maxConnections） | 业务级 QPS 限流（按 API / 用户 / 资源） |
| 熔断 | Pod 级驱逐（5xx×5 → 驱逐 30s） | 业务级慢调用比例 / 异常比例熔断 |
| 重试 | 基础设施级重试（5xx / connect-failure） | 不重试（业务幂等性由网关层保证） |
| 超时 | 数据面超时（3s） | 不超时（避免双重超时冲突） |
| 金丝雀 | 流量分割（90/10） | 不参与 |
| mTLS | 双向认证 | 不参与 |
| 热点参数 | 不支持 | 热点参数限流（按支付 ID / 钱包地址） |

**原则**：Istio 治理基础设施级流量，Sentinel 治理业务级流量，两者互补不重叠。

### 1.4 文件清单

| 文件 | 用途 |
| --- | --- |
| istio-operator.yaml | IstioOperator CRD：控制面安装配置 |
| namespace-labels.yaml | 命名空间 sidecar 注入标签 |
| virtualservices.yaml | 4 个 VirtualService：超时 / 重试 / 金丝雀 |
| destinationrules.yaml | 4 个 DestinationRule：熔断 / 连接池 / 负载均衡 |
| peer-authentication.yaml | mTLS STRICT 双向认证 |
| authorization-policies.yaml | 零信任授权策略（默认拒绝 + 显式允许） |
| kiali-dashboard.yaml | Kiali 可视化配置 + ServiceMonitor |
| telemetry.yaml | Telemetry API：metrics + tracing + access logs |
| ingress-gateway.yaml | 外部 HTTPS 入口 + cert-manager 证书 |
| README.md | 本文档 |

## 第2章 前置条件

### 2.1 集群要求

- K8s ≥ 1.26
- 节点 ≥ 3（控制面 HA 反亲和需要）
- 已部署：kube-prometheus-stack（monitoring 命名空间）、Jaeger（nexus 命名空间）、cert-manager
- LoadBalancer 能力（Ingress Gateway 外部暴露）

### 2.2 istioctl 安装

```bash
# 下载 istioctl 1.22.x（与 IstioOperator 配置版本对齐）
curl -L https://istio.io/downloadIstio | ISTIO_VERSION=1.22.4 sh -

# 移动到 PATH
sudo cp istio-1.22.4/bin/istioctl /usr/local/bin/

# 验证版本
istioctl version
# 期望输出：client version: 1.22.4
```

### 2.3 依赖组件验证

```bash
# 验证 Prometheus
kubectl get svc -n monitoring kube-prometheus-stack-prometheus
# 验证 Jaeger
kubectl get svc -n nexus jaeger-collector
# 验证 cert-manager
kubectl get pods -n cert-manager
```

## 第3章 安装步骤

### 3.1 安装 Istio 控制面

```bash
# 应用 IstioOperator CRD（控制面 + Ingress/Egress Gateway）
istioctl install -f deploy/istio/istio-operator.yaml --skip-verification

# 验证控制面
kubectl get pods -n istio-system
# 期望：
#   istiod-xxx-yyy        1/1 Running
#   istiod-xxx-zzz        1/1 Running   （2 副本 HA）
#   istio-ingressgateway-xxx  1/1 Running
#   istio-ingressgateway-yyy  1/1 Running
#   istio-egressgateway-xxx   1/1 Running
#   istio-egressgateway-yyy   1/1 Running
```

### 3.2 启用 sidecar 自动注入

```bash
# 为 nexus 命名空间打 istio-injection=enabled 标签
kubectl apply -f deploy/istio/namespace-labels.yaml

# 验证标签
kubectl get namespace nexus --show-labels
# 期望包含：istio-injection=enabled

# 滚动重启所有服务，触发 sidecar 注入
kubectl rollout restart deployment -n nexus
kubectl rollout status deployment/nexus-gateway -n nexus
kubectl rollout status deployment/nexus-bridge -n nexus
kubectl rollout status deployment/nexus-signing-service -n nexus
kubectl rollout status deployment/nexus-wallet-service -n nexus
```

### 3.3 应用流量治理配置

```bash
# DestinationRule（先于 VirtualService，避免无 subset 路由失败）
kubectl apply -f deploy/istio/destinationrules.yaml -n nexus

# VirtualService
kubectl apply -f deploy/istio/virtualservices.yaml -n nexus

# 验证
kubectl get destinationrules -n nexus
kubectl get virtualservices -n nexus
```

### 3.4 应用安全策略

```bash
# mTLS STRICT
kubectl apply -f deploy/istio/peer-authentication.yaml -n nexus

# 授权策略（默认拒绝 + 显式允许）
kubectl apply -f deploy/istio/authorization-policies.yaml -n nexus

# 验证
kubectl get peerauthentication -n nexus
kubectl get authorizationpolicies -n nexus
```

### 3.5 应用可观测性配置

```bash
# Telemetry API
kubectl apply -f deploy/istio/telemetry.yaml -n nexus

# Kiali（Helm 安装）
helm repo add kiali https://kiali.org/helm-charts
helm repo update
helm upgrade --install kiali-server kiali/kiali-server \
  -n istio-system --create-namespace \
  -f deploy/istio/kiali-dashboard.yaml

# 验证
kubectl get telemetry -n nexus
kubectl get pods -n istio-system -l app.kubernetes.io/name=kiali
```

### 3.6 应用外部入口配置

```bash
# 前置：创建 cert-manager ClusterIssuer（如 letsencrypt-prod）
# 见 https://cert-manager.io/docs/configuration/acme/

# 应用 Ingress Gateway + VirtualService + Certificate
kubectl apply -f deploy/istio/ingress-gateway.yaml -n nexus

# 验证证书签发
kubectl get certificate -n istio-server nexus-tls-cert
# 期望：READY=True

# 验证外部访问
curl -v https://api.nexus.local/actuator/health
# 期望：200 + TLS 1.3
```

## 第4章 验证清单

### 4.1 sidecar 注入验证

```bash
# 检查 Pod 是否包含 istio-proxy 容器
kubectl get pods -n nexus -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[*].name}{"\n"}{end}'
# 期望每行包含：istio-proxy

# 检查 sidecar 版本
istioctl version
kubectl get pods -n nexus -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[?(@.name=="istio-proxy")].image}{"\n"}{end}'
```

### 4.2 金丝雀发布操作

```bash
# 前置：部署 v2 版本（带 app.kubernetes.io/version=v2 标签）
# kubectl set image deployment/nexus-gateway nexus-gateway=ghcr.io/nexus/nexus-gateway:v2 -n nexus
# kubectl label deployment/nexus-gateway app.kubernetes.io/version=v2 -n nexus --overwrite

# 查看当前流量分割（90% stable / 10% canary）
kubectl get virtualservice nexus-gateway -n nexus -o yaml | grep weight

# 调整金丝雀比例（改为 50/50）
kubectl patch virtualservice nexus-gateway -n nexus --type=json -p='[
  {"op":"replace","path":"/spec/http/0/route/0/weight","value":50},
  {"op":"replace","path":"/spec/http/0/route/1/weight","value":50}
]'

# 观察流量分布（Kiali 拓扑图或 Prometheus 查询）
# sum(rate(istio_requests_total{destination_version="v2"}[1m])) /
# sum(rate(istio_requests_total[1m]))

# 金丝雀完成后：100% 切到 v2 或回滚到 v1
kubectl patch virtualservice nexus-gateway -n nexus --type=json -p='[
  {"op":"replace","path":"/spec/http/0/route/0/weight","value":100},
  {"op":"replace","path":"/spec/http/0/route/1/weight","value":0}
]'
```

### 4.3 熔断验证

```bash
# 查看某服务的熔断配置
kubectl get destinationrule nexus-gateway -n nexus -o yaml | grep -A 10 outlierDetection

# 模拟故障：让某 Pod 返回 5xx
# kubectl exec -n nexus deploy/nexus-gateway -- curl -X POST http://localhost:8080/api/v1/payments/fail

# 观察 Envoy 驱逐状态
istioctl proxy-config clusters <pod-name>.nexus -o json | jq '.[] | select(.name|test("nexus-gateway")) | .outlierDetection'

# Prometheus 查询驱逐次数
# sum(rate(istio_requests_total{response_code=~"5..",destination_service=~"nexus-gateway.*"}[1m]))
```

### 4.4 mTLS 验证

```bash
# 检查 mesh 内 mTLS 状态
istioctl authn tls-check nexus-gateway.nexus.svc.cluster.local
# 期望：mTLSMode: strict

# 检查证书
istioctl proxy-config secret <pod-name>.nexus -o json | jq '.dynamicActiveSecrets'

# 验证未认证请求被拒绝
kubectl run test --image=curlimages/curl -n nexus --rm -it --restart=Never -- \
  curl -k https://nexus-gateway:8080/actuator/health
# 期望：403（无客户端证书）
```

### 4.5 授权策略验证

```bash
# 验证 gateway → signing-service 允许
kubectl exec -n nexus deploy/nexus-gateway -- \
  curl nexus-signing-service:8082/api/v1/signing/health
# 期望：200

# 验证 gateway → signing-service 非签名路径被拒
kubectl exec -n nexus deploy/nexus-gateway -- \
  curl nexus-signing-service:8082/api/v1/wallet/balance
# 期望：403

# 验证 mesh 外部访问被拒
kubectl run test --image=curlimages/curl -n default --rm -it --restart=Never -- \
  curl nexus-signing-service.nexus:8082/api/v1/signing/health
# 期望：403（非 mesh 内身份）
```

### 4.6 可观测性验证

```bash
# Kiali 拓扑图
kubectl port-forward svc/kiali 20001:20001 -n istio-system
# 浏览器打开 http://localhost:20001

# Jaeger 链路
kubectl port-forward svc/jaeger-query 16686:16686 -n nexus
# 浏览器打开 http://localhost:16686

# Prometheus 指标
# sum(rate(istio_requests_total{namespace="nexus"}[1m])) by (destination_service)
```

## 第5章 运维操作

### 5.1 升级 Istio

```bash
# 金丝雀升级 sidecar 版本（不影响存量）
istioctl install --revision=1-23 -f deploy/istio/istio-operator.yaml

# 测试命名空间迁移到新版本
kubectl label namespace nexus istio.io/rev=1-23 --overwrite
kubectl rollout restart deployment -n nexus

# 全量迁移后卸载旧版本
istioctl uninstall --revision=1-22
```

### 5.2 故障排查

```bash
# sidecar 未注入
istioctl analyze -n nexus
# 期望：✔ No validation issues found

# 查看某 Pod 的 sidecar 配置
istioctl proxy-config listeners <pod-name>.nexus
istioctl proxy-config routes <pod-name>.nexus
istioctl proxy-config clusters <pod-name>.nexus

# 查看 sidecar 日志
kubectl logs <pod-name> -n nexus -c istio-proxy

# 检查 istiod 配置下发
istioctl proxy-status
```

### 5.3 卸载

```bash
# 移除命名空间注入标签
kubectl label namespace nexus istio-injection-
kubectl rollout restart deployment -n nexus

# 删除 Istio 配置
kubectl delete -f deploy/istio/ -n nexus

# 卸载控制面
istioctl uninstall --purge
kubectl delete namespace istio-system
```

## 第6章 注意事项

1. **不修改 Java 源代码**：Istio 治理在数据面，业务代码无感知
2. **不修改 Helm Chart**：sidecar 注入由 Istio webhook 完成，无需改 Deployment 模板
3. **不修改 application.yml**：服务发现仍由 Nacos 负责，Istio 仅治理 mesh 内流量
4. **双重限流**：Istio 连接池 + Sentinel QPS 限流，两者阈值需协调避免冲突
5. **健康检查**：actuator/health 路径在授权策略中显式放行，避免探针失败
6. **证书轮换**：Istio 工作负载证书 1h 轮换，无需人工干预
7. **金丝雀回滚**：VirtualService weight 调整是声明式的，回滚即改回 100/0
8. **Egress Gateway**：外部依赖（Nacos/Postgres/Redis）如需统一出口，配置 ServiceEntry

## 第7章 参考链接

- Istio 官方文档：https://istio.io/latest/docs/
- IstioOperator CRD：https://istio.io/latest/docs/reference/config/istio.operator/
- Telemetry API：https://istio.io/latest/docs/reference/config/telemetry/
- Kiali 文档：https://kiali.io/docs/
- cert-manager：https://cert-manager.io/docs/