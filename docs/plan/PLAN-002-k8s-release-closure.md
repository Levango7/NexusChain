# PLAN-002：K 批发布闭环（选项 3 + 5 + 6 合并）

状态：**实施完成（2026-09-07, commit 82eb96a）**——GG20 退役外的 K 批发布闭环
已就位（GG20 仍可作为独立后续批次）

实施时间线：
- Step A sealed-secrets：commit 2c487b1（缺 env 修正 → 74b4dfc）
- Step B Kustomize overlay：commit 4143413（labels array 错 → 修复后同 commit）
- Step C PrometheusRule：commit 6df161a（labels.pairs 错 → 82eb96a）

## 0. 设计选择

合并 3 项工作为单批（同一主线 "可发布的 K8s 部署"）：

| 项 | 范围 | 风险 | 收益 |
|---|---|---|---|
| 3 — Kustomize overlay + dev/staging/prod 多环境 | 2-3 小时 | 低 | 多环境可复现（dev/staging/prod） |
| 6 — sealed-secrets 单独做（只 mpc-engine-secret） | 1-1.5 天 | 低-中（**单点换存储介质**，影响面最窄） | **消除"密钥入仓"致命隐患**——冒烟值 `4242...42` 是裸 hex，真上 prod 必爆 |
| 5 — PrometheusRule 告警 + 基础 dashboard JSON | 2-3 天 | 低 | 故障可发现（否则"上线即裸奔"） |

**组合收益** = 6（安全） + 3（可复现） + 5（可观测）= 上线闭环。**组合风险** = 各自风险按 max 不累计——每项都小，独立验证。

---

## 1. 选项 6 — sealed-secrets 单独做（最高 ROI，单独先做）✅

### 1.1 收益
- **现状风险**：冒烟用 `--set storageKey=4242...` 真值，真上 prod 即"密钥裸传"——这才是真生产环境**最不可接受的隐患**（比 GG20 双栈存在更严重）
- **脱敏化**：sealed-secrets 让密钥经公钥加密入仓，集群内私钥解密；只有 cluster admin 能解密
- **K 批对齐闭环**：①K 批已把 chart 接好 MPC_STORAGE_KEY env；②本选项完成密钥注入闭环；③真生产前唯一必需项

### 1.2 实施（commit 2c487b1 / 74b4dfc）
- **新增** `docs/runbooks/sealed-secrets.md`（controller 安装、母本生成、轮换、应急解封）
- **新增** `deploy/k8s/30-sealed-secret-mpc-engine.yaml`（占位 manifest，**生产由管理员 `kubeseal` 加密后覆盖**）
- **修改** `deploy/helm/charts/mpc-engine/values.yaml`：新增 `secrets.sealedSecretName` 字段
- **修改** `deploy/helm/charts/mpc-engine/templates/secret.yaml`：按 `sealedSecretName` 切换两种模式（生产=空内容+Pod 走 existingSecret；冒烟=明文 stringData）
- **修改** `deploy/helm/charts/mpc-engine/templates/statefulset.yaml`：`MPC_STORAGE_KEY_VERSION` 改 secretKeyRef 引用（与 storage-key 一致走 SealedSecret 模式）

### 1.3 74b4dfc 修正
- K8s 校验错误：`valueFrom` 与 `value` 不能同时设置（删 `value`，由 `valueFrom.optional` 兜底）

### 1.4 风险与缓解
| 风险 | 等级 | 缓解 |
|---|---|---|
| sealed-secrets controller 未部署集群 | 中 | 文档明示：冒烟（kind）继续用明文 `values.storageKey` 路径；生产必须先 apply controller；CI 加 "如果 secrets.sealedSecretName 设置但集群无 controller → fail" 防御 |
| 母本密钥遗失 | 低 | 文档要求"母本"存 1Password/Vault 离线备份；集群管理员 2-3 人各持 1 份 Shamir 分片 |
| key 轮换与持久化衔接 | 低 | NXC1 信封已带 version 字段（⑤批），新增轮换只需递增 version 写新 SealedSecret |

---

## 2. 选项 3 — Kustomize overlay + dev/staging/prod 多环境 ✅

### 2.1 收益
- **现状**：3 套 `values-{env}.yaml` 已写好，但通过 `helm install -f values-*.yaml` 切换——**易错**（忘记带 -f）、**无一致性**（dev/staging/prod 字段名漂移风险）
- **Kustomize overlay**：base + per-env patch 结构化；`kubectl apply -k overlays/prod` 一行命令；**环境差异在 patch 文件中显式可见**
- **复用已有**：3 套 `values-*.yaml` 内容**直接作为 overlay base 输入**——Kustomize 仅做 patch 层

### 2.2 实施（commit 4143413）
- **新增** `deploy/kustomize/{base,overlays/{dev,staging,prod}}/kustomization.yaml`
- **新增** `deploy/kustomize/render.sh`（串联 helm template + kustomize 验证）
- **修改** `.github/workflows/k8s-sync-check.yml`：新增 "Kustomize overlay 验证" step
- **新增** `docs/deploy/kustomize.md`

### 2.3 关键设计取舍
**Kustomize 仅做轻量环境层 patch**（namespace + env 标签），**不重复 patch image tag**——`values-{env}.yaml` 已设（真相源单点）。理由：Kustomize 重复 patch 容易出现"values 与 overlay 错位"。

### 2.4 风险与缓解
| 风险 | 等级 | 缓解 |
|---|---|---|
| Kustomize + Helm 路径分歧 | 中 | CI 验证 kustomization 合法性 + kubectl dry-run |
| Kustomize 学习曲线 | 低 | 仅 base + overlay 两层概念 |

---

## 3. 选项 5 — 生产监控告警（ServiceMonitor 已有，缺 PrometheusRule） ✅

### 3.1 收益
- **现状**：
  - 6 个服务（gateway/bridge/signing/wallet/api-gateway + mpc-engine）都有 `servicemonitor.yaml` ✓
  - `deploy/k8s/40-monitoring.yml` 有 ServiceMonitor + Postgres Exporter ✓
  - **`deploy/k8s/40-prometheus-rules.yaml` 不存在**（grep 验证）——**故障无告警**
- **风险可量化**：生产 MPC 引擎 CrashLoop → 用户提现/支付全停——**必须 5 分钟内被 pager 唤醒**

### 3.2 实施（commit 6df161a / 82eb96a）
- **新增** `deploy/k8s/50-prometheus-rules.yaml`（4 条关键告警）
- **新增** `docs/runbooks/alerts.md`
- **修改** `.github/workflows/k8s-sync-check.yml`：新增 "PrometheusRule 静态校验" step

### 3.3 4 条告警
| 名称 | 级别 | 触发条件 | 含义 |
|---|---|---|---|
| MpcEngineCrashLooping | critical | mpc-engine 容器 CrashLoopBackOff ≥ 5min | 签名服务停摆 |
| MpcEngineNotReady | warning | mpc-engine Pod 至少 1 个非 Ready ≥ 5min | 安全冗余损失 |
| MpcEngineResourceUnbound | critical | PVC 未绑 / Pod 非 Running ≥ 5min | 存储或调度故障 |
| NexusServiceUnhealthy | warning | Java 服务不可用副本 > 50% 持续 5min | 任何 Java 服务降级 |

### 3.4 关键设计取舍
**全部用 `kube_*` 通用 metrics**——**不依赖应用 prom metrics**（应用 metrics 缺失是 2.x TODO）。理由：PromQL 引用不存在的 metric 会"永远不触发"= 静默失效，宁可 mpc-engine CrashLoop 间接信号（5min 延迟）也不要"永远不触发的精确告警"。

Dashboard JSON 暂缓——alert 是"发布后必修"，dashboard 属"运维改善"（避免维护陷阱）。3.x 阶段补应用 metrics 后再加。

### 3.5 6df161a / 82eb96a 修正
- 6df161a：labels 字段语法错（array 形式 — Kustomize 期望 map）→ 82eb96a 修正
- 修正后 Pipeline + commit-level check-runs 全 success

---

## 4. 实施顺序（实际时间线）

| Step | commit | 风险/修复 |
|---|---|---|
| A sealed-secrets | 2c487b1（env 缺 value fix → 74b4dfc） | 1 轮回归 |
| B Kustomize overlay | 4143413 | labels array 错位（commit 内自检） |
| C PrometheusRule | 6df161a（labels.pairs map fix → 82eb96a） | labels 字段类型 |

---

## 5. 完成后状态

- ✅ 生产密钥：经 sealed-secrets 入仓（不再明文）
- ✅ 多环境部署：3 套 Kustomize overlay 一行 `kubectl apply -k overlays/{env}` 切换
- ✅ 故障告警：4 条关键 PrometheusRule
- ✅ master 干净 + commit-level check-runs 全 success（82eb96a）
- ✅ 文档：`docs/runbooks/sealed-secrets.md` / `docs/deploy/kustomize.md` / `docs/runbooks/alerts.md`

**GG20 退役不阻塞上述**——仍可作为独立后续批次（合并第 1+2 步单批完成）。
