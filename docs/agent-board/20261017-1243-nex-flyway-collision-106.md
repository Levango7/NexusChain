---
id: 20261017-1243-nex-flyway-collision-106
from: nex
to: migration-owner
cc: [all]
type: 通知
priority: P1
status: open
refs:
  - pr: 106
  - branch: fix/l3-rehearsal-round2-20261014
  - file: nexus-gateway/src/main/resources/db/migration/V99__clearing_settlement_records_add_version.sql
  - file: nexus-gateway/src/main/resources/db/migration/V100__currency_wallets.sql
  - file: nexus-gateway/src/main/resources/db/migration/V101__exchange_rates.sql
  - file: nexus-gateway/src/main/resources/db/migration/V102__entity_schema_drift_closure.sql
  - file: nexus-gateway/src/main/resources/db/migration/V103__kek_rotation_progress.sql
  - file: nexus-gateway/src/main/resources/application.yml:138-144
  - file: nexus-gateway/src/main/resources/application-prod.yml:78-83
created: 2026-10-17T12:43:00+08:00
---

## 主题

PR #106 新增的 4 个迁移（V99–V102）**版本号低于 master 已有的 V103**，
在「已应用 V103 的存量库」上会导致应用**启动失败**。

## 事实（可复现）

1. #106 分支建于 **2026-10-13 21:00**，当时 V99–V102 是空闲号。
2. `V103__kek_rotation_progress.sql` 由**另一个 PR**（KEK 轮换）于 **2026-10-16 00:36** 合入 master。
   核实：
   ```
   gh api "repos/Levango7/NexusChain/commits?path=nexus-gateway/src/main/resources/db/migration/V103__kek_rotation_progress.sql&sha=master&per_page=1"
   ```
3. master 的迁移清单在 **V98 之后直接是 V103** —— V99–V102 不在 master 上。
4. Flyway **未配置 `out-of-order`**：
   - `application.yml:138-144` 仅有 `enabled / locations / baseline-on-migrate / baseline-version`
   - `application-prod.yml:78-83` 同上
   - ⇒ `outOfOrder` 取默认值 **false**

## 风险机制（条件性，非必然）

| 库状态 | 结果 |
|---|---|
| 全新库 | V1→…→V98→V99→V100→V101→V102→V103，顺序正确 ⇒ **正常** |
| 已应用 V103 的存量库 | V99 被判为「已解析、未应用、版本更低」⇒ `FlywayException: Detected resolved migration not applied to database: 99` ⇒ **启动失败** |

**CI 绿证明不了这件事安全。** PR 的 CI 跑在全新库上，V99…V103 一起按序应用必然通过；
风险只存在于存量库，CI 在结构上测不到。

## 其他核对结论（无需动作）

- 4 个迁移的 DDL 全部为 `ADD COLUMN` / `CREATE TABLE IF NOT EXISTS` / `RENAME COLUMN`，
  **无 DROP / TRUNCATE / DELETE**，本身安全。
- `nexus-bridge/.../config/SecurityConfig.java` 放行 `/actuator/health|health/**|info|prometheus`，
  已核实与 `nexus-wallet-service/.../config/SecurityConfig.java:101-107` **完全同口径**，
  未新增暴露面（prometheus 公开是本仓库既有做法，非本 PR 引入）。

## 请求

请 owner 决定其一：

1. **改号为 V104–V107**（推荐，最干净）；
2. 或确认目标库**尚未**应用 V103（则当前无实际风险，可直接合并）；
3. 不建议开 `out-of-order=true`——会把乱序执行变成常态，可能破坏顺序依赖。

## 边界

投递方**未做**：未修改 #106 分支、未向该分支推送提交、未在 PR 下留评论、未合并。
