# sealed-secrets 操作流程

## 目的

生产环境 MPC 引擎的 `MPC_STORAGE_KEY`（hex 32 字节 AES-256-GCM 密钥，
**不能入库**）通过 [bitnami-labs/sealed-secrets](https://github.com/bitnami-labs/sealed-secrets)
**公钥加密**后入仓。私钥只在集群内 controller 持有——任何能 pull
Secret 的人看到的都是密文。

## 适用对象

- **生产**（prod）：必须使用 sealed-secrets
- **冒烟**（CI / kind）：仍可使用明文 `values.storageKey`（冒烟值是占位
  `4242...42`）——本地 build 与测试不依赖密钥真实性
- **dev**：**强烈建议**使用 sealed-secrets（开发集群密钥复用生产 controller）

## 一次性集群初始化（管理员）

### 1. 安装 controller（一次性）

```bash
# Helm 方式（推荐，便于后续升级）
helm repo add sealed-secrets https://bitnami-labs.github.io/sealed-secrets
helm repo update
helm install sealed-secrets sealed-secrets/sealed-secrets \
  --namespace kube-system \
  --set controllerResources.limits.cpu=200m,memory=128Mi
```

或 `kubectl apply -f https://github.com/bitnami-labs/sealed-secrets/releases/download/v0.27.0/controller.yaml`

### 2. 备份私钥（**关键**——丢了就解密不了任何 SealedSecret）

```bash
# 取出 cluster-wide 私钥并备份到离线介质（1Password / Vault）
kubectl get secret -n kube-system \
  -l sealedsecrets.bitnami.com/sealed-secrets-key=active \
  -o jsonpath='{.items[*].metadata.name}'
# 通常有 2 个 key（active + 私钥轮换期）
kubectl get secret -n kube-system sealed-secrets-keyXXXXX -o yaml > sealed-secrets-key-backup.yaml
# 加密存储到 1Password / Vault，**不要入仓**
```

**为什么必须**：sealed-secrets 的设计是"公钥公开、私钥只在集群"——
私钥丢失 = 所有 SealedSecret 永久无法解密 = 必须从 .bak 母本重加密
或重新生成密钥重新加密所有 secret。

## 2. 新增 / 轮换 MPC_STORAGE_KEY

### 步骤 A：生成新密钥（**离线 + 安全介质**）

```bash
# 64 字符 hex 32 字节
openssl rand -hex 32
# 记录到 1Password / Vault 作为"母本"（offline backup）
```

### 步骤 B：本地加密为 SealedSecret

```bash
# 安装客户端工具
brew install kubeseal   # macOS
# 或从 https://github.com/bitnami-labs/sealed-secrets/releases 拉 release

# 在本地创建明文 secret 文件（不入仓！）
cat > /tmp/mpc-storage-key.yaml <<EOF
apiVersion: v1
kind: Secret
metadata:
  name: mpc-engine-secret
  namespace: nexus
type: Opaque
stringData:
  storage-key: "<your 64 hex>"
  storage-key-version: "1"
EOF

# 用 cluster 公钥加密（-o yaml 输出 SealedSecret）
kubeseal \
  --controller-name=sealed-secrets \
  --controller-namespace=kube-system \
  -o yaml < /tmp/mpc-storage-key.yaml > deploy/k8s/30-sealed-secret-mpc-engine.yaml

# 立刻删本地明文文件
shred -u /tmp/mpc-storage-key.yaml
```

### 步骤 C：commit + apply

```bash
git add deploy/k8s/30-sealed-secret-mpc-engine.yaml
git commit -m "chore(secret): rotate mpc-engine MPC_STORAGE_KEY to v1"
git push
kubectl apply -f deploy/k8s/30-sealed-secret-mpc-engine.yaml
```

controller 会在 `nexus` namespace 创建 `mpc-engine-secret`（明文）——
**只能在该集群内读到**。

### 步骤 D：chart 切换 + 重启引擎

```bash
# values-prod.yaml 或 --set:
secrets:
  sealedSecretName: mpc-engine-secret  # 新字段（PLAN-002 Step A）

# helm upgrade
helm upgrade mpc deploy/helm/charts/mpc-engine \
  -f deploy/helm/values-prod.yaml \
  -n nexus

# 验证 Pod 已读新 key（重启后 logs）
kubectl logs -n nexus mpc-engine-0 | grep "storage_key applied"
# 应输出：version=1 source=plain
```

## 3. 应急解封（私钥丢失 / cluster 灾难恢复）

```bash
# 1. 在新集群安装 controller 后，从 1Password/Vault 取回私钥
kubectl apply -f sealed-secrets-key-backup.yaml

# 2. 所有 SealedSecret 需重新加密（如私钥轮换过）—— 旧 .bak 母本不存在则
#    必须先解密 .bin（需在原集群私钥下）才能重新加密
```

## 4. 常见错误

| 错误 | 原因 | 解决 |
|---|---|---|
| `controller not found` | controller 未装或装在错的 namespace | 检查 `kubectl get pod -n kube-system -l app.kubernetes.io/name=sealed-secrets` |
| `failed to decrypt secret` | SealedSecret 由旧 key 加密，controller 密钥已轮换 | 用旧 controller 私钥解密或重新加密 |
| Pod 启动报 `MPC_STORAGE_KEY empty`（WARN） | sealed-secret 名字/namespace 错 | `kubectl get secret -n nexus mpc-engine-secret` 确认存在；检查 chart `secrets.sealedSecretName` |
| `Resource "mpc-engine-secret" already exists and is not managed by SealedSecret` | 同名普通 Secret 已存在（如 helm 明文模式安装过）——controller 只托管自己创建的 Secret | `kubectl delete secret mpc-engine-secret -n nexus` 删旧，controller 自动重建（带 ownerReference=SealedSecret） |
| 删 SealedSecret 后 Pod CrashLoop（init-config `FATAL: STORAGE_KEY is empty`） | **fail-closed（2026-09-12 C9 拍板，原 fail-warn 全零兜底已移除）**：密钥缺失/全零/长度非 64 hex 时 init-config 拒绝启动；引擎侧 config.rs 同款校验双保险 | 预期防护行为——立即恢复：重新 apply 备份的 SealedSecret（或 helm 传 storageKey 冒烟值）；Pod 自动重调度恢复。MpcEngineCrashLooping 告警会立刻触发（5min） |

## 5. 与本仓库的衔接

- `deploy/k8s/30-sealed-secret-mpc-engine.yaml`：**仅占位**——真生产由管理员用
  `kubeseal` 加密后覆盖此文件
- `deploy/helm/charts/mpc-engine/templates/secret.yaml`：支持两种模式
  - `values.storageKey` 非空（明文，**冒烟专用**）
  - `values.secrets.sealedSecretName` 非空（**生产**——chart 引用 SealedSecret，
    自身不写 stringData）
- CI（kind 冒烟）：**继续走明文**——sealed-secret 不在 kind 内可用（需 controller）
