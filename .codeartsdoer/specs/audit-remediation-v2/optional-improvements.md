# NexusChain 可选改进建议清单

> 审查日期：2026-08-18
> 审查范围：Phase 1 + Phase 2 已修复代码
> 用途：非必须改进项，供后续版本规划参考

## 🟠 高优先级（建议近期处理）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 |
|---|--------|----------|----------|------------|
| 1 | Java SDK RpcClient 方法名未同步修正 | nexus-sdk/java/.../RpcClient.java:109/115/120/124 | 参照Go SDK使用nexus_getLatestBlocks/nexus_getNodeStatus/nexus_getBlockByHeight | 1-2h |
| 2 | Java SDK TransactionBuilder.getGasPrice使用未实现的nexus_gasPrice | nexus-sdk/java/.../TransactionBuilder.java:73 | 参照TypeScript SDK使用nexus_getNodeStatus兜底+默认1gwei | 30min |
| 3 | BLS签名缺少rogue-key attack防护 | Secp256k1BlsSignature.java:55-64 aggregate方法 | 实现proof-of-possession或coefficients-based aggregation | 4-6h |
| 4 | BLS hashToScalar缺少域分离因子(DST) | Secp256k1BlsSigner.java:44-52 | 按BLS12-381规范加入DST(如"NEXUS_BLS_V1") | 1h |
| 5 | BLS Secp256k1BlsSigner构造函数未校验privateKey范围 | Secp256k1BlsSigner.java:28-31 | 构造时校验privateKey ∈ [1, N-1] | 15min |
| 6 | SigningApprovalService内存存储无过期清理 | SigningApprovalService.java:97 | 添加@Scheduled定时清理EXPIRED请求 | 1h |
| 7 | SigningApprovalService无审批人白名单 | SigningApprovalService.java | 增加nexus.approval.approver-whitelist配置 | 2h |
| 8 | SigningApprovalService单实例内存存储不支持多实例 | SigningApprovalService.java | 替换为Redis/DB共享存储 | 4-6h |
| 9 | CompensationService缺少WITHDRAWAL/SETTLEMENT补偿实现 | CompensationService.java:245-252 TODO | 通过Feign调用wallet-service/settlement-service补偿端点 | 6-8h |
| 10 | ReconciliationTask无分布式锁，多实例重复执行 | ReconciliationTask.java:79 | 使用ShedLock或Redis分布式锁 | 2h |

## 🟡 中优先级（建议下个版本处理）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 |
|---|--------|----------|----------|------------|
| 1 | BLS aggregate性能优化：循环内重复normalize | Secp256k1BlsSignature.java:60-62 | 仅最后做一次normalize | 15min |
| 2 | BLS hashToScalar MessageDigest重复查找 | Secp256k1BlsSigner.java:48 | 使用ThreadLocal<MessageDigest>缓存 | 30min |
| 3 | BLS verify未校验message null/空 | Secp256k1BlsSignature.java:33 | 增加null/空校验 | 10min |
| 4 | BLS aggregate未限制签名列表大小 | Secp256k1BlsSignature.java:55 | 增加上限校验(如1024) | 10min |
| 5 | SagaInstance缺少@Version乐观锁字段 | SagaInstance.java | 增加@Version字段 | 1h |
| 6 | BridgeSagaCoordinator.recoverIncompleteSagas无分布式锁 | BridgeSagaCoordinator.java | 使用ShedLock | 1h |
| 7 | BridgeSagaCoordinator.retryFailedSagas无失败重试退避 | BridgeSagaCoordinator.java | 指数退避(2^retryCount秒) | 1h |
| 8 | AuditLogService审计日志无防篡改链式哈希 | AuditLogService.java | 每条日志包含前一条hash形成hash chain | 3-4h |
| 9 | AuditLogService X-Forwarded-For信任风险 | AuditLogService.java:119-124 | 仅当请求来自可信代理IP时才信任XFF | 1h |
| 10 | MpcEngine SessionManager无session过期清理 | mpc-engine/src/session.rs | 添加定期清理Closed/过期session机制 | 2h |
| 11 | MpcEngine SessionManager无session数量上限 | mpc-engine/src/session.rs | 增加max_sessions配置 | 1h |
| 12 | MpcEngine storage_key无密钥轮换机制 | mpc-engine/src/persistence.rs | 支持密钥版本号 | 4-6h |
| 13 | MpcEngine AuthInterceptor token比较非常量时间 | mpc-engine/src/server.rs:350 | 使用constant-time comparison | 30min |
| 14 | CI ci.yml Rust tests continue-on-error不门禁 | ci.yml:87/92 | 预装Rust工具链，移除continue-on-error | 1h |
| 15 | CI ci.yml缺少Gradle wrapper校验 | ci.yml | 增加wrapper校验步骤 | 30min |
| 16 | CI security-scan.yml缺少nexus-core/nexus-signing-service镜像扫描 | security-scan.yml:112 | 扩展为全部12个含Dockerfile的模块 | 30min |
| 17 | CI security-scan.yml Trivy action未钉版本sha | security-scan.yml:40/57/70/89/121/136 | 钉commit sha防供应链攻击 | 1h |
| 18 | CI release.yml github-release body_path使用全量CHANGELOG.md | release.yml:97 | 提取对应版本段落 | 1h |

## 🟢 低优先级（记录备查）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 |
|---|--------|----------|----------|------------|
| 1 | BLS Secp256k1BlsSigner privateKey字段未transient | Secp256k1BlsSigner.java | 标记transient | 30min |
| 2 | BLS Secp256k1BlsPublicKey.getPoint包级可见 | Secp256k1BlsPublicKey.java | 改为private | 15min |
| 3 | SagaState缺少CANCELLED状态 | SagaState.java | 增加CANCELLED终态 | 1h |
| 4 | IdempotencyKey.result字段长度4096可能不足 | IdempotencyKey.java | 改为@Lob或TEXT | 15min |
| 5 | ThreePhaseExecutionTemplate缺少阶段2超时机制 | ThreePhaseExecutionTemplate.java | 增加timeout参数 | 2h |
| 6 | CompensationService.handlePendingRefunds无批量大小限制 | CompensationService.java | 增加batchSize参数 | 30min |
| 7 | AuditLogService鉴权失败日志可能被用于用户枚举 | AuditLogService.java | 对未登录请求不记录具体endpoint | 30min |
| 8 | SigningApprovalRequest.withApproval未校验approver白名单 | SigningApprovalRequest.java | 增加白名单校验 | 1h |
| 9 | MpcEngine persistence.rs session文件无权限校验 | persistence.rs | 写入时设置0600权限 | 30min |
| 10 | MpcEngine config.rs storage_key明文存储在配置文件 | config.rs | 支持从KMS/环境变量加密读取 | 4h |
| 11 | CI k8s-sync-check.yml/performance-test.yml未审查 | .github/workflows/ | 补充审查 | 1h |
| 12 | SDK Go/Python Wallet.Create等方法panic/NotImplemented | nexus-sdk/go/conpay/wallet.go:22-24 | 实现或文档标注未实现 | 4-6h |
| 13 | SDK TypeScript WalletManager.create等方法throw Error | nexus-sdk/typescript/src/wallet.ts | 同上 | 4-6h |
| 14 | SDK common/docs/API.md文档未更新 | nexus-sdk/common/docs/API.md | 更新为实际可用方法 | 1h |
| 15 | SDK Java SdkUnitTest.java测试用例仍引用旧方法名 | SdkUnitTest.java:43/250/251 | 同步修正 | 30min |

---

## 汇总

| 优先级 | 数量 | 预估总工作量 |
|--------|------|------------|
| 🟠 高优先级 | 10项 | ~30-40h |
| 🟡 中优先级 | 18项 | ~25-35h |
| 🟢 低优先级 | 15项 | ~20-30h |
| **合计** | **43项** | **~75-105h** |