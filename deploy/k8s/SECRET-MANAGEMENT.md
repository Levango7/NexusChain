# Kubernetes Secret 管理指南（v2.1.0）

本目录的 `00-namespace-config.yml` 中定义了一个 `nexus-secrets` Secret，
其中 `stringData` 字段的值（`CHANGE_ME_IN_PRODUCTION`、`BASE64_ENCODED_32_BYTE_AES_KEY_HERE` 等）
**仅为仓库内的占位模板**，不代表可直接部署的配置。本文档说明生产环境的正确做法。

## 核心原则

1. **禁止把真实凭据明文提交进 git**。占位符可以，真实值不行。
2. Secret 通过**加密注入**方式在部署时生成，而非在仓库中维护明文。
3. 所有 Secret 值定期轮换，轮换后需重启引用它们的 Pod。

## 生产注入方案（三选一）

### 方案 A：Sealed Secrets（推荐，GitOps 友好）

1. 集群安装 [bitnami/sealed-secrets](https://github.com/bitnami-labs/sealed-secrets) 控制器。
2. 本地用 `kubeseal` 将真实 Secret 加密为 `SealedSecret`：

   ```bash
   kubeseal --controller-name sealed-secrets-controller \
            --controller-namespace kube-system \
            --format yaml < local-real-secret.yaml > 00-sealed-secrets.yml
   ```
3. 将生成的 `00-sealed-secrets.yml` 提交进 git（密文可安全入库）。
4. 集群内控制器自动解密还原为真实 Secret。**删除** `00-namespace-config.yml` 中的明文 `stringData` 段。

### 方案 B：External Secrets（对接云厂商 KMS/Vault）

适合已有 Vault / AWS Secrets Manager / GCP Secret Manager / Azure Key Vault 的环境：

1. 集群安装 [external-secrets](https://external-secrets.io)。
2. 编写 `SecretStore`（指向你的密钥后端）与 `ExternalSecret`（声明要拉取的键）。
3. external-secrets 控制器在部署时从密钥后端拉取并创建 Secret。

### 方案 C：kubectl create secret（最简单，适合小规模）

```bash
kubectl -n nexus create secret generic nexus-secrets \
  --from-literal=NEX_DB_USERNAME=nexus \
  --from-literal=NEX_DB_PASSWORD='<真实密码>' \
  --from-literal=NEX_MASTER_KEY='<base64 32 字节 AES key>' \
  --from-literal=NEX_WEBHOOK_SECRET='<真实 webhook secret>' \
  --dry-run=client -o yaml > /tmp/nexus-secrets.yml
# 不要提交该文件；直接 apply
kubectl apply -f /tmp/nexus-secrets.yml && rm /tmp/nexus-secrets.yml
```

## Secret 字段说明

| 键 | 用途 | 要求 |
|----|------|------|
| `NEX_DB_USERNAME` | PostgreSQL 用户名 | 建议不要用 `postgres` 超级用户 |
| `NEX_DB_PASSWORD` | PostgreSQL 密码 | 强随机密码，定期轮换 |
| `NEX_MASTER_KEY` | 主密钥（AES-256） | **必须 32 字节 BASE64**，用于钱包密钥加密 |
| `NEX_WEBHOOK_SECRET` | Webhook 回调签名密钥 | 网关与商户共享，强随机字符串 |

## 检查清单

- [ ] 生产集群中 `nexus-secrets` 由上述方案之一注入，非仓库明文
- [ ] `NEX_MASTER_KEY` 为合法 32 字节 BASE64
- [ ] 轮换 Secret 后已重启相关 Pod
- [ ] git 历史中无真实凭据（若有，用 `git filter-repo` 清理）
