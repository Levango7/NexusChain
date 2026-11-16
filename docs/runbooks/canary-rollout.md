# 灰度发布操作手册——没有 Nginx 金丝雀的那种

> **本仓不做「按比例切流量」的金丝雀。原因是实打实的：没有 ingress / service mesh，Kubernetes Service 是 Pod 轮询。**
> 本手册是一份「怎么安全上线新代码」的真实做法，以及为什么本系统不适合"金丝雀集群"。

---

## 0. 先搞清楚自己的栈

| 页面 | 真相 |
|---|---|
| prod `values-prod.yaml` | 7+ 服务全是 HPA + PDB + `maxSurge=1, maxUnavailable=0` 的 RollingUpdate + topologySpreadConstraints。**这就是部署策略**，不是"上一个大版本直接推"；蓝绿现成的那半边（滚动新增 + 探活成功才收旧版本）Kubernetes 已经替你做了。 |
| 有 ingress / Nginx / Istio? | **没有**。Chart 里没有 Ingress，Service 全是 ClusterIP。按比例切流量需要自己引入。 |
| prod 已有 staging 同款 values? | 有 `values-staging.yaml`，同样拓扑/差异做共享一套 Chart。 |

**所以"灰度"在本系统 = "先验证 staging（全栈演练），再谨慎 prod，再随时可回滚"。** 不是 Nginx 5%。

**链节点（nexus-core）更要走协调窗口**，见 CHANGELOG `[2.78.0]`「兼容性」段；链上节点同窗切。

---

## 1. 我们真正能用的分步流程（每步都可验证）

### 第 1 步：staging

```bash
helm upgrade --install nexuschain deploy/helm/ \
  --namespace nexus-staging --create-namespace \
  -f deploy/helm/values-staging.yaml
```

必须出现：`kubectl rollout status deployment -n nexus-staging` 全部 Ready；`scripts/acceptance-e2e.sh` 4/4 PASS；`scripts/doctor.sh` 无 FAIL。

### 第 2 步：prod（同版本、同 values 拓扑）

```bash
helm upgrade --install nexuschain deploy/helm/ \
  --namespace nexus-prod --create-namespace
  -f deploy/helm/values-prod.yaml
```

更新窗口公告 → 三个 StatefulSet (`nexus-core-0/1/2`) 一起升 →
`kubectl rollout status` 全部 Ready → 接 `scripts/acceptance-e2e.sh` 跑 PROD BASE URL 验收 4/4。

### 第 3 步：观察（不是金丝雀的那种比例，而是"if/then"）

```
故障指标     触发阈值             动作
-----------  ----------------    ----------------------------------
5xx 率       > staging 且 > 0%   立刻 helm rollback nexuschain <release> --namespace nexus-prod
/actuator/health 非 UP    > 0    停下流量、取 pod 日志，不回退但暂停滚动
Flyway 校验失败   > 0       停下，不发，等迁移前置对完
```

### 第 4 步：rollback 路径

`docs/runbooks/upgrade-rollback.md` + `scripts/upgrade-rollback-drill.sh` — 演过，PASS 11/0。

---

## 2. 如果将来要做真金丝雀

需要往里加两份东西——**两份都还没做**，做之前得确认：

1. 引入 ingress（Nginx/Istio/Kong）+ chart `Ingress` 模板 + 流量按权重路由到
   `*-canary` Service（tag=new 版本）。
2. chart 支持 stable / canary 两套镜像 tag 的部署模板（同时跑两套 Deployment，selector label 区分）。

**现阶段不做这两件事**：没有 ingress（私有化交付的客户网络拓扑千奇百怪，强加 ingress 会成为运维持久负担）、
Helm chart 也不应为灰度而复杂化。如果你确定要做，我可以起一个 `feat/grayline` 设计草稿。

---

## 3. 当前产品给你的是什么 guarantee

- 升级：RollingUpdate + maxSurge=1，探活成功才收旧 pod（零副本数下降）。
- PDB：minAvailable=2，滚升窗口内可用副本数不低于 2。
- 验收：prod 必须 4/4 E2E 通过。
- 回滚：`helm rollback` + 协议级 rollback runbook 已经走过 v2.76.0→v2.77.0→rollback 演练（11 PASS/0 FAIL）。
