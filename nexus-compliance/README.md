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