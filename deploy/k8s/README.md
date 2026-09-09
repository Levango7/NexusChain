# NexusChain Kubernetes 部署说明

本目录包含 NexusChain 各组件的 Kubernetes 清单（namespace、gateway、core、基础设施、监控、备份、NetworkPolicy）。

## 部署顺序

```bash
kubectl apply -f 00-namespace-config.yml   # namespace + ConfigMap + Secret
kubectl apply -f 30-infrastructure.yml     # postgres / redis / nacos 等基础设施
kubectl apply -f 20-core-statefulset.yml   # 链节点
kubectl apply -f 10-gateway.yml            # 支付网关
kubectl apply -f 40-monitoring.yml         # 监控
kubectl apply -f 50-backup.yml             # 备份 CronJob
kubectl apply -f 60-networkpolicy.yml      # 网络策略（default-deny）
```

或一键执行 `./deploy.sh`。

## Secret 管理（生产必读）

`00-namespace-config.yml` 中的 `nexus-secrets` 仅为**仓库占位模板**，其中的
`CHANGE_ME_*` / `BASE64_ENCODED_*` 值**禁止用于任何真实环境**。生产部署前必须
通过密钥注入方案覆盖，而非把明文留在 git：

- **推荐方案**（任选其一）：
  - [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets)：把密文提交进 git，
    集群内 controller 解密为真实 Secret。
  - [External Secrets Operator](https://external-secrets.io/)：从 Vault / AWS Secrets
    Manager / GCP Secret Manager 等外部密钥库同步。
  - [SOPS + age/KMS](https://github.com/getsops/sops)：清单加密后入库。
- **需要注入的键**（见 `00-namespace-config.yml`）：
  | 键 | 说明 | 要求 |
  |----|------|------|
  | `NEX_DB_USERNAME` | 数据库用户 | 生产替换默认值 |
  | `NEX_DB_PASSWORD` | 数据库密码 | 强随机口令 |
  | `NEX_MASTER_KEY` | 主加密密钥 | **必须为 32 字节 BASE64（AES-256）** |
  | `NEX_WEBHOOK_SECRET` | Webhook 签名密钥 | 强随机值 |
- **禁止**：将真实密钥明文提交到本目录任何 `.yml`；轮换密钥时同步更新注入源。

## 安全基线

- 所有业务 Pod 已设置 `automountServiceAccountToken: false`，不挂载 ServiceAccount
  token，Secret 仅以环境变量注入。
- `60-networkpolicy.yml` 在 `nexus` 命名空间启用 default-deny，仅放行声明的入站流量。

## 部署演练经验（2026-09-10，kind v1.30）

在本地 kind 4 节点集群完成 mpc-engine + kube-prometheus-stack 真实部署演练，
以下为踩坑记录——生产/其他环境部署时直接规避：

### 1. PrometheusRule 加载前置：release 标签

kube-prometheus-stack 的 Prometheus CR 默认 `ruleSelector: {matchLabels: {release: <helm release>}}`。
`50-prometheus-rules.yaml` 的 metadata.labels **必须补 `release: <release>`**，
否则规则 apply 成功但 Prometheus 不加载（`/api/v1/rules` 为空）。示例：

```bash
kubectl label prometheusrule nexuschain-alerts -n nexus release=monitoring
```

### 2. kube-prometheus-stack admission webhook secret 需自建

即使 `prometheusOperator.admissionWebhooks.enabled: false`，operator Deployment 仍
挂载 `tls-secret` 卷（路径 /cert）——不存在的 Secret 会让 operator 卡
`ContainerCreating`（FailedMount）。需预建同名 Secret（键 `cert`/`key`/`tls-ca.crt`，
自签即可，operator 不用 webhook 时内容无实际用途）：

```bash
kubectl create secret tls monitoring-kube-prometheus-admission -n monitoring \
  --cert=<自签 cert.pem> --key=<自签 key.pem>
# 若键名不符（operator 读 /cert/cert 与 /etc/tls/private/tls-ca.crt），
# 用带 cert/key/tls-ca.crt 三键的 Secret 覆盖
```

### 3. 节点内拉取镜像（国内网络环境）

kind 节点内 containerd 直连 docker.io/registry.k8s.io 会被墙。两条可行路径：

- **推荐**：节点内 `ctr pull --hosts-dir`（配镜像加速，对任意 tag 生效）：
  ```bash
  # 每节点：/etc/containerd/certs.d/docker.io/hosts.toml
  #   server = "https://registry-1.docker.io"
  #   [host."https://docker.1ms.run"] capabilities = ["pull", "resolve"]
  docker exec <node> ctr -n k8s.io images pull \
    --platform linux/amd64 --hosts-dir /etc/containerd/certs.d \
    docker.io/library/<img>:<tag>
  ```
- registry.k8s.io 镜像用 `m.daocloud.io/registry.k8s.io/<image>:<tag>` 前缀
  （`docker.m.daocloud.io/registry.k8s.io/...` 嵌套路径返回 403，不可用）。

**已知陷阱**：Windows 上 `docker save` + `ctr import` 或 `kind load docker-image`
对多平台 manifest（busybox/grafana 等 manifest list）有 digest 缺失 bug
（`content digest ... not found`）——不要用 save/import 传大镜像，直接用上面
的 `ctr pull --hosts-dir`。

### 4. 告警规则真值：absent() 勿用于"可选目标"

`absent(up{job="..."}) == 1` 在目标**不存在**时恒为真（序列缺失 = absent 返回 1），
会永久假告警。对可选抓取目标（如 seata-tc）只用 `up{job="..."} == 0`——无目标时
表达式无数据、不触发。SeataTcDown 因此修复（57f1cfc）。

### 5. SealedSecret 闭环（2026-09-10 演练验证）

`30-sealed-secret-mpc-engine.yaml` 是母本占位（`PLACEHOLDER-DO-NOT-APPLY`）。
真实闭环（演练验证通过）：
1. 部署 sealed-secrets controller（manifest 见 sealed-secrets 官方 release）；
2. 本地 `kubeseal`（版本与 controller 一致）加密母本 → SealedSecret CR；
3. apply 后 controller 解密出普通 Secret（ownerReference=SealedSecret）；
4. chart 设 `secrets.sealedSecretName` 后 Pod 经 envFrom 引用该 Secret。

**注意**：
- 若同名普通 Secret 已存在（如 helm 明文模式创建过），controller 拒绝更新
  （"already exists and is not managed by SealedSecret"）——删旧 Secret 让
  controller 重建（带 ownerReference）。
- 删除 SealedSecret 会**级联删除**解密出的 Secret；mpc-engine 对此是
  fail-warn 而非 fail-closed——init-config 用全零密钥兜底（日志 WARNING），
  引擎能启动但无法解密既有会话（会话数据视为失效）。
- 演练的 SealedSecret 密文**不可提交**（绑定演练 controller 公钥）；生产用
  生产 controller 重新 seal 后替换 `30-sealed-secret-mpc-engine.yaml`。

## 未验证声明

已实测验证（2026-09-10 kind 集群）：
- `mpc-engine` Helm chart（StatefulSet 3 副本、PVC/持久化、mTLS、SealedSecret 注入）
- `50-prometheus-rules.yaml`（11 条规则真实加载并评估）
- `deploy/grafana/dashboards/`（8 个 dashboard 经 sidecar 装载）

仍**未在真实集群验证**（Java 服务栈）：
1. `10-gateway.yml` / `20-core-statefulset.yml` 等 Java 服务清单——需 Nacos/PG/Redis
   等基础设施，kind 演练仅验证了 nexus-core + PostgreSQL 最小闭环；
2. `60-networkpolicy.yml` default-deny 对业务 Pod 的放行规则；
3. `50-backup.yml` 备份 CronJob 的实际落盘产物。
