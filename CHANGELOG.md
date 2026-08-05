# Changelog

本文件记录 NexusChain 各版本的变更。

## [1.2.0] - 2026-08-06

### 主题：第一类纯逻辑骨架补全（白名单 / 货币转换 / 退款审批 / SDK 客户端）

### 新增

- **钱包地址白名单（nexus-exchange-wallet）**
  - 白名单增删查、按商户过滤、首次提币延迟检查（可配置小时数）
  - 地址格式校验、软删除
- **网关货币转换（nexus-gateway）**
  - USD 基准表交叉汇率、可配置点差（spread-bps）、币种子集管理
  - 恒等短路、汇率缺失保护
- **网关退款审批流（nexus-gateway）**
  - 退款请求 / 审批 / 拒绝 / 执行完整工作流（refund_requests 表 V6）
  - RefundPolicy：可退性校验、最大退款额、退款窗口（可配置天数）
- **SDK 客户端封装（nexus-sdk）**
  - BridgeClient：锁定 / 解锁 / 状态查询 / 支持链 / 手续费
  - PaymentChannelClient：开启 / 关闭 / 状态更新 / 查询 / 争议
  - StableCoinClient：铸造 / 销毁 / 转账 / 抵押率 / 价格 / 总供应量

### 修复

- **Wallet.create 密码超长 bug**：随机密码 16 字节→32 位 hex 恒超 fromPassword 的 8-20 长度上限，改为 8 字节→16 位 hex
- **陈旧 SDK 单元测试**：SdkUnitTest 断言骨架行为（抛 UnsupportedOperationException），SDK 实现后回归失败，按真实行为更新断言

### 测试

- 全量回归：9 个模块共 593 个测试全部通过
  （core 277 / bridge 49 / wallet 31 / gateway 89 / settlement 20 / compliance 30 / analytics 32 / oracle 28 / sdk 37）

## [1.1.0] - 2026-08-06

### 主题：合约引擎落地 + 跨链/钱包骨架补全 + 假集成修复

### 新增

- **合约引擎（nexus-core）**
  - WASM 执行器真实实现：接入 Chicory 纯 Java WASM 解释器（无原生依赖），
    支持部署 / 调用 / 查询，二进制 i64 ABI，gas 按指令计费
  - ChicoryWasmEngine / ChicoryWasmInstance：模块校验、实例化、导出函数调用、
    按地址加载与编译缓存
  - EVM 兼容层：内嵌栈式 EVM 子集解释器（算术 / 栈 / 内存 / 存储 / 跳转 / REVERT），
    256 位字宽，接入 ContractStorage
  - ContractStorage：合约 KV 存储（slot → 32 字节值），快照与写回
  - RPC 接线：nexus_deployContract / nexus_callContract / nexus_queryContract
    三个端点，按 vmType 选取 WASM / EVM 执行器

- **跨链桥（nexus-bridge）**
  - Relayer 网络：中继请求生命周期、信誉×质押加权随机选取、中继证明验证
  - 流动性管理：储备注入 / 抽取、利用率计算、跨链再平衡

- **钱包（nexus-exchange-wallet）**
  - 提币审批流：白名单校验、分级审批人数、审批累计、拒绝、执行
  - 默认审批策略：按金额分级（1 / 2 / 3 审批人）+ 地址白名单
  - 托管服务：热 / 冷钱包余额管理、转账校验、策略再平衡（自动归集 + 下限回补）

### 修复

- **StableCoinService 假集成**：getPrice() 硬编码 1.00 却谎称 source=oracle；
  改为可配置锚定价（peg-price）与来源标识（price-source，默认 PEG），诚实标注
- **Gradle daemon 文件锁**：清理残留的 foojay-resolver jar 过期锁

### 版本治理

- 全仓库版本号统一升级为 1.1.0
- 网关对中间层模块依赖坐标同步为 org.nexus:nexus-settlement:1.1.0 / nexus-compliance:1.1.0

### 测试

- 全量回归：8 个模块共 520 个测试全部通过
  （core 277 / bridge 49 / wallet 20 / gateway 64 / settlement 20 / compliance 30 / analytics 32 / oracle 28）
- 新增测试：WASM 引擎 5、EVM 解释器 6、Relayer 9、流动性 9、审批 10、托管 9

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
