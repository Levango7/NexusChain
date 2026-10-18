# Kubernetes Secret 管理指南

<!-- 说明（2026-10-18 批次④定稿）：本指南为跨版本通用文档，标题不绑定发布版本号——当前发布版本以根 README.md「版本口径」头注为准。历史形态曾是「标题含版本（vX.Y.Z）+ scripts/bump-version.sh 第 6 步随归版 sed 同步」；为终结该维护面（每版必改、sed 目标易漂移），自本次起去版本化，bump-version.sh 对应步骤已移除。 -->

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
| `NEX_DB_USERNAME` | MySQL 用户名（2026-10-12 起数据库族为 MySQL 8） | 生产建议按库分账号，勿用 `root` |
| `NEX_DB_PASSWORD` | MySQL 密码（同时作为内置 MySQL StatefulSet 的 root 口令） | 强随机密码，定期轮换 |
| `NEX_MASTER_KEY` | 主密钥（AES-256） | **必须 32 字节 BASE64**，用于钱包密钥加密 |
| `NEX_WEBHOOK_SECRET` | Webhook 回调签名密钥 | 网关与商户共享，强随机字符串 |
| `NACOS_AUTH_TOKEN` | Nacos 3.x 服务端 token 签名密钥（Base64） | **Base64 字符串**；`nacos-ha-statefulset` 启动脚本强制校验非空 |
| `NACOS_AUTH_IDENTITY_KEY` / `NACOS_AUTH_IDENTITY_VALUE` | Nacos 服务端 identity（服务器间鉴权） | 强随机，三键缺一启动脚本 exit 255 |
| `NEX_NACOS_USERNAME` / `NEX_NACOS_PASSWORD` | **Nacos 客户端**凭据（应用注册/配置拉取） | L3 预演缺陷 #23：`nacos.core.auth.enabled=true` 后必填，否则应用 401 `User not found` 起不来。服务端用户初始化：空用户表时 `POST /nacos/v3/auth/user/admin`（控制台 API，返回自动生成口令）后填入 |
| `SPRING_DATA_REDIS_PASSWORD` | Redis requirepass（业务 Pod 经 envFrom 自动获得） | 强随机；须与 `30-infrastructure.yml` 的 Redis 一致 |
| `NEX_API_KEYS` | API Gateway 商户 API Key 白名单 | 逗号分隔；缺失则 api-gateway 启动即 `PlaceholderResolutionException`（缺陷 #18） |

## 检查清单

- [ ] 生产集群中 `nexus-secrets` 由上述方案之一注入，非仓库明文
- [ ] `NEX_MASTER_KEY` 为合法 32 字节 BASE64
- [ ] 轮换 Secret 后已重启相关 Pod
- [ ] git 历史中无真实凭据（若有，用 `git filter-repo` 清理）
