# Kustomize 多环境部署

## 设计

3 套 overlay（dev/staging/prod）作为未来 GitOps（ArgoCD/Flux）的入口。
**真相源仍是 `deploy/helm/values-{dev,staging,prod}.yaml`**——Kustomize 仅
在环境层差异（namespace + env 标签）上做**轻量 patch**。

## 实际差异（每套 overlay 唯一做的事）

| Overlay | Namespace | env label | 说明 |
|---|---|---|---|
| dev | nexus-dev | `dev` | 重型差异（replicas/resources/image tag/HPA）由 `values-dev.yaml` 控制 |
| staging | nexus-staging | `staging` | 同上 |
| prod | nexus | `prod` | 同上 + `secrets.sealedSecretName` 引用 SealedSecret |

**为什么不重复 patch image tag**：`values-{env}.yaml` 已设 image tag
（`latest` / `staging` / `2.1.0`）。Kustomize 重复 patch 容易出现"values
与 overlay 错位"——**单源真相**。如果未来 GitOps 工具需要 image patch，
直接在 ArgoCD 端用 `kustomize edit set image` 即可。

## 用法

### 1. 准备工具

```bash
# helm 3.x
brew install helm
# kubectl（带 kustomize 内置）
brew install kubectl
# 集群内 sealed-secrets controller（仅 prod 需要）
# 见 docs/runbooks/sealed-secrets.md
```

### 2. 渲染 + apply

```bash
# dev（明文冒烟值，可在本机 kind 集群跑）
bash deploy/kustomize/render.sh dev | kubectl apply -f -

# staging
bash deploy/kustomize/render.sh staging | kubectl apply -f -

# prod（**先确认 sealedSecretName 已注入**）
bash deploy/kustomize/render.sh prod > /tmp/prod.yaml
kubectl apply -f deploy/k8s/30-sealed-secret-mpc-engine.yaml  # 先 apply SealedSecret
kubectl apply -f /tmp/prod.yaml
```

### 3. 与 `helm install -f` 路径的等价

```bash
# 等价：以下两条命令结果一致（Kustomize 仅修 image tag，helm values 已设）

# 路径 A：纯 helm
helm install nexus-chain deploy/helm/ -f deploy/helm/values-prod.yaml

# 路径 B：helm template + Kustomize
bash deploy/kustomize/render.sh prod | kubectl apply -f -
```

## 何时用哪条路径

- **本地开发 / CI 冒烟**：用 helm 直接 install，**不需要 Kustomize**（K 批 kind
  冒烟 job 继续走 helm install 路径）
- **生产 GitOps 部署**（未来 ArgoCD）：用 Kustomize 路径
- **临时调试**：用 helm 路径最快

## CI 验证（PLAN-002 Step B.4）

CI 跑：
1. `kubectl kustomize deploy/kustomize/overlays/{dev,staging,prod}` —— 确保
   kustomization 合法
2. `kubectl kustomize | kubectl apply --dry-run=client` —— 验证渲染产物可 apply
3. `bash deploy/kustomize/render.sh <env>` + `--dry-run=client` 三环境跑通

## 未来扩展

- **GitOps 集成**：ArgoCD 直接 `kustomize build deploy/kustomize/overlays/prod`
- **kustomize edit set image** 在线升级 image tag（无 helm release 状态）
- **kustomize edit set namespace** 在线切 namespace（多租户场景）
