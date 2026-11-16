# 演练记录：K8s 回滚（kind 自动化冒烟）

- **演练时间**：2026-11-16 16:04:45Z ~ 16:06:06Z（UTC）= 2026-11-17 00:04 ~ 00:06（GMT+8）
- **耗时**：81 秒（job 全程，含拉起 kind 集群）
- **载体**：GitHub Actions run [43896648804](https://github.com/Levango7/NexusChain/actions/runs/43896648804)
  · workflow `K8s Rollback Smoke (kind)` 第 2 次运行 · 被测提交 `f555fa4d`
- **工作流**：`.github/workflows/rollback-smoke.yml`
- **被测 chart**：`.github/ci/rollback-smoke-chart/`（最小无状态 Deployment，**不承载业务**）
- **归档字段依据**：`docs/database-production.md:368`（演练时间 / 耗时 / 发现问题 / 改进措施）

---

## 1. 范围

覆盖 `docs/runbooks/upgrade-rollback.md` 第七节里的两条**集群路径**：

| 路径 | 命令 | 本次是否覆盖 |
|------|------|--------------|
| 7.1 应用层回滚（Helm 侧） | `helm rollback <release> <revision>` | ✅ |
| 7.1 应用层回滚（K8s 侧） | `kubectl rollout undo deploy/<name>` | ✅ |
| 7.3 快照恢复 | — | ❌ 未覆盖 |

**这是「机制演练」，不是「部署演练」**：验证的是 Helm revision 机制与 Deployment
ReplicaSet 回退语义，不是 NexusChain 业务能否起来。边界见第 5 节。

## 2. 步骤与实测值

| 阶段 | 动作 | 实测输出 |
|------|------|----------|
| 1 | `helm install rb ... --set replicaCount=1 --set revisionMarker=v1` | revision 1 建立 |
| 2 | `helm upgrade ... --set replicaCount=2 --set revisionMarker=v2` | `upgrade 后 replicas=2 marker=v2` |
| 3 | `helm rollback rb 1 --wait` | `rollback 后 replicas=1 marker=v1` |
| 4 | `helm upgrade ... --set replicaCount=2 --set revisionMarker=v3` | `upgrade 后 marker=v3` |
| 5 | `kubectl rollout undo deploy/rb-smoke` | `undo 后 marker=v1`（回到步骤 3 结束时的模板） |

关键点：步骤 3 与步骤 5 都是**双条件断言**，不只看副本数：

- `replicas` 证明 Helm 把 revision 1 的**完整 spec** 恢复回来了（副本数在 Pod 模板之外，
  只有 `helm rollback` 会还原它）；
- `marker`（Pod 模板 annotation）证明 **Pod 模板**也回退了（副本数不在模板里，
  只有模板变化才会产生新 ReplicaSet）。

## 3. 发现问题

### 问题 1（表层）：Pod 启动时才拉镜像，`--wait --timeout 180s` 超时

首跑（run 43890723838）报：

```
Install v1 release
Error: INSTALLATION FAILED: client rate limiter Wait returned an error: context deadline exceeded
```

原因：`--wait` 要等 Pod Ready，而 Pod 启动时才从 Docker Hub 拉 `bitnami/nginx`，
冷启动 + 镜像较大 ⇒ 180s 不够。

### 问题 2（严重）：`kubectl rollout undo` 在原设计里是**空操作**

原升级步骤只 `--set replicaCount=2`。但**副本数不在 Pod 模板里**，改它不产生新的
ReplicaSet ⇒ `rollout history` 永远只有 1 条 ⇒ `kubectl rollout undo` 无版可退。

也就是说：**那条「验证 rollout undo」的步骤即使跑绿，也没有验证任何东西。**

用 `helm template` 实测证明（同一 chart，只改 replicaCount）：

```
$ diff render-v1.yaml render-r2.yaml
11c11
<   replicas: 1
---
>   replicas: 2
```

唯一差异是 `spec.replicas`，在 Pod 模板之外 —— `spec.template` 字节完全相同。

> 这个问题的教训比问题 1 大：**一条永远为真的断言，比没有断言更危险**，
> 因为它会以「已验证」的形态进入台账。

## 4. 改进措施

| 针对 | 措施 |
|------|------|
| 问题 1 | 改用仓库内最小 chart + `docker pull nginx:alpine` + `kind load docker-image` 预载镜像，`imagePullPolicy: IfNotPresent` ⇒ Pod 启动**零外网依赖**；超时放宽到 300s |
| 问题 2 | chart 增加 `revisionMarker`，注入 Pod 模板 annotation `smokemarker`；每次 upgrade 同时改副本数与 marker，保证产生新 ReplicaSet；断言改为 `replicas` + `marker` 双条件；`rollout undo` 改为「记下 undo 前 marker，undo 后必须原样回到该值」（不硬编码，避免脆弱断言） |
| 假红隐患 1 | `set -o pipefail` + `cmd \| grep -q`：grep 命中即退出会让左侧命令吃 SIGPIPE，pipefail 把整条管道判失败。两处 `helm history \| grep -q` 改为 `HIST=$(helm history)` + `grep -q <<< "$HIST"` |
| 假红隐患 2 | 删掉 `docker exec <node> crictl images \| grep`：依赖 kind 节点内 crictl 配置，属未验证假设。镜像是否可用由下游 `--wait` 证明（缺镜像会 ImagePullBackOff ⇒ 超时失败），不需要额外探测 |

## 5. 覆盖边界（不要把这当成「回滚已演练」）

本演练**不能**证明以下任何一条：

1. **NexusChain 业务镜像可回滚** —— 被测 chart 是一个 nginx，与业务无关；
2. **数据库可回滚** —— 未涉及。`helm rollback` 只回退 chart 与镜像，
   不回退数据库（`docs/runbooks/upgrade-rollback.md:268`），Flyway 迁移是**前向**的；
3. **链数据可回滚** —— 未涉及；
4. **多副本 / 反亲和 / PDB 下的回滚行为** —— kind 单节点，无跨 AZ；
5. **快照恢复路径** —— 完全未覆盖；
6. **真实集群与真实云厂商 LB / Ingress 的交互** —— 未涉及。

一句话：**它证明的是「Helm 与 K8s 的回滚机制在干净环境里按文档语义工作」，
不证明「这个仓库的业务可以在生产回滚」。**

## 6. 复跑方法

推任意改动到 `.github/workflows/rollback-smoke.yml` 或
`.github/ci/rollback-smoke-chart/**` 即自动触发；也可手动：

```
gh workflow run "K8s Rollback Smoke (kind)" --repo Levango7/NexusChain --ref master
```

该 workflow **不是 required context**，不阻断合并；它的产出是**可复跑的演练证据**，
不是部署收据。
