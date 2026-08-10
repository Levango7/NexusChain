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

## 未验证声明

本目录清单**尚未在真实 Kubernetes 集群验证**。上线前请：
1. 在测试集群逐文件 `kubectl apply` 并检查 Pod 就绪状态；
2. 用真实密钥替换占位值后验证数据库/Redis 连接；
3. 运行 `50-backup.yml` 的备份 CronJob 并确认备份产物落盘。
