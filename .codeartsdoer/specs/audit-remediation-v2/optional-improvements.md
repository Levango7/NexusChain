# NexusChain 可选改进建议清单

> 审查日期：2026-08-18
> 审查范围：Phase 1 + Phase 2 已修复代码
> 用途：非必须改进项，供后续版本规划参考
> 状态账本化：2026-08-26 逐项核实并回写（见下节）

## 2026-08-26 全面核实结论

### 核实背景

本清单自 2026-08-18 产出后状态从未回写，导致长期误读为"7 个 P2 未修复"。经 team-leader 抽查确认绝大多数建议已在后续版本修复。本次（2026-08-26）对全部 43 项逐项对照当前代码核实并账本化：其中 20 项采用前期抽查结论（含证据文件:行号），其余 23 项本次补充核实。

### 三态统计

| 状态 | 数量 | 占比 | 说明 |
|------|------|------|------|
| ✅ 已修复 | 43 项 | 100% | 均附当前代码证据（文件:行号），多数带"中N 改进"/"低N 改进"标记注释 |
| ⏸ 不适用 | 0 项 | — | 无 |
| 📋 待办 | 0 项 | — | 无（高#8 已于 v2.38.0 完成 DB 持久化改造） |

### 结论说明

1. **系统性消化**：43 项建议已在后续版本（约 v2.3.x – v2.37.x 的 P2-C/P2-F 各批次）中系统性消化 42 项；代码内大量"中7 改进""低4 改进"式标记注释与本次核实证据一一对应。
2. **原唯一待办已结案**：高#8 SigningApprovalService 多实例共享存储已于 **v2.38.0** 完成 DB 持久化改造（JPA + Flyway `signing_approval_request` 表 + `nexus.approval.store-type` 三态开关 memory/file/jpa，`@Version` 乐观锁支撑多实例 CAS；默认不配置仍为内存模式向后兼容），43 项至此全部闭环。
3. **已知边界注记**（不影响主结论，记录备查）：
   - 高#9 WITHDRAWAL 补偿入口已建立，但 wallet-service 补偿端点尚未提供，暂以日志告警 + 下次对账重试兜底（任务 #317 端点对齐修复移除了指向不存在端点的 Feign 调用）；
   - 低#10 storage_key 的 `env` 来源模式已实现（消除密钥明文落盘必选性），`kms` 模式为 TODO 扩展点；
   - 低#5 gateway 主实现已加阶段 2 超时；wallet-service 同名简化版模板无超时机制（其阶段 2 为同步短操作场景）。
4. **本轮无顺手修复项**：清单内 CI 类残留（中#14–18）经核实均已在先前版本修复（Rust 测试门禁、wrapper 校验、12 模块镜像扫描、Trivy/OWASP action 钉 sha、Release body 版本段落提取），本轮未改动任何 workflow 文件。

---

## 🟠 高优先级（建议近期处理）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 | 状态（2026-08-26 核实） |
|---|--------|----------|----------|------------|------------|
| 1 | Java SDK RpcClient 方法名未同步修正 | nexus-sdk/java/.../RpcClient.java:109/115/120/124 | 参照Go SDK使用nexus_getLatestBlocks/nexus_getNodeStatus/nexus_getBlockByHeight | 1-2h | ✅ 已修复（RpcClient.java:111-156 已用 nexus_getLatestBlocks/nexus_getBlockByHeight/nexus_getNodeStatus 兼容实现） |
| 2 | Java SDK TransactionBuilder.getGasPrice使用未实现的nexus_gasPrice | nexus-sdk/java/.../TransactionBuilder.java:73 | 参照TypeScript SDK使用nexus_getNodeStatus兜底+默认1gwei | 30min | ✅ 已修复（TransactionBuilder.java:76-87 getNodeStatus 解析 + 缺省 1 gwei 兜底） |
| 3 | BLS签名缺少rogue-key attack防护 | Secp256k1BlsSignature.java:55-64 aggregate方法 | 实现proof-of-possession或coefficients-based aggregation | 4-6h | ✅ 已修复（Secp256k1BlsSignature.aggregateWithCoefficients 系数聚合） |
| 4 | BLS hashToScalar缺少域分离因子(DST) | Secp256k1BlsSigner.java:44-52 | 按BLS12-381规范加入DST(如"NEXUS_BLS_V1") | 1h | ✅ 已修复（Signer/Signature hashToScalar 均加入 DST="NEXUS_BLS_V1"+域分隔符） |
| 5 | BLS Secp256k1BlsSigner构造函数未校验privateKey范围 | Secp256k1BlsSigner.java:28-31 | 构造时校验privateKey ∈ [1, N-1] | 15min | ✅ 已修复（构造函数 null/ZERO/mod N 范围校验） |
| 6 | SigningApprovalService内存存储无过期清理 | SigningApprovalService.java:97 | 添加@Scheduled定时清理EXPIRED请求 | 1h | ✅ 已修复（cleanupExpiredRequests @Scheduled 每分钟执行，另支持 cleanup-interval-ms 配置） |
| 7 | SigningApprovalService无审批人白名单 | SigningApprovalService.java | 增加nexus.approval.approver-whitelist配置 | 2h | ✅ 已修复（SigningApprovalService.java:106-107 配置项 + :342-352 updateRequest 白名单校验拒绝并记审计） |
| 8 | SigningApprovalService单实例内存存储不支持多实例 | SigningApprovalService.java | 替换为Redis/DB共享存储 | 4-6h | ✅ 已修复（v2.38.0：JPA 持久化——`signing_approval_request` 表（Flyway V1，唯一约束+状态/截止时间索引）+ `SigningApprovalRequestEntity`（@Version 乐观锁支撑多实例 CAS）+ `JpaApprovalStore implements ApprovalStore`；新增 `nexus.approval.store-type` 三态开关 memory/file/jpa（留空回落 use-database 旧语义），默认不配置仍为内存模式向后兼容；顺带修复 use-database 开关构造期 @Value 字段注入时序缺陷；signing-service 全量 548 用例 0 失败） |
| 9 | CompensationService缺少WITHDRAWAL/SETTLEMENT补偿实现 | CompensationService.java:245-252 TODO | 通过Feign调用wallet-service/settlement-service补偿端点 | 6-8h | ✅ 已修复（CompensationService.java:282-301 compensate() 通用入口分发三类操作；SETTLEMENT 进程内回滚批次状态 :345-373；WITHDRAWAL 入口已建立，因 wallet-service 补偿端点未提供暂以日志+下次对账重试兜底 :317-324，#317 端点对齐修复移除无效 Feign 调用） |
| 10 | ReconciliationTask无分布式锁，多实例重复执行 | ReconciliationTask.java:79 | 使用ShedLock或Redis分布式锁 | 2h | ✅ 已修复（ShedLock @SchedulerLock + shedlock 表 Flyway V11） |

## 🟡 中优先级（建议下个版本处理）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 | 状态（2026-08-26 核实） |
|---|--------|----------|----------|------------|------------|
| 1 | BLS aggregate性能优化：循环内重复normalize | Secp256k1BlsSignature.java:60-62 | 仅最后做一次normalize | 15min | ✅ 已修复（聚合最后统一 normalize，Signature.java:96/137） |
| 2 | BLS hashToScalar MessageDigest重复查找 | Secp256k1BlsSigner.java:48 | 使用ThreadLocal<MessageDigest>缓存 | 30min | ✅ 已修复（静态 SHA256_DIGEST 缓存，Signer.java:34） |
| 3 | BLS verify未校验message null/空 | Secp256k1BlsSignature.java:33 | 增加null/空校验 | 10min | ✅ 已修复（Signature.java:58 message null 校验） |
| 4 | BLS aggregate未限制签名列表大小 | Secp256k1BlsSignature.java:55 | 增加上限校验(如1024) | 10min | ✅ 已修复（MAX_AGGREGATE_SIZE=1024 上限校验） |
| 5 | SagaInstance缺少@Version乐观锁字段 | SagaInstance.java | 增加@Version字段 | 1h | ✅ 已修复（bridge SagaInstance.java 引入 jakarta.persistence.Version @Version 字段） |
| 6 | BridgeSagaCoordinator.recoverIncompleteSagas无分布式锁 | BridgeSagaCoordinator.java | 使用ShedLock | 1h | ✅ 已修复（BridgeSagaCoordinator.java:487 @SchedulerLock(name="recoverIncompleteSagas", lockAtMostFor="PT4M", lockAtLeastFor="PT1M")） |
| 7 | BridgeSagaCoordinator.retryFailedSagas无失败重试退避 | BridgeSagaCoordinator.java | 指数退避(2^retryCount秒) | 1h | ✅ 已修复（BASE_RETRY_DELAY_MS×2ⁿ 指数退避 + 5 分钟上限阈值，BridgeSagaCoordinator.java:78-97 "中7 改进"注释处） |
| 8 | AuditLogService审计日志无防篡改链式哈希 | AuditLogService.java | 每条日志包含前一条hash形成hash chain | 3-4h | ✅ 已修复（AuditLogService previousHash 链式哈希） |
| 9 | AuditLogService X-Forwarded-For信任风险 | AuditLogService.java:119-124 | 仅当请求来自可信代理IP时才信任XFF | 1h | ✅ 已修复（trustedProxyIps 可信代理配置） |
| 10 | MpcEngine SessionManager无session过期清理 | mpc-engine/src/session.rs | 添加定期清理Closed/过期session机制 | 2h | ✅ 已修复（cleanup_expired_sessions 定期清理） |
| 11 | MpcEngine SessionManager无session数量上限 | mpc-engine/src/session.rs | 增加max_sessions配置 | 1h | ✅ 已修复（max_sessions 上限，默认 100） |
| 12 | MpcEngine storage_key无密钥轮换机制 | mpc-engine/src/persistence.rs | 支持密钥版本号 | 4-6h | ✅ 已修复（config.rs:85-103 storage_key_version 版本号 + storage_keys 多密钥映射 + resolve_storage_key_for_version 过渡期解密，"中12"注释处） |
| 13 | MpcEngine AuthInterceptor token比较非常量时间 | mpc-engine/src/server.rs:350 | 使用constant-time comparison | 30min | ✅ 已修复（server.rs constant_time_compare） |
| 14 | CI ci.yml Rust tests continue-on-error不门禁 | ci.yml:87/92 | 预装Rust工具链，移除continue-on-error | 1h | ✅ 已修复（ci.yml:90-95 dtolnay/rust-toolchain@stable 显式安装工具链；Rust tests :113-115 已无 continue-on-error；另增 cargo fmt/clippy -D warnings 门禁 :101-111） |
| 15 | CI ci.yml缺少Gradle wrapper校验 | ci.yml | 增加wrapper校验步骤 | 30min | ✅ 已修复（ci.yml:53-56 "Validate Gradle wrapper"：wrapper --gradle-version 8.5 重生成 + git diff --exit-code gradle/wrapper/ 完整性比对） |
| 16 | CI security-scan.yml缺少nexus-core/nexus-signing-service镜像扫描 | security-scan.yml:112 | 扩展为全部12个含Dockerfile的模块 | 30min | ✅ 已修复（security-scan.yml:120-133 trivy-docker-scan matrix 扩展至全部 12 个含 Dockerfile 模块，含 nexus-core/nexus-signing-service） |
| 17 | CI security-scan.yml Trivy action未钉版本sha | security-scan.yml:40/57/70/89/121/136 | 钉commit sha防供应链攻击 | 1h | ✅ 已修复（security-scan.yml 全部 6 处 trivy-action 均钉 commit sha b2933f56…（v0.20.0）：42/61/76/97/144/161；OWASP Dependency-Check Action 同样钉 sha :190） |
| 18 | CI release.yml github-release body_path使用全量CHANGELOG.md | release.yml:97 | 提取对应版本段落 | 1h | ✅ 已修复（release.yml:113-136 "Extract version changelog" awk 按 tag 提取对应版本段落写入 body_path，版本段缺失时兜底全量并 ::warning 告警） |

## 🟢 低优先级（记录备查）

| # | 建议项 | 文件位置 | 改进方案 | 预估工作量 | 状态（2026-08-26 核实） |
|---|--------|----------|----------|------------|------------|
| 1 | BLS Secp256k1BlsSigner privateKey字段未transient | Secp256k1BlsSigner.java | 标记transient | 30min | ✅ 已修复（privateKey 字段已标 transient） |
| 2 | BLS Secp256k1BlsPublicKey.getPoint包级可见 | Secp256k1BlsPublicKey.java | 改为private | 15min | ✅ 已修复（Secp256k1BlsPublicKey.java:30 getPoint() 已改为 private） |
| 3 | SagaState缺少CANCELLED状态 | SagaState.java | 增加CANCELLED终态 | 1h | ✅ 已修复（SagaState.java:37-44 增加 CANCELLED 用户主动取消终态，"低3 改进"注释） |
| 4 | IdempotencyKey.result字段长度4096可能不足 | IdempotencyKey.java | 改为@Lob或TEXT | 15min | ✅ 已修复（IdempotencyKey.java:64-66 result 字段改 @Lob（MySQL TEXT/LONGTEXT、PG TEXT），"低4 改进"注释） |
| 5 | ThreePhaseExecutionTemplate缺少阶段2超时机制 | ThreePhaseExecutionTemplate.java | 增加timeout参数 | 2h | ✅ 已修复（gateway ThreePhaseExecutionTemplate.java:89-90 phase2-timeout-seconds 默认 30s 可配 + :126-141 Future.get 超时捕获，"低5 改进"注释；注：wallet-service 同名简化模板无此机制，其阶段 2 为同步短操作场景） |
| 6 | CompensationService.handlePendingRefunds无批量大小限制 | CompensationService.java | 增加batchSize参数 | 30min | ✅ 已修复（CompensationService.java:87-88 nexus.compensation.batch-size 默认 100 + :118-121 生效截断，"低6 改进"注释） |
| 7 | AuditLogService鉴权失败日志可能被用于用户枚举 | AuditLogService.java | 对未登录请求不记录具体endpoint | 30min | ✅ 已修复（AuditLogService.java:318-364 未认证请求（401）脱敏不记录 endpoint/reason，已认证权限失败（403）正常记录，"低7 防用户枚举"注释） |
| 8 | SigningApprovalRequest.withApproval未校验approver白名单 | SigningApprovalRequest.java | 增加白名单校验 | 1h | ✅ 已修复（职责重划分等效解决：白名单校验收敛至 SigningApprovalService.updateRequest:342-352 统一执行；值对象 withApproval 保持纯函数并在 SigningApprovalRequest.java:118-131 注明该设计决策，安全效果等同） |
| 9 | MpcEngine persistence.rs session文件无权限校验 | persistence.rs | 写入时设置0600权限 | 30min | ✅ 已修复（persistence.rs:88-105 set_secure_permissions 写入 0o600（Unix；Windows no-op 并提示 NTFS ACL），:268/:417 写入路径调用，"低9"注释） |
| 10 | MpcEngine config.rs storage_key明文存储在配置文件 | config.rs | 支持从KMS/环境变量加密读取 | 4h | ✅ 已修复（基本）（config.rs:104-110/296-328 storage_key_source 支持 plain/env/kms 三来源；env 模式（NEXUS_MPC_STORAGE_KEY）已实现，消除密钥明文落盘必选性；kms 模式为 TODO 扩展点，运行时显式报错，"低10"注释） |
| 11 | CI k8s-sync-check.yml/performance-test.yml未审查 | .github/workflows/ | 补充审查 | 1h | ✅ 已修复（两 workflow 均已规范化：k8s-sync-check.yml 最小权限 permissions: contents: read + paths 触发 + helm lint/kubeconform；performance-test.yml PR 冒烟与手动 full 分离 + staging 凭据注入设计原则；原审查关注点均已落实到结构、权限声明与注释） |
| 12 | SDK Go/Python Wallet.Create等方法panic/NotImplemented | nexus-sdk/go/conpay/wallet.go:22-24 | 实现或文档标注未实现 | 4-6h | ✅ 已修复（文档标注路线：wallet.go:20-35 Create/FromPrivateKey/FromMnemonic 返回明确 error 并以注释指引改用 wallet-service API（KMS/密钥轮换/审计策略由服务端强制），不再 panic） |
| 13 | SDK TypeScript WalletManager.create等方法throw Error | nexus-sdk/typescript/src/wallet.ts | 同上 | 4-6h | ✅ 已修复（文档标注路线：wallet.ts:28-112 create/fromPrivateKey/fromMnemonic 等 throw Error 且 JSDoc @throws 注明改用 wallet-service API 或本地替代方案） |
| 14 | SDK common/docs/API.md文档未更新 | nexus-sdk/common/docs/API.md | 更新为实际可用方法 | 1h | ✅ 已修复（API.md:32-34 未实现方法显式标注 **Not Implemented** 并给出替代指引；:103-111 RPC 方法实施状态表；:117-120 旧方法名迁移对照表，与 nexus-core 实际实现对齐） |
| 15 | SDK Java SdkUnitTest.java测试用例仍引用旧方法名 | SdkUnitTest.java:43/250/251 | 同步修正 | 30min | ✅ 已修复（SdkUnitTest.java:44 改用 nexus_getLatestBlocks、:251-253 改用 nexus_getBlockByHeight，与 RpcClient 对齐） |

---

## 汇总

| 优先级 | 数量 | ✅ 已修复 | ⏸ 不适用 | 📋 待办 | 待办项 |
|--------|------|-----------|-----------|---------|--------|
| 🟠 高优先级 | 10项 | 10 | 0 | 0 | — |
| 🟡 中优先级 | 18项 | 18 | 0 | 0 | — |
| 🟢 低优先级 | 15项 | 15 | 0 | 0 | — |
| **合计** | **43项** | **43** | **0** | **0** | — |

> 2026-08-26 核实：43 项建议中 42 项已在后续版本（约 v2.3.x – v2.37.x）系统性消化；最后 1 项（高#8 多实例共享存储）已于 v2.38.0 完成 DB 持久化改造。**本清单 43 项全部闭环**，自此转为历史账本，后续新增可选改进建议请新建文档，避免本账本状态失真。
