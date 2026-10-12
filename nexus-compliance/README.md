# nexus-compliance

## 模块职责

合规与身份层。承担 KYC/AML、制裁筛查、可疑交易报告、去中心化身份（DID）、可验证凭证、信誉评分等合规与身份相关职责。

| 子包 | 职责 |
| --- | --- |
| `kyc` | KYC：申请提交、审核、状态查询、等级管理 |
| `aml` | 反洗钱：交易/地址/用户筛查、制裁名单匹配、可疑交易报告（STR） |
| `identity` | 去中心化身份：DID 创建/解析、可验证凭证校验 |
| `reputation` | 信誉评分：分数查询、事件驱动更新、历史回溯 |

## 技术栈

- Java 17
- Spring Boot 3.2.5
- Spring Data JPA
- Jackson 2.15.4
- Lombok 1.18.32
- H2 2.2.224（测试）

## 接口清单

### kyc（KYC）

- `KycService`
  - `submitKyc(KycApplication)` → `KycApplication`
  - `reviewKyc(applicationId)` → `KycApplication`
  - `getKycStatus(userId)` → `KycLevel`
- `KycLevel`（枚举）：`NONE` / `BASIC` / `ENHANCED` / `INSTITUTIONAL`
- 骨架实现：`DefaultKycService`（`@Service`）

### aml（反洗钱）

- `AmlScreeningService`
  - `screen(Transaction)` → `ScreeningResult`
  - `screenAddress(address)` → `ScreeningResult`
  - `screenUser(userId)` → `ScreeningResult`
- `SanctionListChecker`
  - `check(nameOrAddress)` → `SanctionHit[]`
- 名单数据入口（2026-10-11 起，**离线包优先**；2026-10-12 补官方格式解析）：
  - **离线包解析**：`SanctionListPackParser.parse(content, format)`
    —— 支持 **OFAC SDN CSV**（按 `SDN_Name` 取姓名、`-0-` 占位跳过、支持引号内逗号）、
    **UN 综合名单 XML**（FIRST/SECOND/THIRD_NAME 拼接 + ENTITY_NAME；别名不入名单）、
    纯文本兜底；XML 已关 DTD/外部实体（XXE 防护）。
  - **加载**：`InMemorySanctionListChecker.loadFromFile(path)`（按格式自动识别、追加语义）、
    `addEntries(...)`（批量）、`replaceAll(...)`（整体替换，仅显式场景）。
  - **HTTP 入口**（`nexus-gateway` 的 `SanctionListController`，一律 `ROLE_ADMIN`）：
    `GET|POST|DELETE /api/v1/compliance/sanction-list`（查/单条增/单条删）、
    `POST …/reload`（按配置路径重载）、
    **`POST …/import`**（离线包导入：裸体直传 / JSON / 服务器本地文件；`mode=append|replace`；
    解析 0 条 → 400 且名单不变）、
    **`GET …/imports`**（版本台账：来源/版本/格式/条目数/SHA-256/操作人）。
  - 每次变更写审计（含操作人与条目/版本明细）；导入另落 `sanction_list_imports` 台账（V97）。
- 骨架实现：`DefaultAmlService`（`@Service`）

### identity（去中心化身份）

- `DidService`
  - `createDid()` → `DidDocument`
  - `resolveDid(did)` → `DidDocument`
  - `verifyCredential(VerifiableCredential)` → `boolean`
- 骨架实现：`DefaultDidService`（`@Service`）

### reputation（信誉评分）

- `ReputationService`
  - `getScore(address)` → `ReputationScore`
  - `updateScore(address, event)` → `ReputationScore`
  - `getHistory(address)` → `List<String>`
- 骨架实现：`DefaultReputationService`（`@Service`）

## 实体清单

- `KycApplication`：申请 ID、用户 ID、证件类型、证件号、证件图片 URL、状态（PENDING/APPROVED/REJECTED/EXPIRED）、提交时间
- `ScreeningResult`：风险等级、命中名单、匹配详情、是否需要人工审核
- `SanctionListChecker.SanctionHit`：名单名称、匹配度、命中条目原始信息
- `SuspiciousTransactionReport`：报告 ID、交易详情、可疑原因、上报状态（DRAFT/SUBMITTED/ACKNOWLEDGED/REJECTED）、上报时间
- `DidDocument`：DID、公钥列表、认证方式、服务端点
- `VerifiableCredential`：发行者、持有者、凭证内容、签名、有效期
- `ReputationScore`：地址、分数、等级（A/B/C/D）、历史事件

## 构建

```bash
gradle build
```

## 状态

骨架阶段。所有 `@Service` 实现方法体均保留 `TODO` 注释，待后续填充业务逻辑。