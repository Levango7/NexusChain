# P2P gRPC TLS 部署指南

> 对应需求：REQ-19 安全加固
> 生成日期：2026-08-17
> 适用范围：nexus-core P2P 节点间通信加密

## 1. 概述

nexus-core 节点间通过 gRPC 进行 P2P 通信（区块同步、交易广播、共识消息）。
原实现使用明文传输（`usePlaintext()`），存在中间人攻击与流量篡改风险。

REQ-19 启用 mTLS 双向认证：
- **服务端 TLS**：节点对外暴露 gRPC 端口时要求客户端证书
- **客户端 TLS**：节点作为客户端连接对端时出示自身证书
- **信任库**：CA 证书用于验证对端身份

## 2. 证书生成

### 2.1 CA 证书

```bash
# 生成 CA 私钥
openssl genrsa -out ca-key.pem 4096
# 生成 CA 证书（自签名）
openssl req -new -x509 -key ca-key.pem -out ca-cert.pem -days 3650 \
    -subj "/C=CN/O=NexusChain/CN=NexusChain P2P CA"
```

### 2.2 节点证书

为每个节点生成独立证书（假设 4 节点：node-0 ~ node-3）：

```bash
for i in 0 1 2 3; do
    # 节点私钥
    openssl genrsa -out node-${i}-key.pem 2048
    # CSR
    openssl req -new -key node-${i}-key.pem -out node-${i}.csr \
        -subj "/C=CN/O=NexusChain/CN=node-${i}"
    # 由 CA 签发节点证书
    openssl x509 -req -in node-${i}.csr -CA ca-cert.pem -CAkey ca-key.pem \
        -CAcreateserial -out node-${i}-cert.pem -days 365
done
```

## 3. 证书分发

每个节点持有：
- 自身证书链：`node-${i}-cert.pem`
- 自身私钥：`node-${i}-key.pem`
- CA 证书（信任库）：`ca-cert.pem`

通过 K8s Secret 挂载或节点本地安全存储分发，**禁止私钥通过网络明文传输**。

## 4. 环境变量配置

每个 nexus-core 节点启动时注入以下环境变量：

```bash
GRPC_TLS_ENABLED=true
GRPC_TLS_CERT_CHAIN=/path/to/node-${i}-cert.pem
GRPC_TLS_PRIVATE_KEY=/path/to/node-${i}-key.pem
GRPC_TLS_TRUST_STORE=/path/to/ca-cert.pem
```

K8s ConfigMap/Secret 示例：

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: nexus-core-p2p-tls-node-0
type: Opaque
stringData:
  cert-chain: |
    -----BEGIN CERTIFICATE-----
    ...
  private-key: |
    -----BEGIN RSA PRIVATE KEY-----
    ...
  trust-store: |
    -----BEGIN CERTIFICATE-----
    ...
```

StatefulSet volume 挂载：

```yaml
spec:
  template:
    spec:
      containers:
        - name: nexus-core
          env:
            - name: GRPC_TLS_ENABLED
              value: "true"
            - name: GRPC_TLS_CERT_CHAIN
              value: /etc/nexus/tls/cert.pem
            - name: GRPC_TLS_PRIVATE_KEY
              value: /etc/nexus/tls/key.pem
            - name: GRPC_TLS_TRUST_STORE
              value: /etc/nexus/tls/ca.pem
          volumeMounts:
            - name: p2p-tls
              mountPath: /etc/nexus/tls
              readOnly: true
      volumes:
        - name: p2p-tls
          secret:
            secretName: nexus-core-p2p-tls-node-0
```

## 5. 证书轮换

### 5.1 轮换周期

- **节点证书**：365 天（生成时 `-days 365`）
- **CA 证书**：10 年（生成时 `-days 3650`）

### 5.2 轮换脚本

`scripts/rotate-p2p-certs.sh`（待补充）应执行：
1. 生成新节点证书
2. 更新 K8s Secret
3. 滚动重启 StatefulSet（`kubectl rollout restart statefulset/nexus-core`）
4. 监控节点共识状态，确认全部节点重新加入网络

### 5.3 监控

- 证书过期前 30 天告警（Prometheus `x509_cert_not_after` 指标）
- gRPC 握手失败率监控（`grpc_server_handled_total{grpc_status!="OK"}`）

## 6. 开发环境

dev 环境通过 `GRPC_TLS_ENABLED=false` 关闭 TLS，使用明文通信（仅本地开发）：

```bash
export GRPC_TLS_ENABLED=false
./gradlew :nexus-core:nexus-core:bootRun
```

## 7. 验证

启用 TLS 后：
1. 启动 4 节点集群，全部节点加入网络
2. `tcpdump` 抓包确认 gRPC 流量加密（无明文 RPC 方法名）
3. 移除一个节点的证书，确认该节点无法加入网络（mTLS 拒绝）
4. 监控共识状态：`nexus_consensus_finality_height` 4 节点一致增长