# NexusChain Oracle

NexusChain 预言机与治理层模块，提供外部数据喂价、可验证随机数、链上治理（提案 / 投票 / 参数升级 / 国库支出）能力。

## 模块定位

`nexus-oracle` 是 NexusChain 与外部世界交互的统一入口，向上为链上合约 / 业务系统
提供可信外部数据与随机源，向下对接中心化交易所、聚合器、链上预言机合约。

模块当前为**接口骨架**：所有接口契约已固化，骨架 `@Service` / `@Component` 实现以
TODO 标注待填充点，便于后续按子能力并行开发。

## 技术栈

| 项 | 版本 |
|----|------|
| Java | 17 |
| Spring Boot | 3.2.5 |
| Gradle | 8.5（与根项目一致） |
| Jackson | 2.15.4 |
| Lombok | 1.18.32 |

## 包结构

```
org.nexus.oracle
├── price            外部数据喂价
│   └── feeds        数据源适配器（Binance / CoinGecko / Chainlink）
├── random           可验证随机数（VRF）
└── governance       链上治理（提案 / 投票 / 国库）
```

## 接口清单

### price — 喂价

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `PriceOracle` | 价格查询、订阅、历史价格 |
| 接口 | `PriceFeed` | 单数据源接口 |
| 实体 | `PriceEntry` | 价格条目（资产 / 价格 / 时间戳 / 数据源 / 置信度） |
| 骨架 | `AggregatedPriceOracle` | 多源聚合 `@Service`（异常值剔除 + 置信度评估） |
| 骨架 | `feeds.BinancePriceFeed` | Binance 适配器 `@Component` |
| 骨架 | `feeds.CoinGeckoPriceFeed` | CoinGecko 适配器 `@Component` |
| 骨架 | `feeds.ChainlinkPriceFeed` | Chainlink 链上预言机适配器 `@Component` |

**核心方法**

- `PriceOracle.getPrice(asset) / subscribe(asset, callback) / getHistoricalPrice(asset, time)`
- `PriceFeed.fetch(asset) → BigDecimal`

### random — 可验证随机数

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `RandomOracle` | VRF 随机数生成与验证 |
| 实体 | `RandomProof` | 随机数证明（随机数 / 证明 / 生成者签名） |
| 骨架 | `DefaultRandomOracle` | VRF 方案 `@Service` 占位实现 |

**核心方法**

- `generateRandom(seed) → RandomProof`
- `verifyRandom(random, proof) → boolean`

### governance — 链上治理

| 类型 | 类 | 说明 |
|------|----|------|
| 接口 | `GovernanceService` | 提案创建 / 投票 / 执行 / 状态查询 |
| 接口 | `Treasury` | 国库余额 / 支出 / 历史 |
| 实体 | `Proposal` | 提案（含 `Type` 内嵌枚举：PARAMETER_CHANGE / SOFTWARE_UPGRADE / TREASURY_SPEND） |
| 实体 | `Vote` | 投票（含 `Option` 内嵌枚举：YES / NO / ABSTAIN） |
| 枚举 | `ProposalState` | PENDING / ACTIVE / PASSED / REJECTED / EXECUTED / CANCELED |
| 骨架 | `DefaultGovernanceService` | `@Service` 占位实现 |
| 骨架 | `DefaultTreasury` | `@Service` 占位实现 |

**核心方法**

- `GovernanceService.createProposal(Proposal) / vote(proposalId, vote) / executeProposal(proposalId) / getProposalState(proposalId)`
- `Treasury.balance() / spend(amount, to, proposalId) / getHistory()`

## 构建

```bash
gradle build
```

模块独立 `settings.gradle` 已配置 `rootProject.name = 'nexus-oracle'`，
可单独构建，也可由根项目 `settings.gradle` 通过 `include 'nexus-oracle'` 收编。

## 后续路线

- [ ] 接入 Binance / CoinGecko / Chainlink 三源实现 `AggregatedPriceOracle` 聚合逻辑
- [ ] 接入 VRF 库（ECVRF / chainlink VRF v2）实现 `DefaultRandomOracle`
- [ ] 接入链上治理合约实现 `DefaultGovernanceService` + `DefaultTreasury`
- [ ] 接入价格变更推送通道（WebSocket / Kafka）实现 `subscribe` 回调