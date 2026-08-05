# Changelog

本文件记录 NexusChain 各版本的变更。

## [1.0.0] - 2026-08-06

### 大版本主题：中间服务层从骨架走向真实实现，支付主链路接入风控与合规关卡

### 新增

- **清结算（nexus-settlement）**
  - 复式记账账本组件（Ledger）：结算落账、归集转账、余额查询
  - 对账服务：本地账本 vs 链上 / 银行渠道记录逐笔比对，四类差错识别（匹配 / 本地独有 / 外部独有 / 金额不符）
  - 资金归集服务：单笔归集、自动归集、热钱包阈值触发冷钱包转移
  - 风控规则真实逻辑：金额阈值、滑动窗口频次、地址黑名单（参数可配置）
  - 单元测试 20 个

- **合规（nexus-compliance）**
  - KYC：申请受理去重、自动审核（证件要素校验）、等级映射（NONE/BASIC/ENHANCED/INSTITUTIONAL）
  - AML：制裁名单筛查（内存名单检查器可注入）、四级风险分级（LOW/MEDIUM/HIGH/CRITICAL）、可疑交易报告（STR）受理登记
  - DID：Ed25519 密钥对生成、DID 文档创建/解析、可验证凭证签发与验签（含有效期校验）
  - 信誉评分：事件驱动加减分、等级重算（A/B/C/D）、历史回溯
  - 单元测试 30 个

- **数据分析（nexus-analytics）**
  - 交易图谱：BFS 子图构建、资金路径发现、启发式地址聚类
  - 链上监控：指标采集端口 + 阈值告警规则（双向比较）+ 定时轮询驱动
  - 告警服务：告警登记、确认、活动查询、按级别过滤
  - 统计服务：日交易量、商户 TopN、失败率、平均时延、综合报告
  - 用户分群：高净值 / 商户 / 长尾 / 沉默四类分群与画像
  - 数据导出：CSV/JSON 异步导出、报告导出、任务取消
  - 单元测试 32 个

- **预言机（nexus-oracle）**
  - 价格聚合：多源并发拉取、中位数偏离异常值剔除（20% 阈值）、加权置信度、价格订阅、历史价格窗口
  - 三个数据源真实实现：Binance / CoinGecko（HTTP 拉取 + 静态注入）、Chainlink（可注入报价）
  - 链上治理：提案生命周期（创建/投票/计票/惰性状态推进/执行延迟）、权重投票、防重投
  - 国库：提案联动校验（仅 TREASURY_SPEND 提案可支出）、余额扣减、支出历史审计
  - 可验证随机数：HMAC-SHA256 VRF 方案，生成/验证/常量时间比对
  - 单元测试 28 个

### 变更

- **网关主链路接入关卡**：发起支付过风控（黑名单/限额/规则链）、确认支付过 AML 筛查、退款过风控评估、编排支付路由前过统一风控
- 网关风控/合规桩实现改为委托中间层模块（composite build 进程内依赖）
- 状态机新增 `PENDING → FAILED` 转移（风控/合规拒绝落点）
- 订单状态枚举与错误码扩充（RISK_REJECTED / COMPLIANCE_REJECTED）

### 修复

- settlement/compliance 模块缺少 `useJUnitPlatform()` 导致测试静默跳过
- 四个中间层模块缺少 `bootJar.enabled = false`，作为库被消费时依赖解析失败
- 缺失的 `risk_profiles` / `settlement_batches` 建表迁移（V5），dev/prod validate 模式无法启动的问题
- DefaultRiskEngine 规则链装配缺口（原无法注入任何规则）

### 版本治理

- 全仓库版本号统一升级为 1.0.0（root / nexus-core version.properties / 四个中间层模块 / bridge / sdk / gateway / OpenAPI 文档 / demo）
- 网关对中间层模块依赖坐标同步为 `org.nexus:nexus-settlement:1.0.0` / `nexus-compliance:1.0.0`

### 测试

- 全量回归：5 个模块共 174 个测试全部通过（gateway 64 / settlement 20 / compliance 30 / analytics 32 / oracle 28）
