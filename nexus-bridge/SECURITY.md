# NexusChain 跨链桥安全方案

> 设计日期: 2026-08-05
> 状态: 设计方案（待实现）

## 一、现状评估

### 已有基础
| 组件 | 状态 | 文件 |
|------|------|------|
| 桥服务接口 | ✅ 完整 | `BridgeService.java` — Lock/Mint/Burn/Unlock 四操作 |
| 桥配置 | ✅ 完整 | `BridgeConfig.java` — 多签阈值/时间锁/单笔上限/日限额/大额阈值 |
| 桥验证者接口 | ✅ 接口定义 | `BridgeValidator.java` — sign/verify/isActive/getWeight |
| 桥状态机 | ✅ 完整 | `BridgeState.ACTIVE ↔ PAUSED → EMERGENCY_STOP` |
| 多签阈值判断 | ✅ 静态方法 | `BridgeValidator.meetsThreshold(Set, BridgeConfig)` |

### 关键缺失
| 缺失项 | 影响 | 优先级 |
|--------|------|--------|
| 验证者私钥存储方案 | sign() 接口存在但私钥来源未定义 — 攻击面 | 🔴 P0 |
| 签名防重放 | sign() 调用的 payload 无 timestamp/nonce | 🔴 P0 |
| 日限额重置调度 | dailyUsed 无定时重置逻辑 — 桥迟早耗尽 | 🔴 P0 |
| 验证者集合动态治理 | validatorPublicKeys 是静态配置 | 🟡 P1 |
| 大额告警 | 超 largeAmount 的交易无主动通知 | 🟡 P1 |
| 桥资金审计 | 链上锁定/销毁总额 vs 铸造/解锁总额的自动对账 | 🟡 P1 |

---

## 二、分层安全架构

```
┌──────────────────────────────────────────────────────────┐
│                  NexusChain Bridge Security               │
├──────────────────────────────────────────────────────────┤
│                                                          │
│  L1 密钥管理层                                            │
│  ┌──────────┐ ┌──────────┐ ┌────────────┐              │
│  │HSM Adapter│ │Vault/KMS │ │File(dev)   │              │
│  │PKCS#11   │ │HashiCorp │ │AES-GCM enc │              │
│  └──────────┘ └──────────┘ └────────────┘              │
│         ↓ 私钥永不离开 vault，签名在 vault 内执行         │
│                                                          │
│  L2 多签聚合层                                            │
│  ┌─────────────────────────────────────────────┐        │
│  │ Sign → Collect(N of M) → Verify → Execute   │        │
│  │ payload = hash(chainId‖txHash‖amount‖       │        │
│  │   recipient‖timestamp‖nonce)               │        │
│  │ 防重放: ts ±5min + nonce Redis TTL          │        │
│  └─────────────────────────────────────────────┘        │
│                                                          │
│  L3 运行监控层                                            │
│  ┌─────────────────────────────────────────────┐        │
│  │ • 日限额使用率 > 80% → 告警                  │        │
│  │ • 签名拒绝率 > 阈值 → 告警                   │        │
│  │ • 大额跨链 (>50% dailyLimit) → 自动时间锁2x  │        │
│  │ • 验证者离线 > 15min → 告警，自动排除        │        │
│  │ • 多验证者离线致签名不足 → 自动 PAUSED       │        │
│  │ • 每日 00:00 重置 dailyUsed + 快照审计表     │        │
│  └─────────────────────────────────────────────┘        │
│                                                          │
│  L4 治理与生命周期                                        │
│  ┌─────────────────────────────────────────────┐        │
│  │  添加 → 活跃 → 暂停(offline) → 退役          │        │
│  │  密钥轮换: 新公钥上链→共识确认→旧密钥标记退役  │        │
│  │  至少保留 N+1 验证者防止签名能力降级          │        │
│  └─────────────────────────────────────────────┘        │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

---

## 三、P0 实现规格

### 3.1 KeyVault 抽象层

**新增文件**: `nexus-bridge/.../keyvault/KeyVault.java`

```java
public interface KeyVault {
    /** 签名。私钥操作完全在 vault 内完成，绝不外泄。 */
    String sign(String validatorId, byte[] payload);

    /** 获取公钥（可暴露）。 */
    String getPublicKey(String validatorId);

    /** vault 健康检查。 */
    boolean isAvailable();
}
```

**三种实现**：

| 实现 | 场景 | 私钥存储 |
|------|------|---------|
| `HsmKeyVault` | 生产环境 | HSM 硬件模块，PKCS#11 通信 |
| `VaultKeyVault` | 云部署 | HashiCorp Vault transit engine |
| `FileKeyVault` | 开发/测试 | 文件系统，AES-256-GCM 加密，密码从环境变量 `NEX_BRIDGE_KEY_PASSWORD` 注入 |

**FileKeyVault 密钥格式** (`validators/{id}.key.enc`):
```
AES-GCM nonce (12 bytes) || ciphertext || tag (16 bytes)
```
加密时用 Argon2id 从密码派生 256-bit AES key（复用 nexus-core 已有的 `ArgonManage`）。

### 3.2 签名防重放

**payload 规范**（修改 `BridgeService` 各操作调用 `sign()` 的传参）：

```
canonicalPayload = SHA-256(
    chainId       (8 bytes big-endian)
    || txHash     (32 bytes)
    || amount     (8 bytes big-endian)
    || recipient  (20 bytes)
    || timestamp  (8 bytes Unix epoch)
    || nonce      (8 bytes SecureRandom)
)
```

**验证端**（bridge 服务收到签名后）：
1. 检查 `|now - timestamp| < 300`（5 分钟窗口）— 过期拒绝
2. 检查 nonce 是否在 `redis SET NX bridge:nonce:{hex} TTL 300` 中已存在 — 存在则拒绝（重放）
3. 用验证者公钥验证签名 — 不匹配拒绝
4. 收集 N of M 签名后执行操作

### 3.3 日限额重置

**新增文件**: `nexus-bridge/.../scheduler/DailyLimitResetJob.java`

```java
@Component
public class DailyLimitResetJob {
    @Scheduled(cron = "0 0 0 * * ?")  // 每天 00:00
    public void resetDailyLimit() {
        BridgeStatus status = bridgeService.getStatus();
        // 快照昨天的用量到审计表
        dailyUsageRepository.save(new DailyUsage(
            LocalDate.now().minusDays(1),
            status.getDailyUsed()
        ));
        // 重置
        status.resetDailyUsed();
        log.info("Daily bridge limit reset. Yesterday: {} NEX",
            status.getYesterdayUsed());
    }
}
```

### 3.4 验证者治理集成

修改 `BridgeConfig` 的 validator 来源从静态配置改为监听 consortium 治理事件：

```
consortium 治理提案(添加/移除验证者)
    → PoA 共识确认
    → 事件广播 (NewValidatorSetEvent)
    → BridgeConfig 热更新 validatorPublicKeys + signatureThreshold
    → 新配置即时生效
```

---

## 四、紧急响应 SOP

| 事件 | 触发条件 | 自动响应 | 人工响应 |
|------|---------|---------|---------|
| 🔵 单验证者离线 | `healthCheck()` 返回 UNHEALTHY 持续 > 15min | 告警；若剩余活跃 ≥ threshold 则自动排除该验证者 | 检查节点状态 |
| 🟡 大额跨链 | `amount > largeAmountThreshold` | 时间锁周期 ×2；告警 | 人工确认后手动放行 |
| 🟡 日限额 80% | `dailyUsed > 0.8 * dailyLimit` | 告警；仅允许当前余额内的操作 | 评估是否需要临时提额 |
| 🔴 多验证者离线 | 活跃验证者数 < signatureThreshold | **自动 PAUSED**；全员告警 | 紧急恢复验证者节点 |
| 🔴 EMERGENCY_STOP | 任何验证者手动触发 | 所有操作禁止（仅 UNLOCK 在 PAUSED 阶段可用） | 安全事件应急 |

---

## 五、实现优先级与工作量

| # | 任务 | 优先级 | 工作量 | 依赖 |
|---|------|--------|--------|------|
| 1 | `KeyVault` 接口 + `FileKeyVault` 实现 | P0 | 1.0 人日 | — |
| 2 | 签名防重放（payload 规范化 + nonce 校验） | P0 | 0.5 人日 | #1 |
| 3 | `DailyLimitResetJob` + 审计表 | P0 | 0.5 人日 | — |
| 4 | `BridgeServiceImpl` 接入 KeyVault（替换 sign 空调用） | P0 | 1.0 人日 | #1, #2 |
| 5 | 告警规则 + Prometheus 指标 | P1 | 0.5 人日 | — |
| 6 | 验证者治理集成（consortium 事件监听） | P1 | 1.5 人日 | — |
| 7 | `HsmKeyVault` 实现 | P2 | 1.0 人日 | #1 |
| 8 | 桥资金审计对账 | P2 | 1.0 人日 | — |
| | **P0 合计** | | **3.0 人日** | |
| | **全部合计** | | **7.0 人日** | |