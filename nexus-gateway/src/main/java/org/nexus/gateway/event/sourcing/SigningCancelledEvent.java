package org.nexus.gateway.event.sourcing;

import java.math.BigDecimal;

/**
 * 签名取消事件（TCC Cancel 阶段补偿事件）。
 *
 * <p>当 SigningTccAction Cancel 阶段执行（Seata 全局事务回滚触发）时产出本事件。
 * Cancel 阶段释放了 Try 阶段预锁定的 nonce，本事件用于：
 * <ul>
 *   <li>审计：记录"签名已取消，nonce 已释放"事实，便于事后排查全局回滚原因</li>
 *   <li>监控：消费方统计 TCC Cancel 频率，高频 Cancel 触发告警（可能 nonce 池配置问题）</li>
 *   <li>对账：与 Seata 事务日志关联，验证 Cancel 与 Try 一一对应</li>
 * </ul>
 *
 * <p>本事件不参与业务决策（Cancel 已由 Seata 框架保证幂等），仅供审计/监控消费，
 * 事件产出失败不影响 Cancel 成功（事件存储层故障由 Kafka 重试/DLQ 兜底）。
 *
 * <p>关联 ADR-027：TCC Cancel + 事件补偿协调，Cancel 先释放 nonce 后产出本事件，
 * 避免事件先到但 nonce 未释放的虚假状态。
 *
 * @since Phase 3 - P3-T7 Seata 与事件溯源协调
 */
public class SigningCancelledEvent extends PaymentEvent {

    private static final long serialVersionUID = 1L;

    /** Seata 全局事务 ID（XID），用于关联 Try/Confirm/Cancel 三阶段 */
    private final String globalTxId;
    /** 转出方公钥（Try 阶段写入） */
    private final String fromPubkey;
    /** 转入方公钥哈希（Try 阶段写入） */
    private final String toPubkeyHash;
    /** 转账金额（Try 阶段写入） */
    private final BigDecimal amount;
    /** 被释放的 nonce（Try 阶段锁定，Cancel 阶段释放） */
    private final Long nonce;
    /** 签名方地址（由 fromPubkey 推导） */
    private final String address;
    /** Cancel 原因（如 "GLOBAL_TX_ROLLBACK"、"TRY_FAILED"） */
    private final String cancelReason;

    public SigningCancelledEvent(String aggregateId, long version, String globalTxId,
                                 String fromPubkey, String toPubkeyHash, BigDecimal amount,
                                 Long nonce, String address, String cancelReason) {
        super(aggregateId, version);
        this.globalTxId = globalTxId;
        this.fromPubkey = fromPubkey;
        this.toPubkeyHash = toPubkeyHash;
        this.amount = amount;
        this.nonce = nonce;
        this.address = address;
        this.cancelReason = cancelReason;
    }

    public SigningCancelledEvent(String eventId, String aggregateId, java.time.Instant timestamp, long version,
                                 String globalTxId, String fromPubkey, String toPubkeyHash, BigDecimal amount,
                                 Long nonce, String address, String cancelReason) {
        super(eventId, aggregateId, timestamp, version);
        this.globalTxId = globalTxId;
        this.fromPubkey = fromPubkey;
        this.toPubkeyHash = toPubkeyHash;
        this.amount = amount;
        this.nonce = nonce;
        this.address = address;
        this.cancelReason = cancelReason;
    }

    @Override
    public String getEventType() {
        return "SIGNING_CANCELLED";
    }

    public String getGlobalTxId() {
        return globalTxId;
    }

    public String getFromPubkey() {
        return fromPubkey;
    }

    public String getToPubkeyHash() {
        return toPubkeyHash;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Long getNonce() {
        return nonce;
    }

    public String getAddress() {
        return address;
    }

    public String getCancelReason() {
        return cancelReason;
    }
}