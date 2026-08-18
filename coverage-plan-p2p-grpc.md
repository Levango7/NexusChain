# P2P 与 MPC gRPC 加密层覆盖率提升方案

> 分析人：分析师(覆盖率) ｜ 任务 ID：230 ｜ 日期：2026-08-18
> 目标：`org.nexus.p2p`（0% → 50%+）、`org.nexus.signing.mpc.crypto.grpc`（0% → 50%+）

## 第1章 关键事实澄清（结论依据）

### 1.1 路径与代码性质

| 任务给定路径 | 实际位置 | 代码性质 | 依据 |
|---|---|---|---|
| `nexus-signing-service/.../mpc/crypto/grpc/` | `build/generated/source/proto/main/{grpc,java}/.../mpc/crypto/grpc/` | **protobuf/gRPC 生成代码**，非手写源码 | `build.gradle:167` protobuf 插件配置；目录扫描确认 `src/main/.../crypto/grpc/` 不存在 |
| `nexus-core/.../p2p/` | `src/main/java/org/nexus/p2p/` | 手写 + 混入 2 个生成类 | `NexusChainOuterClass.java` 15910 行、`NexusChainGrpc.java` 242 行均为 protoc 生成 |

### 1.2 两个包的文件清单与行数（实测）

**表：p2p 包文件行数对照表**

| 文件 | 行数 | 类型 | 已有测试 |
|---|---|---|---|
| NexusChainOuterClass.java | 15910 | protoc 生成 | 无（不应手测） |
| PeerServer.java | 319 | 手写 | 无 |
| PeersCache.java | 296 | 手写 | 无 |
| MerkleHandler.java | 275 | 手写 | 无 |
| GRPCClient.java | 208 | 手写 | 无 |
| PeersCacheWrapper.java | 175 | 手写 | 无 |
| Payload.java | 178 | 手写 | 无 |
| Util.java | 172 | 手写 | 无 |
| Peer.java | 159 | 手写 | **PeerTest** |
| PeersManager.java | 84 | 手写 | 无 |
| MessageFilter.java | 59 | 手写 | 无 |
| PeersStorage.java | 53 | 手写 | 无 |
| Context.java | 51 | 手写 | **ContextTest** |
| GetBlockQuery.java | 48 | 手写 | 无 |
| HostPort.java | 43 | 手写 | **HostPortTest** |
| MessageLogger.java | 30 | 手写 | 无 |
| Status.java | 29 | 手写 | 无 |
| Plugin.java | 7 | 接口 | — |
| NexusChainGrpc.java | 242 | protoc 生成 | 无 |
| test/Main.java | 12 | 示例 main | — |

**表：crypto/grpc 生成包文件行数对照表**

| 文件 | 行数 | 类型 |
|---|---|---|
| MpcCryptoProto.java | 8410 | protobuf message 生成 |
| MpcCryptoServiceGrpc.java | 566 | gRPC stub 生成 |
| 合计 | 8976 | 全部生成 |

### 1.3 0% 覆盖率的根因（有据可查）

1. **分母膨胀**：两个 `build.gradle` 均**未配置 JaCoCo `excludes`**（`nexus-core/nexus-core/build.gradle:240-252`、`nexus-signing-service/build.gradle:185-196` 仅设 BUNDLE 0.15 门禁，无 excludes）。生成代码（p2p 侧 16152 行、crypto/grpc 侧 8976 行）全部计入覆盖率分母，几乎无法被单元测试覆盖，把包级覆盖率压到接近 0%。
2. **手写类缺测**：p2p 包 14 个手写类中仅 3 个（Peer/HostPort/Context）有测试；`PeerServer`/`PeersCache`/`MerkleHandler`/`GRPCClient`/`Util`/`Payload` 等核心类无测试。
3. **crypto 包手写类已有较好覆盖**：`DkgRequest`/`SignRequest`/`AggregateRequest`/Response 六个 DTO 均有测试，`GrpcMpcCryptoEngine` 已有 `GrpcMpcCryptoEngineTlsConfigTest`（覆盖 `init()` TLS 分支）。0% 的是 `crypto/grpc` 生成包本身，而非 `GrpcMpcCryptoEngine`。

## 第2章 p2p 包类级可测试性分析

### 2.1 可单元测试类（纯逻辑 / 可 Mock 依赖）

**表：p2p 手写类可测试性分级表**

| 类 | 行数 | 职责 | 外部依赖 | 可测试性 | 可测行数估算 | 建议策略 |
|---|---|---|---|---|---|---|
| PeersCache | 296 | Kademlia K-bucket 路由表管理（keepPeer/removePeer/pend/half/blockPeer） | 仅 Peer/HostPort 纯逻辑 | **高** | ~260 | 参数化测试，覆盖 K-bucket 分桶、满桶淘汰、衰减归零 |
| PeersCacheWrapper | 175 | PeersCache 读写锁委托 | PeersCache | **高** | ~160 | 继承 PeersCache 测试 + 验证委托调用 |
| Util | 172 | 消息构建/签名/大消息拆分 | protobuf 生成类、Ed25519 | **中** | ~120 | buildMessage 14 个 code 分支 + split 拆分逻辑 |
| Payload | 178 | protobuf Message → 强类型 body 解析 | protobuf、Peer.parse | **中** | ~140 | 构造各 code 的 Message 测 parseBody switch |
| Peer | 159 | 节点标识/parse/subTree/distance | Ed25519、Hex | **高** | ~140 | 已有 PeerTest，补 parse/newPeer/异常分支 |
| PeersManager | 84 | PING/PONG/LOOKUP/PEERS 插件 | PeerServer | **中** | ~70 | Mock PeerServer 测 onMessage 分发 |
| MessageFilter | 59 | 签名校验/重复/自回环过滤 | Ed25519PublicKey | **中** | ~45 | Mock 验签结果测 4 条过滤路径 |
| GetBlockQuery | 48 | 区块查询区间裁剪 clip | 纯逻辑 | **高** | ~40 | 参数化测试 clip 边界 |
| Context | 51 | 消息处理标志位 | 纯逻辑 | **高** | ~45 | 已有 ContextTest |
| HostPort | 43 | host:port 值对象 | 纯逻辑 | **高** | ~40 | 已有 HostPortTest |
| MessageLogger | 30 | 消息日志插件 | slf4j | **高** | ~25 | 简单单测 |
| Status | 29 | 状态 POJO | 纯逻辑 | **高** | ~20 | POJO getter |
| PeersStorage | 53 | PeersCache LevelDB 持久化 | Leveldb、JSON codec | **中** | ~35 | Mock Leveldb 测 init/flush |
| PeerServer | 319 | gRPC 服务端 + 消息分发 | gRPC Server、6 个 @Autowired | **中低** | ~180 | Mock 依赖测 onMessage/entry/relay/dial/broadcast；startListening 走集成 |
| MerkleHandler | 275 | Merkle 树同步插件 | 5 个 @Autowired + PeerServer | **中** | ~180 | Mock 依赖测 onMessage 4 分支 + replaceTrans 校验链 |
| GRPCClient | 208 | gRPC 客户端 dial/buildMessage | gRPC Netty/TLS | **低** | ~80 | buildMessage/getNonce/withSelf 可测；getChannel/dial 走集成 |

可测试行数合计估算：~1380 行（手写类约 1888 行，可测比例 ~73%）。

### 2.2 难以单元测试的类（真实网络/IO）

- **PeerServer.startListening()**：启动真实 gRPC Server（`NettyServerBuilder.forPort`），需集成测试或 grpc-inprocess。
- **GRPCClient.getChannel()/dial()/dialAsync()**：建立真实 Netty channel，建议用 `io.grpc.inprocess.InProcessServerBuilder` 做集成测试，或 Mock `ManagedChannel`。
- **PeersStorage.init()/flush()**：依赖 LevelDB 文件 IO，可 Mock `Leveldb` 接口规避。

## 第3章 crypto/grpc 生成包覆盖策略

### 3.1 生成代码不应直接写单元测试

`MpcCryptoProto.java`（8410 行）与 `MpcCryptoServiceGrpc.java`（566 行）由 `protoc` + `protoc-gen-grpc-java` 生成（`build.gradle:167-181`）。直接为生成代码写测试：
- 维护成本高（重新生成即失效）；
- 生成代码已由 protobuf/gRPC 官方保证质量；
- 行业惯例是 JaCoCo 排除生成代码。

### 3.2 推荐覆盖路径

1. **首选：JaCoCo 排除生成包**（见第4章），使 `crypto/grpc` 包不计入分母，包级覆盖率指标即不再为 0%。
2. **间接覆盖**：通过 `GrpcMpcCryptoEngine` 的单元测试（Mock `MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub`）调用 `toProto`/`fromProtoWithZeroization`，会执行生成类的 `newBuilder()`/`getXxx()`/`parseFrom()` 等方法，对生成代码产生实际覆盖（但 JaCoCo 排除后不再统计）。
3. **若必须统计生成包覆盖率**：用 `io.grpc.inprocess.InProcessServerBuilder` 起一个内存 gRPC 服务，注册 `MpcCryptoServiceGrpc` 的实现，`GrpcMpcCryptoEngine` 通过 in-process channel 调用，可覆盖 stub 的 `dkg`/`sign`/`aggregate`/`healthCheck` 调用链。但 8410 行 message 类的 `parseFrom`/`mergeFrom`/`equals`/`hashCode` 全分支覆盖不现实，仍建议排除。

## 第4章 覆盖率提升方案（优先级排序）

### 4.1 立即执行：JaCoCo 排除生成代码（零成本消除分母膨胀）

**代码示例：nexus-core build.gradle jacoco 排除配置（Groovy）**

```groovy
jacocoTestReport {
    afterEvaluate {
        classDirectories.setFrom(files(classDirectories.files.collect {
            fileTree(dir: it, exclude: [
                'org/nexus/p2p/NexusChainOuterClass.class',
                'org/nexus/p2p/NexusChainGrpc*.class',
                'org/nexus/p2p/NexusChainGrpc$*'
            ])
        }))
    }
}
```

**代码示例：nexus-signing-service build.gradle jacoco 排除配置（Groovy）**

```groovy
jacocoTestReport {
    afterEvaluate {
        classDirectories.setFrom(files(classDirectories.files.collect {
            fileTree(dir: it, exclude: [
                'org/nexus/signing/mpc/crypto/grpc/**',
                'org/nexus/signing/mpc/transport/grpc/**'
            ])
        }))
    }
}
```

> 预期效果：p2p 包分母从 ~18000 行降至 ~1888 手写行；crypto/grpc 包从 8976 行生成代码中移出统计。这是把"0%"拉到有意义区间的前置动作。

### 4.2 手写类补测优先级（按可测试行数降序）

**表：p2p 包补测优先级与策略表**

| 优先级 | 类 | 可测行数 | 测试策略 | 预计覆盖率 |
|---|---|---|---|---|
| P0 | PeersCache | 260 | 参数化测试：K-bucket 分桶、MAX_PEERS 上限、half 衰减、block/remove | 85% |
| P0 | PeerServer | 180 | Mockito mock 6 个 @Autowired + gRPCClient；测 onMessage/entry/relay/dial/broadcast/getIP | 55% |
| P0 | MerkleHandler | 180 | Mockito mock 5 依赖 + PeerServer；测 onMessage 4 分支、replaceTrans 校验链、getRootTreeNodes | 65% |
| P1 | Payload | 140 | 构造 14 种 code 的 Message 测 parseBody + getter | 80% |
| P1 | Util | 120 | buildMessage 14 分支 + split(Blocks/Transactions) 拆分 + getRawForSign | 70% |
| P1 | PeersCacheWrapper | 160 | 复用 PeersCache 用例 + 验证锁委托 | 90% |
| P2 | GRPCClient | 80 | 测 buildMessage/getNonce/withSelf/withTimeout；getChannel 走 in-process | 40% |
| P2 | PeersManager | 70 | Mock PeerServer 测 PING/PONG/LOOKUP/PEERS 分发 | 85% |
| P2 | Peer（补全） | 30 | 补 parse/newPeer/异常（已有 PeerTest 覆盖 ~70%） | 90% |
| P3 | MessageFilter | 45 | Mock Ed25519PublicKey.verify 测 4 过滤路径 | 75% |
| P3 | PeersStorage | 35 | Mock Leveldb/codec 测 init/flush | 65% |
| P3 | GetBlockQuery | 40 | 参数化 clip 边界 | 95% |
| P3 | MessageLogger | 25 | 简单单测 enableMessageLog 开关 | 85% |
| P3 | Status | 20 | POJO getter | 100% |

**表：crypto 包 GrpcMpcCryptoEngine 补测策略表**

| 优先级 | 类 | 行数 | 已有测试 | 补测内容 | 预计覆盖率 |
|---|---|---|---|---|---|
| P0 | GrpcMpcCryptoEngine | 646 | GrpcMpcCryptoEngineTlsConfigTest（init TLS 分支） | Mock blockingStub 测 dkg/sign/aggregate/healthCheck 正常+StatusRuntimeException 路径；validateSessionId（UUID/自定义/非法）+validatePartyIndex（越界）参数化；toProto/fromProtoWithZeroization 零化；shutdown | 70% |

### 4.3 需创建的测试工具类

| 工具类 | 位置 | 职责 |
|---|---|---|
| `PeerTestFixture` | `nexus-core/src/test/java/org/nexus/p2p/` | 构造带固定 peerID 的 Peer、构造各 code 的 `NexusChainOuterClass.Message`、构造 Ed25519 KeyPair 的 self Peer |
| `MockPeerServerFactory` | 同上 | 用 Mockito 构造预置 peers/self 的 PeerServer mock，供 MerkleHandler/PeersManager/MessageFilter 测试 |
| `P2pMessageBuilder` | 同上 | 封装 `Util.buildMessage` 构造各类 Payload 入参，减少测试样板 |
| `InProcessGrpcChannel` | 同上 | 封装 `InProcessServerBuilder`/`InProcessChannelBuilder`，供 GRPCClient/PeerServer 集成测试 |
| `MockMpcCryptoStubFactory` | `nexus-signing-service/src/test/java/org/nexus/signing/mpc/crypto/` | 构造 `MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub` mock，预置 Dkg/Sign/Aggregate 响应 |

## 第5章 工作量估算

### 5.1 测试类数量

**表：新增测试类数量估算表**

| 模块 | 新增测试类 | 其中工具类 | 备注 |
|---|---|---|---|
| p2p 包 | 14 | 4 | 13 个业务类测试 + 4 个工具类（Peer/HostPort/Context 已有，补全 Peer） |
| crypto 包 | 1 | 1 | GrpcMpcCryptoEngine 完整测试 + MockMpcCryptoStubFactory |
| build.gradle 改动 | 2 | — | 两个模块加 jacoco excludes |
| **合计** | **17** | **5** | — |

### 5.2 预计覆盖率提升

**表：覆盖率提升预估表（排除生成代码后）**

| 包 | 现状 | 排除生成代码后 | 补测后 | 达 50%? |
|---|---|---|---|---|
| org.nexus.p2p（手写部分） | ~16%（仅 Peer/HostPort/Context） | ~16% | **~72%** | 是 |
| org.nexus.signing.mpc.crypto（含 GrpcMpcCryptoEngine） | DTO 已覆盖，Engine 部分 | DTO+Engine TLS 已覆盖 ~30% | **~70%** | 是 |
| org.nexus.signing.mpc.crypto.grpc（生成包） | 0% | **排除统计**（或通过 Engine 间接覆盖 stub 调用 ~15%） | 排除或 ~15% | 排除即不纳入；若纳入则需 in-process 集成测试且难达 50% |

> 结论：**排除生成代码 + 为 14 个 p2p 手写类 + GrpcMpcCryptoEngine 补测，两个模块的包级覆盖率均可达到 50% 以上**。生成包 `crypto/grpc` 本身建议从覆盖率统计中排除（行业惯例），若硬性要求该生成包达 50%，需用 in-process gRPC 集成测试，工作量翻倍且收益低，不推荐。

### 5.3 工时估算（参考）

| 工作项 | 估算 |
|---|---|
| build.gradle jacoco excludes（2 个模块） | 0.5 人时 |
| 测试工具类（5 个） | 4 人时 |
| PeersCache + PeersCacheWrapper 参数化测试 | 6 人时 |
| PeerServer + MerkleHandler Mock 测试 | 10 人时 |
| Util + Payload 测试 | 6 人时 |
| 其余 7 个 p2p 类测试 | 6 人时 |
| GrpcMpcCryptoEngine 完整测试 | 6 人时 |
| **合计** | **~38.5 人时（约 5 人日）** |

## 第6章 需要确认

1. **生成代码是否必须纳入覆盖率统计**：若团队门禁要求 `crypto/grpc` 生成包也达 50%，需追加 in-process gRPC 集成测试（额外 ~8 人时），且 8410 行 message 类全分支覆盖不现实。建议确认是否接受"生成代码 JaCoCo 排除"方案。
2. **PeerServer.startListening / GRPCClient.getChannel 是否纳入本次目标**：这两块真实建 gRPC Server/Channel，纯单测无法覆盖。建议确认是否接受用 `io.grpc.inprocess` 集成测试，或本次仅覆盖其可 Mock 的方法（onMessage/dial/buildMessage 等），startListening/getChannel 留给后续集成测试。
3. **nexus-core 的 `NexusChainGrpc.java`（242 行）性质**：文件位于 `src/main` 而非 `build/generated`，但内容为 protoc 生成风格。建议确认是否可安全从覆盖率排除（排除后不影响业务）。