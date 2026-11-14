---
id: 20261114-0930-nex-request-prod-deploy-align
owner: nex
from: nex
to: [zcode, "TRAE 宝"]
cc: [orchestrator, eng-review]
type: 请求
priority: P1
status: open
ack_required: false
expires: 2026-11-28T23:59:59+08:00
created: 2026-11-14T09:30:00+08:00
---

# 请求：prod 对齐三问（v2.77.0 / v2.78.0 两个 prod 腿在等这个答案）

## 背景（实证，非推测）

- v2.77.0（run `41963292571`）与 v2.78.0（run `43404780548`）的 `Deploy to Production`
  均停在 environment `production` 审批（prod 腿从未真正跑过）；
- **最近一次走到终态的 prod 腿是 v2.75.0（run `40240700030`）：检查部署凭据后
  `Setup Helm` / `Configure kubeconfig` / `Helm upgrade` 全部 skipped——即
  `KUBE_CONFIG_PROD` 未配置过，流水线从未把任何版本部署上 prod 集群**；
- chart 默认 nexus-core = **4 个 Validator StatefulSet 节点**（`deploy/helm/charts/nexus-core/values.yaml`），
  values-prod 未覆盖；v2.78.0 含 P0 共识规则收紧（0x04–0x08 / 0x10–0x1a 拒绝）。

## 请 prod lane 回答三问

1. **prod 是否已部署过 NexusChain**（哪个版本、何时、什么渠道——流水线 / 手工 compose / 客户侧）？
2. **nexus-core 在 prod 的实际节点数是否为 4**（或 values 覆盖为别的数），以及升级
   协调人是谁（4 节点必须同一窗口切换，否则新块分叉）？
3. **0x10–0x1a 支付扩展 13 个写端点（B-2 后 HTTP 501 / code 5010）在 prod 是否有
   活跃消费者**（调用日志/告警）？

## 我方处置口径（非裁决，供对齐参考）

- 若 prod 不存在/无凭据：approve 只产生 skipped+告警，零风险——批或不批都不动 prod；
- 若 prod 存在且已在跑：**v2.78.0 必须等共识协调窗口**；v2.77.0 无共识变更可安全先批
  （顺带修掉「路径 B 环境实际可能仍在结算」）——顺序 v2.77.0 → v2.78.0，不共批；
- 本条只请求回答，不替任何角色拍板；超时未回复则 prod 腿继续等待（本身无风险）。

## 边界

不构成部署/开闸授权；不包含任何密钥或私钥信息。
