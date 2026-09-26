package org.nexus.gateway.security.audit;

import org.nexus.gateway.security.audit.event.KeyRotationCompletedEvent;
import org.nexus.gateway.security.audit.event.PaymentPasswordLockedEvent;
import org.nexus.gateway.security.audit.event.ReplayInterceptionEvent;
import org.nexus.gateway.security.audit.event.ThreeDsAuthCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 安全事件发布服务。基于 Spring {@link ApplicationEventPublisher} 发布安全相关事件，
 * 供系统内其他模块（监控、告警、风控等）异步消费。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.5 — SecurityEventPublisher。
 * 架构图中 AuditSvc --> EventPub 的集成关系。</p>
 */
@Service
public class SecurityEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SecurityEventPublisher.class);

    private final ApplicationEventPublisher eventPublisher;

    public SecurityEventPublisher(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发布 3DS 认证完成事件。
     */
    public void publishThreeDsAuthCompleted(ThreeDsAuthCompletedEvent event) {
        log.debug("Publishing ThreeDsAuthCompletedEvent: merchantId={}, orderId={}, transStatus={}",
                event.getMerchantId(), event.getPaymentOrderId(), event.getTransStatus());
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布支付密码锁定事件。
     */
    public void publishPaymentPasswordLocked(PaymentPasswordLockedEvent event) {
        log.debug("Publishing PaymentPasswordLockedEvent: merchantId={}, failedAttempts={}",
                event.getMerchantId(), event.getFailedAttempts());
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布密钥轮换完成事件。
     */
    public void publishKeyRotationCompleted(KeyRotationCompletedEvent event) {
        log.debug("Publishing KeyRotationCompletedEvent: oldVersion={}, newVersion={}, migratedDekCount={}",
                event.getOldVersion(), event.getNewVersion(), event.getMigratedDekCount());
        eventPublisher.publishEvent(event);
    }

    /**
     * 发布防重放拦截事件。
     */
    public void publishReplayInterception(ReplayInterceptionEvent event) {
        log.debug("Publishing ReplayInterceptionEvent: merchantId={}, errorCode={}, reason={}",
                event.getMerchantId(), event.getErrorCode(), event.getReason());
        eventPublisher.publishEvent(event);
    }
}