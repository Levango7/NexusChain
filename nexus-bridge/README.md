# NexusChain Bridge

> NexusChain 跨链桥模块 — 基于 Lock-Mint 模式实现跨链资产转移

## 模块定位

NexusChain Bridge 是 NexusChain 平台的跨链资产转移核心模块，采用 **Lock-Mint** 模式实现不同区块链网络之间的 NEX 代币跨链转移。

Lock-Mint 模式的工作原理：

```
┌──────────┐   BRIDGE_LOCK    ┌───────────────┐   BRIDGE_MINT    ┌──────────┐
│  原链     │ ──────────────► │  桥验证者集合   │ ───────────────► │  目标链   │
│ (Source)  │   锁定 NEX     │  (Validators)  │   铸造 NEX      │ (Target) │
│          │ ◄────────────── │  多签 + 时间锁  │ ◄─────────────── │          │
│          │  BRIDGE_UNLOCK  │               │  BRIDGE_BURN     │          │
└──────────┘   解锁 NEX     └───────────────┘   销毁 NEX       └──────────┘
```

- **正向跨链**（原链 → 目标链）：在原链锁定 NEX，在目标链铸造等量 NEX
- **反向跨链**（目标链 → 原链）：在目标链销毁 NEX，在原链解锁等量 NEX

## 功能

| 操作 | 枚举值 | 方向 | 说明 |
|------|--------|------|------|
| 锁定 | `BRIDGE_LOCK` | 原链 | 用户在原链将 NEX 锁定到桥合约托管地址 |
| 铸造 | `BRIDGE_MINT` | 目标链 | 验证者多签确认后，在目标链铸造等量 NEX 给用户 |
| 销毁 | `BRIDGE_BURN` | 目标链 | 用户在目标链销毁 NEX，发起反向跨链 |
| 解锁 | `BRIDGE_UNLOCK` | 原链 | 验证者多签确认后，在原链解锁等量 NEX 给用户 |

## 架构

### 安全机制

```
┌─────────────────────────────────────────────────────────────┐
│                    NexusChain Bridge Architecture                │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │ 桥验证者多签  │  │  时间锁      │  │  金额上限 & 日限额   │ │
│  │ Multi-Sig   │  │  Time-Lock  │  │  Quota & Daily Limit │ │
│  │             │  │             │  │                     │ │
│  │ N-of-M 签名  │  │ 延迟确认窗口  │  │ 单笔上限 / 24h 累计   │ │
│  │ 防止单点作恶  │  │ 防止紧急回滚  │  │ 防止大额异常流出      │ │
│  └─────────────┘  └─────────────┘  └─────────────────────┘ │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              紧急暂停 (Emergency Pause)               │   │
│  │  任何验证者可触发暂停，暂停后仅允许 UNLOCK 退回资产     │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

- **桥验证者多签**：采用 N-of-M 多签机制，跨链操作需达到阈值数量的验证者签名确认，防止单点作恶。
- **时间锁**：大额跨链交易设有时间锁确认窗口，在此期间异常交易可被其他验证者否决。
- **金额上限与日限额**：设置单笔跨链上限和 24 小时累计流出限额，防止大额资产异常流出。
- **紧急暂停**：任何验证者可触发桥状态进入 PAUSED，暂停期间仅允许反向解锁（BRIDGE_UNLOCK）以保护用户资产。

### 桥状态

| 状态 | 说明 |
|------|------|
| `ACTIVE` | 桥正常运行，所有操作可用 |
| `PAUSED` | 桥暂停，仅允许 UNLOCK 退回资产 |
| `EMERGENCY_STOP` | 紧急停止，所有操作禁止 |

## 与其他模块的集成

### nexus-core 集成

- **RPC 客户端**：通过 `nexus-core-rpc-client` 与各链节点通信，提交交易、查询状态、监听事件。
- **交易管理**：跨链交易的状态机由 nexus-core 的交易生命周期管理支撑。
- **事件总线**：桥事件通过 nexus-core 的事件总线进行传播和持久化。

### nexus-consortium 集成

- **验证者集合**：桥验证者列表来自 nexus-consortium 的共识层治理结果。
- **多签聚合**：跨链操作的多签收集与验证由 nexus-consortium 的签名聚合服务完成。
- **治理**：桥参数（阈值、限额、时间锁周期）的变更通过 nexus-consortium 治理提案投票决定。

```
┌──────────────────┐       ┌──────────────────┐       ┌──────────────────────┐
│  nexus-core     │       │  nexus-bridge    │       │  nexus-consortium   │
│                  │       │                  │       │                      │
│  RPC Client ─────┼──────┤  Bridge Handlers │◄──────┤  Validator Set       │
│  Event Bus ──────┼──────┤  Bridge Service  │◄──────┤  Multi-Sig Aggregate │
│  Tx Lifecycle ───┼──────┤  State Machine   │◄──────┤  Governance          │
│                  │       │                  │       │                      │
└──────────────────┘       └──────────────────┘       └──────────────────────┘
```

## API 概览

### REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/bridge/lock` | 发起跨链锁定请求 |
| POST | `/api/v1/bridge/mint` | 发起跨链铸造请求（验证者调用） |
| POST | `/api/v1/bridge/burn` | 发起反向跨链销毁请求 |
| POST | `/api/v1/bridge/unlock` | 发起反向跨链解锁请求（验证者调用） |
| GET | `/api/v1/bridge/tx/{txId}` | 查询桥交易详情 |
| GET | `/api/v1/bridge/tx?sourceTxHash={hash}` | 按源链交易哈希查询 |
| GET | `/api/v1/bridge/status` | 查询桥状态与限额使用情况 |
| POST | `/api/v1/bridge/pause` | 验证者暂停桥 |
| POST | `/api/v1/bridge/resume` | 验证者恢复桥 |

### Java API（核心接口）

```java
public interface BridgeService {

    BridgeTransaction lock(LockRequest request);

    BridgeTransaction mint(MintRequest request);

    BridgeTransaction burn(BurnRequest request);

    BridgeTransaction unlock(UnlockRequest request);

    BridgeTransaction getTransaction(String txId);

    BridgeStatus getStatus();
}
```

## 技术栈

- **语言**：Java 11
- **框架**：Spring Boot 2.7.x
- **区块链交互**：Web3j 4.9.x
- **构建工具**：Gradle
- **测试**：JUnit 5 + Mockito

## 构建

```bash
# 编译
./gradlew build

# 运行测试
./gradlew test

# 打包
./gradlew bootJar
```

## 目录结构

```
nexus-bridge/
├── build.gradle
├── settings.gradle
├── README.md
└── src/
    ├── main/
    │   ├── java/org/nexus/bridge/
    │   │   ├── BridgeService.java          # 桥服务主接口
    │   │   ├── BridgeValidator.java         # 桥验证者接口
    │   │   ├── BridgeState.java             # 桥状态枚举
    │   │   ├── LockRequest.java             # 锁定请求 DTO
    │   │   ├── MintRequest.java             # 铸造请求 DTO
    │   │   ├── BridgeConfig.java            # 桥配置
    │   │   ├── handler/
    │   │   │   ├── AbstractBridgeHandler.java   # 桥处理器抽象类
    │   │   │   ├── EthereumBridgeHandler.java   # 以太坊桥处理器
    │   │   │   └── BSCBridgeHandler.java        # BSC 桥处理器
    │   │   └── model/
    │   │       ├── BridgeTransaction.java      # 桥交易模型
    │   │       └── BridgeEvent.java            # 桥事件模型
    │   └── resources/
    │       └── application.yml
    └── test/
        └── java/org/nexus/bridge/
            └── BridgeServiceTest.java       # 基础测试
```

## 许可证

Copyright (c) NexusChain. All rights reserved.
