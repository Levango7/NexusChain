package org.nexus.gateway.event.sourcing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 支付聚合根（事件溯源模式）。
 *
 * <p>聚合根封装支付领域的状态与行为，所有状态变更通过 {@link #apply(PaymentEvent)} 应用事件产生，
 * 不直接修改字段。聚合根可从空状态出发重放历史事件序列重建当前状态。
 *
 * <p>状态机：
 * <pre>
 *   [初始] --PaymentCreatedEvent-->        CREATED
 *   CREATED --PaymentProcessingEvent-->    PROCESSING
 *   PROCESSING --PaymentSucceededEvent-->  SUCCEEDED (终态)
 *   PROCESSING --PaymentFailedEvent-->     FAILED    (终态)
 *   SUCCEEDED --PaymentRefundedEvent-->    REFUNDED  (终态)
 * </pre>
 *
 * <p>状态枚举与 {@code PaymentOrder.OrderStatus} 区分：
 * <ul>
 *   <li>本聚合根用 {@code CREATED} 表达"已创建待支付"，对应 {@code PaymentOrder.OrderStatus#PENDING}</li>
 *   <li>本聚合根用 {@code PROCESSING} 表达"支付中"，对应 {@code PaymentOrder.OrderStatus#PAYING}</li>
 *   <li>本聚合根用 {@code SUCCEEDED} 表达"已支付"，对应 {@code PaymentOrder.OrderStatus#PAID}</li>
 * </ul>
 *
 * <p>用法：
 * <pre>
 *   PaymentAggregate agg = PaymentAggregate.replay(events);
 *   agg.processing(chainTxHash, "BROADCAST");
 *   eventStore.append(agg.peekNewEvents());
 * </pre>
 *
 * @since Phase 3 - P3-T3 事件溯源 + CQRS
 */
public class PaymentAggregate {

    /** 聚合根状态枚举（事件溯源视角） */
    public enum State {
        /** 初始（未应用任何事件） */
        INITIAL,
        /** 已创建（应用 PaymentCreatedEvent） */
        CREATED,
        /** 处理中（应用 PaymentProcessingEvent） */
        PROCESSING,
        /** 已成功（应用 PaymentSucceededEvent，终态） */
        SUCCEEDED,
        /** 已失败（应用 PaymentFailedEvent，终态） */
        FAILED,
        /** 已退款（应用 PaymentRefundedEvent，终态） */
        REFUNDED
    }

    /** 聚合根 ID（支付订单 ID 字符串形式） */
    private final String aggregateId;

    /** 当前状态 */
    private State state = State.INITIAL;
    /** 当前版本号（已应用事件数；0 表示未应用任何事件） */
    private long version = 0L;

    // 业务字段（由事件投影得到）
    private Long merchantId;
    private String orderNo;
    private BigDecimal amount;
    private String tokenSymbol;
    private String payerAddress;
    private String payeeAddress;
    private String chainTxHash;
    private BigDecimal settledAmount;
    private Instant paidAt;
    private String failureCode;
    private String failureMessage;
    private String refundNo;
    private BigDecimal refundAmount;
    private String refundChainTxHash;
    private String refundReason;

    /** 本次聚合根操作新产出的事件（未持久化） */
    private final List<PaymentEvent> newEvents = new ArrayList<>();

    private PaymentAggregate(String aggregateId) {
        this.aggregateId = aggregateId;
    }

    /**
     * 创建一个空聚合根（用于从事件流重放）。
     *
     * @param aggregateId 聚合根 ID
     * @return 空聚合根实例
     */
    public static PaymentAggregate empty(String aggregateId) {
        return new PaymentAggregate(aggregateId);
    }

    /**
     * 从事件流重放聚合根状态。
     *
     * <p>从空状态出发，依次应用每个事件，最终得到聚合根当前状态。
     * 应用过程中不会产出新事件（{@link #peekNewEvents()} 返回空列表）。
     *
     * @param aggregateId 聚合根 ID
     * @param events      事件序列（按版本号升序）
     * @return 重放后的聚合根
     */
    public static PaymentAggregate replay(String aggregateId, List<PaymentEvent> events) {
        PaymentAggregate agg = empty(aggregateId);
        if (events == null) {
            return agg;
        }
        for (PaymentEvent event : events) {
            agg.applyRehydrated(event);
        }
        return agg;
    }

    // ============ 命令方法（产出事件） ============

    /**
     * 创建支付命令：产出 {@link PaymentCreatedEvent}。
     *
     * @throws IllegalStateException 当聚合根非 INITIAL 状态
     */
    public PaymentCreatedEvent create(Long merchantId, String orderNo, BigDecimal amount,
                                      String tokenSymbol, String payerAddress, String payeeAddress) {
        if (state != State.INITIAL) {
            throw new IllegalStateException("Cannot create payment in state: " + state);
        }
        long nextVersion = version + 1;
        PaymentCreatedEvent event = new PaymentCreatedEvent(
                aggregateId, nextVersion, merchantId, orderNo, amount, tokenSymbol, payerAddress, payeeAddress);
        apply(event);
        newEvents.add(event);
        return event;
    }

    /**
     * 进入处理中命令：产出 {@link PaymentProcessingEvent}。
     *
     * @throws IllegalStateException 当聚合根非 CREATED 状态
     */
    public PaymentProcessingEvent processing(String chainTxHash, String reason) {
        if (state != State.CREATED) {
            throw new IllegalStateException("Cannot start processing in state: " + state);
        }
        long nextVersion = version + 1;
        PaymentProcessingEvent event = new PaymentProcessingEvent(
                aggregateId, nextVersion, chainTxHash, reason);
        apply(event);
        newEvents.add(event);
        return event;
    }

    /**
     * 支付成功命令：产出 {@link PaymentSucceededEvent}。
     *
     * @throws IllegalStateException 当聚合根非 PROCESSING 状态
     */
    public PaymentSucceededEvent succeed(String chainTxHash, BigDecimal settledAmount, Instant paidAt) {
        if (state != State.PROCESSING) {
            throw new IllegalStateException("Cannot succeed in state: " + state);
        }
        long nextVersion = version + 1;
        PaymentSucceededEvent event = new PaymentSucceededEvent(
                aggregateId, nextVersion, chainTxHash, settledAmount, paidAt);
        apply(event);
        newEvents.add(event);
        return event;
    }

    /**
     * 支付失败命令：产出 {@link PaymentFailedEvent}。
     *
     * @throws IllegalStateException 当聚合根非 CREATED / PROCESSING 状态
     */
    public PaymentFailedEvent fail(String failureCode, String failureMessage) {
        if (state != State.CREATED && state != State.PROCESSING) {
            throw new IllegalStateException("Cannot fail in state: " + state);
        }
        long nextVersion = version + 1;
        PaymentFailedEvent event = new PaymentFailedEvent(
                aggregateId, nextVersion, failureCode, failureMessage);
        apply(event);
        newEvents.add(event);
        return event;
    }

    /**
     * 退款命令：产出 {@link PaymentRefundedEvent}。
     *
     * @throws IllegalStateException 当聚合根非 SUCCEEDED 状态
     */
    public PaymentRefundedEvent refund(String refundNo, BigDecimal refundAmount,
                                       String refundChainTxHash, String reason) {
        if (state != State.SUCCEEDED) {
            throw new IllegalStateException("Cannot refund in state: " + state);
        }
        long nextVersion = version + 1;
        PaymentRefundedEvent event = new PaymentRefundedEvent(
                aggregateId, nextVersion, refundNo, refundAmount, refundChainTxHash, reason);
        apply(event);
        newEvents.add(event);
        return event;
    }

    // ============ 事件应用（更新状态） ============

    /**
     * 应用一个事件到聚合根（命令产出后调用，更新状态与版本号）。
     */
    private void apply(PaymentEvent event) {
        doApply(event);
        this.version = event.getVersion();
    }

    /**
     * 重放时应用事件（不加入 newEvents，仅更新状态与版本号）。
     */
    private void applyRehydrated(PaymentEvent event) {
        doApply(event);
        this.version = event.getVersion();
    }

    /**
     * 实际状态转移逻辑（按事件类型分发）。
     *
     * <p>对外暴露为 public，便于 {@link EventReplayService} 直接调用以应用从 EventStore 加载的事件。
     */
    public void doApply(PaymentEvent event) {
        if (event instanceof PaymentCreatedEvent e) {
            applyCreated(e);
        } else if (event instanceof PaymentProcessingEvent e) {
            applyProcessing(e);
        } else if (event instanceof PaymentSucceededEvent e) {
            applySucceeded(e);
        } else if (event instanceof PaymentFailedEvent e) {
            applyFailed(e);
        } else if (event instanceof PaymentRefundedEvent e) {
            applyRefunded(e);
        } else {
            throw new IllegalArgumentException("Unknown event type: " + event.getClass().getName());
        }
    }

    /**
     * 应用支付创建事件：状态 → CREATED。
     */
    public void apply(PaymentCreatedEvent event) {
        applyCreated(event);
        this.version = event.getVersion();
    }

    private void applyCreated(PaymentCreatedEvent event) {
        this.state = State.CREATED;
        this.merchantId = event.getMerchantId();
        this.orderNo = event.getOrderNo();
        this.amount = event.getAmount();
        this.tokenSymbol = event.getTokenSymbol();
        this.payerAddress = event.getPayerAddress();
        this.payeeAddress = event.getPayeeAddress();
    }

    /**
     * 应用处理中事件：状态 → PROCESSING。
     */
    public void apply(PaymentProcessingEvent event) {
        applyProcessing(event);
        this.version = event.getVersion();
    }

    private void applyProcessing(PaymentProcessingEvent event) {
        this.state = State.PROCESSING;
        if (event.getChainTxHash() != null) {
            this.chainTxHash = event.getChainTxHash();
        }
    }

    /**
     * 应用成功事件：状态 → SUCCEEDED。
     */
    public void apply(PaymentSucceededEvent event) {
        applySucceeded(event);
        this.version = event.getVersion();
    }

    private void applySucceeded(PaymentSucceededEvent event) {
        this.state = State.SUCCEEDED;
        this.chainTxHash = event.getChainTxHash();
        if (event.getSettledAmount() != null) {
            this.settledAmount = event.getSettledAmount();
        }
        this.paidAt = event.getPaidAt();
    }

    /**
     * 应用失败事件：状态 → FAILED。
     */
    public void apply(PaymentFailedEvent event) {
        applyFailed(event);
        this.version = event.getVersion();
    }

    private void applyFailed(PaymentFailedEvent event) {
        this.state = State.FAILED;
        this.failureCode = event.getFailureCode();
        this.failureMessage = event.getFailureMessage();
    }

    /**
     * 应用退款事件：状态 → REFUNDED。
     */
    public void apply(PaymentRefundedEvent event) {
        applyRefunded(event);
        this.version = event.getVersion();
    }

    private void applyRefunded(PaymentRefundedEvent event) {
        this.state = State.REFUNDED;
        this.refundNo = event.getRefundNo();
        this.refundAmount = event.getRefundAmount();
        this.refundChainTxHash = event.getRefundChainTxHash();
        this.refundReason = event.getReason();
    }

    // ============ 新事件访问 ============

    /**
     * 获取本次聚合根操作新产出的事件（不可变视图）。
     *
     * <p>调用方在持久化这些事件后应调用 {@link #commitNewEvents()} 清空缓存。
     */
    public List<PaymentEvent> peekNewEvents() {
        return Collections.unmodifiableList(newEvents);
    }

    /**
     * 清空新事件缓存（持久化成功后调用）。
     */
    public void commitNewEvents() {
        newEvents.clear();
    }

    // ============ 状态访问器 ============

    public String getAggregateId() {
        return aggregateId;
    }

    public State getState() {
        return state;
    }

    public long getVersion() {
        return version;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getTokenSymbol() {
        return tokenSymbol;
    }

    public String getPayerAddress() {
        return payerAddress;
    }

    public String getPayeeAddress() {
        return payeeAddress;
    }

    public String getChainTxHash() {
        return chainTxHash;
    }

    public BigDecimal getSettledAmount() {
        return settledAmount;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public String getRefundNo() {
        return refundNo;
    }

    public BigDecimal getRefundAmount() {
        return refundAmount;
    }

    public String getRefundChainTxHash() {
        return refundChainTxHash;
    }

    public String getRefundReason() {
        return refundReason;
    }

    @Override
    public String toString() {
        return "PaymentAggregate{" +
                "aggregateId='" + aggregateId + '\'' +
                ", state=" + state +
                ", version=" + version +
                ", orderNo='" + orderNo + '\'' +
                ", amount=" + amount +
                ", chainTxHash='" + chainTxHash + '\'' +
                '}';
    }
}