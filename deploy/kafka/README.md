# NexusChain Kafka 集群部署文档（Strimzi Operator）

> P3-T4：K8s 部署 Kafka 集群（Strimzi Operator 0.40+）
>
> 适用范围：NexusChain v2.1.0 Phase 3 架构演进
>
> 说明：此处 v2.1.0 为历史架构阶段标记（Phase 3 引入期），非当前发布版本；当前版本请见根 README.md。
>
> 部署栈：Strimzi Kafka Operator 0.40.x + Kafka 3.7.0 + ZooKeeper 3.8.3
>
> 本目录为 NexusChain 引入 Kafka 事件总线，为事件溯源（P3-T3）与异步 Webhook 提供基础设施。

## 第1章 文件清单

| 文件 | 用途 |
| --- | --- |
| `strimzi-operator.yaml` | Strimzi Cluster Operator 部署清单（Namespace / RBAC / Deployment） |
| `kafka-cluster.yaml` | Kafka 集群 CRD（3 broker + 3 zookeeper + 持久化 + 监听器 + 机架感知） |
| `kafka-topics.yaml` | KafkaTopic CRD（5 个 topic：3 主 + 2 死信队列） |
| `kafka-consumer-groups.yaml` | 消费者组配置约定（ConfigMap，4 个消费者组） |
| `kafka-monitoring.yaml` | 监控配置（ServiceMonitor + PrometheusRule + Grafana Dashboard） |
| `kafka-client-config.yaml` | Spring Boot Kafka 客户端配置片段 + Helm values 注入示例 |
| `README.md` | 本文档 |

## 第2章 前置条件

### 2.1 集群要求

- Kubernetes >= 1.24（推荐 1.27+，Pod Security Standards 已 GA）
- Helm 3 >= 3.12
- StorageClass `fast-ssd` 已存在（用于 Kafka/ZooKeeper PVC）
- 节点标签 `topology.kubernetes.io/zone` 已设置（机架感知依赖此标签）
- 至少 3 个 worker 节点（broker 反亲和要求每节点 1 broker）
- 每节点至少 4 核 CPU / 8Gi 内存 / 100Gi 磁盘（broker 资源需求）
- kube-prometheus-stack 已部署（监控集成依赖）

### 2.2 资源规划

| 组件 | 副本 | CPU 请求 | 内存请求 | CPU 上限 | 内存上限 | 磁盘 |
| --- | --- | --- | --- | --- | --- | --- |
| Kafka Broker | 3 | 1 core | 3Gi | 2 core | 4Gi | 100Gi PVC |
| ZooKeeper | 3 | 0.5 core | 1Gi | 1 core | 2Gi | 10Gi PVC |
| Strimzi Operator | 1 | 0.2 core | 256Mi | 1 core | 1Gi | - |
| Entity Operator | 1 | 0.1 core | 128Mi | 0.5 core | 512Mi | - |
| Kafka Exporter | 1 | 0.1 core | 128Mi | 0.5 core | 512Mi | - |

### 2.3 网络规划

| 监听器 | 端口 | 类型 | 用途 | 访问方 |
| --- | --- | --- | --- | --- |
| plain | 9092 | internal | 集群内明文 | nexus 业务 Pod |
| tls | 9093 | internal | 集群内 mTLS | 签名/钱包等敏感服务 |
| external | 9094 | NodePort 32000 | 运维调试 | 外部 kafka-cli |

## 第3章 安装 Strimzi Operator

### 3.1 安装 CRD

Strimzi CRD 定义体积较大（数千行），按官方推荐通过以下方式安装：

```bash
# 方式 A：直接 apply 官方 CRD（推荐，版本固定）
kubectl apply -f https://strimzi.io/install/latest -n kafka

# 方式 B：使用 Helm（便于版本管理与升级）
helm repo add strimzi https://strimzi.io/charts/
helm repo update
helm install strimzi-crds strimzi/strimzi-crds --version 0.40.x
```

### 3.2 部署 Operator

```bash
# 应用 Operator 部署清单（Namespace / RBAC / Deployment）
kubectl apply -f deploy/kafka/strimzi-operator.yaml

# 验证 Operator 就绪
kubectl get deployment strimzi-cluster-operator -n kafka -w
# 期望：1/1 running

# 验证 CRD 已注册
kubectl get crd | grep kafka.strimzi.io
# 期望输出：kafkas.kafka.strimzi.io / kafkatopics.kafka.strimzi.io / kafkausers.kafka.strimzi.io 等
```

### 3.3 集群模式说明

本清单采用**集群 Operator 模式**（`STRIMZI_NAMESPACE=*`），Operator 监听所有命名空间的 Kafka CRD。如需限定监听命名空间（多租户隔离），修改 `strimzi-operator.yaml` 中环境变量：

```yaml
env:
  - name: STRIMZI_NAMESPACE
    value: "kafka,nexus"  # 逗号分隔的命名空间列表
```

## 第4章 创建 Kafka 集群

### 4.1 应用集群配置

```bash
# 创建 Kafka 集群（含 ZooKeeper / Entity Operator / Kafka Exporter / 指标 ConfigMap）
kubectl apply -f deploy/kafka/kafka-cluster.yaml -n kafka

# 观察集群创建过程（约 2-5 分钟）
kubectl get kafka nexus-kafka -n kafka -w
# 期望：Ready=True

# 查看 broker Pod
kubectl get pods -n kafka -l app.kubernetes.io/name=nexus-kafka
# 期望：nexus-kafka-kafka-0/1/2 Running，nexus-kafka-zookeeper-0/1/2 Running
```

### 4.2 验证集群健康

```bash
# 集群状态
kubectl describe kafka nexus-kafka -n kafka | tail -20

# bootstrap Service
kubectl get svc nexus-kafka-kafka-bootstrap -n kafka
# 期望：端口 9092 / 9093 / 9094

# PVC 已绑定
kubectl get pvc -n kafka -l app.kubernetes.io/name=nexus-kafka
# 期望：3 个 100Gi broker PVC + 3 个 10Gi zk PVC，全部 BOUND
```

### 4.3 验证机架感知

```bash
# 确认 broker 分布在不同 zone
kubectl get pods -n kafka -l app.kubernetes.io/name=nexus-kafka -o wide \
  --show-labels | grep rack
```

## 第5章 Topic 管理

### 5.1 创建 Topic

```bash
# 应用所有 KafkaTopic CRD
kubectl apply -f deploy/kafka/kafka-topics.yaml -n kafka

# 验证 topic 已创建
kubectl get kafkatopic -n kafka
# 期望：payment-events / bridge-events / webhook-events / payment-events-dlq / bridge-events-dlq

# 查看 topic 详情
kubectl describe kafkatopic payment-events -n kafka
```

### 5.2 Topic 管理原则

- **禁止自动创建**：集群已设 `auto.create.topics.enable=false`，所有 topic 必须通过 KafkaTopic CRD 创建
- **禁止自动删除**：`auto.delete.topics.enable=false`，删除 topic 需先删除 KafkaTopic CRD（`kubectl delete kafkatopic <name>`），再由 Topic Operator 清理
- **分区扩容**：修改 KafkaTopic CRD 的 `partitions` 字段（只能增加，不能减少），apply 后 Topic Operator 自动执行
- **配置变更**：修改 `config` 字段后 apply，Topic Operator 自动触发 `ALTER TOPIC`
- **副本因子**：生产 topic RF=3，不可降级（会低于 min.insync.replicas）

### 5.3 Topic 清单

| Topic | 分区 | RF | 保留 | 用途 | 产出方 | 消费方 |
| --- | --- | --- | --- | --- | --- | --- |
| payment-events | 12 | 3 | 7d | 支付事件 | nexus-gateway | gateway / analytics / webhook 消费者 |
| bridge-events | 6 | 3 | 7d | 桥事件 | nexus-bridge | bridge 消费者 |
| webhook-events | 4 | 3 | 3d | Webhook 事件 | nexus-gateway | webhook 消费者 |
| payment-events-dlq | 4 | 3 | 7d | 支付死信 | 消费失败转入 | 人工/定时重放 |
| bridge-events-dlq | 2 | 3 | 7d | 桥死信 | 消费失败转入 | 人工/定时重放 |

### 5.4 通过 kafka-cli 验证 topic

```bash
# 进入 broker Pod 执行 kafka-topics.sh
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

# 查看 topic 详情
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --topic payment-events
```

## 第6章 消费者组管理

### 6.1 消费者组清单

| 消费者组 | 服务 | 订阅 topic | 并行度 | offset 策略 | DLQ |
| --- | --- | --- | --- | --- | --- |
| nexus-gateway-payment-consumer | nexus-gateway | payment-events | 12 | earliest | payment-events-dlq |
| nexus-analytics-payment-consumer | nexus-analytics | payment-events | 6 | latest | payment-events-dlq |
| nexus-gateway-webhook-consumer | nexus-gateway | webhook-events | 4 | earliest | payment-events-dlq |
| nexus-bridge-event-consumer | nexus-bridge | bridge-events | 6 | earliest | bridge-events-dlq |

### 6.2 查看消费者组状态

```bash
# 列出所有消费者组
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 查看消费者组 lag
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group nexus-gateway-payment-consumer
# 输出：CURRENT-OFFSET / LOG-END-OFFSET / LAG
```

### 6.3 重置 offset（谨慎操作）

```bash
# 重置到最早（仅当消费者组无活跃成员时）
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group nexus-gateway-payment-consumer \
  --topic payment-events \
  --reset-to-earliest --execute

# 重置到指定时间点
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group nexus-gateway-payment-consumer \
  --topic payment-events \
  --reset-to-datetime 2026-01-01T00:00:00.000 --execute
```

> **警告**：重置 offset 前必须停止该消费者组所有实例，否则操作被拒绝。重置后需手动验证消费位点正确再启动消费者。

### 6.4 死信队列重放

```bash
# 将 DLQ 消息重新生产回主 topic（需先消费 DLQ 再 produce 回主 topic）
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic payment-events-dlq --from-beginning --max-messages 10
# 人工审查后，用 console-producer 投回 payment-events
```

## 第7章 监控告警

### 7.1 部署监控配置

```bash
# 应用 ServiceMonitor + PrometheusRule + Grafana Dashboard
kubectl apply -f deploy/kafka/kafka-monitoring.yaml -n kafka

# 验证 ServiceMonitor 被 Prometheus 识别
kubectl get servicemonitor -n kafka
# 期望：nexus-kafka-broker-monitor / nexus-kafka-exporter-monitor / nexus-kafka-zookeeper-monitor

# 验证 PrometheusRule
kubectl get prometheusrule -n kafka
# 期望：nexus-kafka-alerting-rules
```

### 7.2 告警规则清单

| 告警 | 条件 | 持续 | 级别 | 说明 |
| --- | --- | --- | --- | --- |
| KafkaConsumerGroupLagHigh | consumer lag > 10000 | 5m | critical | 消息积压，核心告警 |
| KafkaConsumerGroupLagIncreasing | lag 持续增长 | 10m | warning | 消费速率低于生产速率 |
| KafkaUnderReplicatedPartitions | under-replicated > 0 | 5m | critical | 副本未同步，数据风险 |
| KafkaUnderMinIsrPartitions | under-min-isr > 0 | 5m | critical | 写入阻塞风险 |
| KafkaNoActiveController | active controller = 0 | 2m | critical | 集群无法选举 |
| KafkaMultipleActiveControllers | active controller > 1 | 2m | critical | 脑裂 |
| KafkaBrokerDown | broker 抓取失败 | 1m | critical | broker 不可达 |
| KafkaZookeeperDown | zk 抓取失败 | 1m | critical | zk 不可达 |

### 7.3 Grafana Dashboard

Dashboard `NexusChain Kafka 概览`（uid: `nexus-kafka-overview`）包含：

- 活跃 Broker 数（stat）
- Under-Replicated 分区数（stat）
- Active Controller 数（stat）
- 消费者组 Lag 趋势（timeseries，按 group）
- 消息吞吐 in/out（timeseries）
- 消费者组 Lag 明细表（table）

> Grafana 需配置 dashboard sidecar 跨命名空间发现（`dashboardNamespaceSelector: {}`），或将 ConfigMap 复制到 monitoring 命名空间。

### 7.4 关键指标

| 指标 | 来源 | 含义 |
| --- | --- | --- |
| `kafka_consumergroup_lag` | Kafka Exporter | 消费者组 lag（积压量） |
| `kafka_server_replicamanager_underreplicatedpartitions` | JMX | 未同步副本分区数 |
| `kafka_controller_activecontrollercount` | JMX | Active Controller 数 |
| `kafka_server_brokertopicmetrics_messagesinpersec` | JMX | 消息入速率 |
| `kafka_server_brokertopicmetrics_bytesoutpersec` | JMX | 字节出速率 |

## 第8章 与 Spring Boot 集成

### 8.1 集成原则

- **不修改 application.yml**：Kafka 配置通过 Helm values 注入环境变量
- **不修改 Helm chart 模板**：仅通过 values 覆盖注入 env
- **配置参考**：见 `kafka-client-config.yaml` 中的 `application-snippet.yml` 与 `helm-values-injection-example.yaml`

### 8.2 Helm values 注入

在各环境的 `deploy/helm/values-<env>.yaml` 中添加 Kafka 配置（参考 `kafka-client-config.yaml`）：

```yaml
global:
  kafka:
    bootstrapServers: "nexus-kafka-kafka-bootstrap.kafka:9092"
    enabled: true

nexus-gateway:
  env:
    SPRING_KAFKA_BOOTSTRAP_SERVERS: "nexus-kafka-kafka-bootstrap.kafka:9092"
    KAFKA_CONSUMER_GROUP_ID: "nexus-gateway-webhook-consumer"
    SPRING_KAFKA_PRODUCER_ACKS: "all"
    SPRING_KAFKA_PRODUCER_RETRIES: "3"
    SPRING_KAFKA_CONSUMER_ENABLE_AUTO_COMMIT: "false"
    # ... 完整配置见 kafka-client-config.yaml
```

### 8.3 关键客户端配置

| 配置 | 值 | 说明 |
| --- | --- | --- |
| `acks` | all | 写入需所有同步副本确认 |
| `retries` | 3 | 生产者重试次数 |
| `batch-size` | 32768 | 批次大小 32KB |
| `linger.ms` | 10 | 批次等待 10ms |
| `enable.idempotence` | true | 幂等生产者，避免重复 |
| `compression.type` | lz4 | LZ4 压缩 |
| `enable-auto-commit` | false | 手动提交 offset |
| `auto-offset-reset` | earliest | 首次从最早开始 |
| `max.poll.records` | 100 | 单次拉取 100 条 |
| `max.poll.interval.ms` | 300000 | 处理超时 5 分钟 |
| `partition.assignment.strategy` | StickyAssignor | 减少 rebalance 抖动 |

### 8.4 TLS 监听器集成（敏感服务）

签名服务、钱包服务可通过 TLS 监听器（9093）+ KafkaUser mTLS 认证接入：

```bash
# 1. 创建 KafkaUser（mTLS 证书由 Strimzi 自动签发）
cat <<EOF | kubectl apply -f -
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaUser
metadata:
  name: nexus-signing-service
  namespace: kafka
spec:
  authentication:
    type: tls
  authorization:
    type: simple
    acls:
      - resource:
          type: topic
          name: payment-events
        operations: [Read, Describe]
      - resource:
          type: group
          name: nexus-signing-event-consumer
        operations: [Read, Describe]
EOF

# 2. 挂载 Secret 到 Pod（Strimzi 自动生成 keystore/truststore）
#    在 Helm deployment 中挂载 nexus-signing-service Secret
```

TLS 客户端配置见 `kafka-client-config.yaml` 中 `tls-client-config-example.yaml`。

## 第9章 常见问题排查

### 9.1 集群不就绪（Ready=False）

```bash
# 查看 Kafka CRD 事件
kubectl describe kafka nexus-kafka -n kafka | grep -A 20 "Events:"

# 常见原因：
# 1. StorageClass 不存在 → 创建 fast-ssd 或修改 kafka-cluster.yaml 中 class
# 2. 节点数不足 → broker 反亲和要求 >= 3 节点
# 3. 节点无 zone 标签 → 机架感知失败，添加 topology.kubernetes.io/zone 标签
```

### 9.2 消费者组 lag 持续增长

```bash
# 1. 查看消费者组 lag 明细
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group <group-id>

# 2. 检查消费者实例数与分区数
#    消费者数 > 分区数时多余消费者空闲，不会增加吞吐
#    消费者数 < 分区数时部分消费者承担多分区

# 3. 检查消费者处理速率（Grafana 或 actuator metrics）
#    若处理速率 < 生产速率，需扩容消费者实例或优化处理逻辑

# 4. 检查下游依赖（DB / Redis / RPC）是否瓶颈
```

### 9.3 Under-replicated partitions

```bash
# 1. 查看 under-replicated 分区
kubectl exec -it nexus-kafka-kafka-0 -n kafka -- \
  bin/kafka-topics.sh --bootstrap-server localhost:9092 \
  --describe --under-replicated-partitions

# 2. 检查 broker Pod 状态
kubectl get pods -n kafka -l app.kubernetes.io/name=nexus-kafka

# 3. 检查 broker 日志
kubectl logs nexus-kafka-kafka-1 -n kafka --tail=100

# 常见原因：broker 宕机、磁盘满、网络分区
# RF=3 + min.insync=2 可容忍 1 broker 故障，2 broker 故障将阻塞 acks=all 写入
```

### 9.4 Topic 创建失败

```bash
# 查看 KafkaTopic 状态
kubectl describe kafkatopic payment-events -n kafka

# 常见原因：
# 1. Kafka 集群未就绪 → 等待集群 Ready=True
# 2. Topic Operator 未运行 → 检查 entity-operator Pod
# 3. RF > broker 数 → 确保 replicas <= kafka.spec.kafka.replicas
```

### 9.5 外部访问 NodePort 不通

```bash
# 1. 确认 NodePort Service
kubectl get svc nexus-kafka-kafka-external-listeners -n kafka

# 2. 确认节点防火墙放行 32000 端口
# 3. 确认 preferredNodePortAddressType 配置正确
# 4. 生产环境建议关闭 external 监听器或加 NetworkPolicy 白名单
```

### 9.6 Strimzi Operator 升级

```bash
# 1. 升级 Operator 镜像
#    修改 strimzi-operator.yaml 中 image: quay.io/strimzi/operator:<new-version>
kubectl apply -f deploy/kafka/strimzi-operator.yaml

# 2. 升级 CRD（必须与 Operator 版本匹配）
kubectl apply -f https://strimzi.io/install/<new-version>

# 3. 升级 Kafka 集群版本
#    修改 kafka-cluster.yaml 中 spec.kafka.version
kubectl apply -f deploy/kafka/kafka-cluster.yaml -n kafka
# Operator 将执行滚动升级（逐 broker 重启）

# 注意：Kafka 跨版本升级需遵循兼容矩阵，见 Strimzi 文档
```

## 第10章 部署顺序速查

```bash
# 1. 安装 CRD（首次）
kubectl apply -f https://strimzi.io/install/latest

# 2. 部署 Operator
kubectl apply -f deploy/kafka/strimzi-operator.yaml

# 3. 创建 Kafka 集群
kubectl apply -f deploy/kafka/kafka-cluster.yaml -n kafka

# 4. 等待集群就绪
kubectl wait kafka/nexus-kafka -n kafka --for=condition=Ready --timeout=600s

# 5. 创建 Topic
kubectl apply -f deploy/kafka/kafka-topics.yaml -n kafka

# 6. 应用消费者组配置（文档）
kubectl apply -f deploy/kafka/kafka-consumer-groups.yaml -n kafka

# 7. 部署监控
kubectl apply -f deploy/kafka/kafka-monitoring.yaml -n kafka

# 8. 通过 Helm values 注入客户端配置（在各环境 values-<env>.yaml 中配置）
#    参考 kafka-client-config.yaml
```

## 第11章 安全注意事项

1. **明文监听器**：plain 9092 为集群内明文，依赖 K8s NetworkPolicy 隔离。生产环境敏感服务应使用 tls 9093 + KafkaUser mTLS
2. **NodePort 暴露**：external 9094 NodePort 32000 仅供运维调试，生产环境应关闭或加白名单
3. **ACL 授权**：集群已启用 simple ACL，建议为每个服务创建 KafkaUser 并配置最小权限 ACL
4. **PVC 保留**：`deleteClaim: false` 确保 broker 重启后数据保留。删除集群前需手动清理 PVC
5. **密钥管理**：KafkaUser mTLS 证书由 Strimzi 自动签发，轮换由 Operator 自动处理。TLS Secret 需通过 Helm values 注入，不硬编码
6. **Pod 安全**：所有 Pod 设 `runAsNonRoot: true` + seccomp RuntimeDefault，Namespace 启用 baseline Pod Security Standard

## 第12章 未验证声明

本目录清单**尚未在真实 Kubernetes 集群验证**。上线前请：

1. 在测试集群逐文件 `kubectl apply` 并检查 Pod 就绪状态
2. 验证 StorageClass `fast-ssd` 存在且可绑定 PVC
3. 验证节点 `topology.kubernetes.io/zone` 标签已设置（机架感知）
4. 运行生产/消费压测验证吞吐与延迟
5. 验证 Prometheus 跨命名空间发现 ServiceMonitor（`serviceMonitorNamespaceSelector`）
6. 验证 Grafana Dashboard 跨命名空间加载（或复制 ConfigMap 到 monitoring ns）
7. 验证 Helm values 注入后 Spring Boot 客户端能正常连接