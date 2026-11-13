# A2 staging 开闸清单（部署方执行）

> **用途**：部署方在 **staging** 重新打开 A2（业务审批豁免凭据，ADR-036 §2.2）时的操作清单。
> **代码侧已全部就绪**（v2.78.0）；本清单只涉及**非代码动作**。**不执行不等于 A2 可用**——
> 当前 staging A2 为**关闭**状态（2026-10-31，板 `20261031-0310`：开关曾开启但 `NEX_WALLET_PLATFORM_PUBKEY`
> 从未下发，A2 提现腿 100% fail-closed）。
>
> **边界**：本清单只覆盖 **staging**。**生产开闸是独立动作**（values-prod + production environment
> 人工审批 + 协调窗口），本清单不授权、不替代生产流程。公钥值**不入仓、不入聊天**。
>
> 开闸口径与前因后果以板 `20261031-0310-qoder-cn-notice-staging-a2-off`（关闸原因）与
> `20261103-0035-zcode-a2-checklist-code-preverify`（代码侧预验）为准，本文档为其可执行摘要。

---

## 0. 前置确认

| # | 前置 | 验证方式 |
|---|---|---|
| 1 | staging 已部署 v2.78.0（或确认镜像 tag = `v2.78.0`） | `helm list -n nexus-staging` / `kubectl -n nexus-staging get deploy -o wide` |
| 2 | 持有 signing 平台热钱包 keystore（`WALLET_KEYSTORE_JSON` + `WALLET_KEYSTORE_PASSWORD`） | 由持有方确认，**值不出现于本清单/仓库** |
| 3 | staging 可观测就绪：gateway / wallet 的 `/actuator/prometheus` 可取（需 Bearer token） | `curl -H "Authorization: Bearer <token>" <gw>/actuator/prometheus \| grep nexus_` |
| 4 | 有回滚窗口（分钟级） | 确认 `helm upgrade` 路径可用 |

## 1. 从 keystore 导出 platform pubkey（不入仓）

由**持有 keystore 的一方**调用 signing 的 ADMIN 端点（或本地解密）：

```
POST /api/v1/signing/keystoreToPubkey
{ "keystoreJson": "<内容>", "password": "<密码>" }
```

返回值 = 平台热钱包公钥。同族端点：`keystoreToPubkeyHash` / `keystoreToAddress`。

> ⚠️ 公钥值不入仓、不贴入工单/IM。只通过 `values-staging.yaml` 的 Secret 渲染路径下发。

## 2. 下发公钥到**两侧**（用同一个值）

| 侧 | 配置项 | 说明 |
|---|---|---|
| wallet | `NEX_WALLET_PLATFORM_PUBKEY`（`spring:nexus.wallet.platform-pubkey`） | extraEnv 下发，供钱包侧校验豁免凭据 |
| gateway | `nexus.exchange-wallet.platform-pubkey` | 作为 signTransfer 的 `fromPubkey`（平台热钱包公钥）；按部署方式注入（Nacos / 启动参数 / env 松绑定），仓库无固定 env 名 |

## 3. 恢复开关

wallet `extraEnv` 恢复：

```yaml
NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED: "true"
```

> k8s-sync-check.yml 对 staging 是**软告警**（flag 未开启 → 告警分支转绿）；prod 是**硬断言**（本就没开，不受影响）。

## 4. 部署与验证（按序）

```bash
helm upgrade --install nexuschain deploy/helm/ -n nexus-staging \
  -f deploy/helm/values-staging.yaml
kubectl rollout status deploy/nexus-wallet-service -n nexus-staging
kubectl rollout status deploy/nexus-gateway -n nexus-staging
```

观察（建议 ≥ 3 个调度周期，观察窗≥15 分钟）：

- [ ] wallet / gateway pods Running，重启次数正常，日志无 `5000` / `5010` 异常；
- [ ] 发起一笔**沙箱内小额**提现请求 → A2 审批单出现 → 单人豁免凭据放行（**不再 fail-closed 拒绝**）；
- [ ] 结算确认链路：含 txHash 的提现落地后，结算确认查询 200（与交付验收 `B-04` 同口径）；
- [ ] Prometheus 中 `nexus_*` 指标正常（如 `nexus_settlement_confirmation_total`），无异常趋势告警；
- [ ] staging 对外示例页面/沙箱 API 可用（如用于演示）。

## 5. 回滚（分钟级，零迁移零数据库变更）

删除两键 `NEX_WALLET_PLATFORM_PUBKEY`、`NEXUS_SIGNING_BUSINESS_APPROVAL_ENABLED=true`（或置 `false`），再 `helm upgrade` 一次。A2 代码与表结构**不动**，退回 A0 双闸形态。

## 6. 生产的独立性（不在本清单内）

生产的同款开闸需要：values-prod 同样两键 + `production` environment 人工审批（required reviewer）+ 协调窗口（v2.78.0 含 P0 共识变更，见 CHANGELOG）。staging 验证通过**不等于**可直接照搬到 prod。
