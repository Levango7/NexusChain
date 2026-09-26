package org.nexus.gateway.security.audit.event;

import java.time.Instant;

/**
 * 防重放拦截事件。当检测到重放攻击（重复 nonce 或时间戳过期）时发布，
 * 供监控和告警系统消费。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.5 — SecurityEventPublisher。</p>
 */
public class ReplayInterceptionEvent {

    private final Long merchantId;
    private final String errorCode;
    private final String reason;
    private final Instant interceptedAt;

    public ReplayInterceptionEvent(Long merchantId, String errorCode, String reason) {
        this.merchantId = merchantId;
        this.errorCode = errorCode;
        this.reason = reason;
        this.interceptedAt = Instant.now();
    }

    public Long getMerchantId() {
        return merchantId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getReason() {
        return reason;
    }

    public Instant getInterceptedAt() {
        return interceptedAt;
    }
}