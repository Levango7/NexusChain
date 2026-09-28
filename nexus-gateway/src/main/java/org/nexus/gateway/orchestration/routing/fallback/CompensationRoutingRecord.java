package org.nexus.gateway.orchestration.routing.fallback;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 跨渠道补偿路由记录 JPA 实体（Wave 16 模块三）。
 *
 * <p>对应表 {@code compensation_routing_records}（V87）。当补偿（退款/调账）需要
 * 切换到与原支付不同的 connector 执行时，由 {@link CompensationRoutingService}
 * 记录路由决策与执行结果；{@code compensation_id} 唯一约束保证幂等。</p>
 */
@Entity
@Table(name = "compensation_routing_records")
public class CompensationRoutingRecord {

    /**
     * 执行结果。PENDING 为 route() 落库到 complete() 回填之间的瞬态
     * （V87 迁移注释未列出的扩展值，列类型 VARCHAR(16) 兼容）。
     */
    public enum RouteResult { PENDING, SUCCESS, FAILED, TIMEOUT, SKIPPED_IDEMPOTENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code comp_{UUID}}。 */
    @Column(name = "compensation_id", length = 64, nullable = false, unique = true)
    private String compensationId;

    /** 关联 orchestrated_payments.id。 */
    @Column(name = "payment_id", length = 64, nullable = false)
    private String paymentId;

    @Column(name = "merchant_id")
    private Long merchantId;

    @Column(name = "original_connector", length = 64)
    private String originalConnector;

    @Column(name = "compensation_connector", length = 64)
    private String compensationConnector;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo = 1;

    @Column(name = "result", length = 16, nullable = false)
    private String result;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    // === Getters & Setters ===

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCompensationId() { return compensationId; }
    public void setCompensationId(String compensationId) { this.compensationId = compensationId; }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getOriginalConnector() { return originalConnector; }
    public void setOriginalConnector(String originalConnector) { this.originalConnector = originalConnector; }

    public String getCompensationConnector() { return compensationConnector; }
    public void setCompensationConnector(String compensationConnector) { this.compensationConnector = compensationConnector; }

    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Long latencyMs) { this.latencyMs = latencyMs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
