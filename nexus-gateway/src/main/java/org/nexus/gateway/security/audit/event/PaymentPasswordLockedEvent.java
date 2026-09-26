package org.nexus.gateway.security.audit.event;

import java.time.Instant;

/**
 * 支付密码锁定事件。当商户支付密码连续验证失败达到阈值时发布，
 * 触发密码锁定及后续告警流程。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.5 — SecurityEventPublisher。</p>
 */
public class PaymentPasswordLockedEvent {

    private final Long merchantId;
    private final int failedAttempts;
    private final Instant lockedAt;
    private final Instant lockedUntil;

    public PaymentPasswordLockedEvent(Long merchantId, int failedAttempts,
                                       Instant lockedUntil) {
        this.merchantId = merchantId;
        this.failedAttempts = failedAttempts;
        this.lockedAt = Instant.now();
        this.lockedUntil = lockedUntil;
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }
}