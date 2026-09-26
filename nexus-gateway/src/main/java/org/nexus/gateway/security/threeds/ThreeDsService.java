package org.nexus.gateway.security.threeds;

import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.repository.PaymentOrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 3DS 认证服务 — 编排完整的 3DS 认证流程。
 *
 * <p>核心流程：
 * <ol>
 *   <li>{@link #initiateAuth} — 发起 3DS 认证，创建认证记录，调用模拟 ACS</li>
 *   <li>{@link #completeAuth} — 完成 Challenge 认证，验证挑战结果</li>
 *   <li>{@link #getAuthStatus} — 查询认证状态</li>
 * </ol>
 * </p>
 *
 * <p>认证结果通过 {@link ThreeDsAuthCompletedEvent} 事件发布联动风控系统。</p>
 */
@Service
public class ThreeDsService {

    private static final Logger log = LoggerFactory.getLogger(ThreeDsService.class);

    private final ThreeDsConfigService configService;
    private final ThreeDsServer threeDsServer;
    private final ThreeDsAuthRecordRepository authRecordRepository;
    private final PaymentOrderRepository paymentOrderRepository;
    private final ApplicationEventPublisher eventPublisher;

    public ThreeDsService(ThreeDsConfigService configService,
                          ThreeDsServer threeDsServer,
                          ThreeDsAuthRecordRepository authRecordRepository,
                          PaymentOrderRepository paymentOrderRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.configService = configService;
        this.threeDsServer = threeDsServer;
        this.authRecordRepository = authRecordRepository;
        this.paymentOrderRepository = paymentOrderRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 发起 3DS 认证流程。
     *
     * <p>流程：
     * <ol>
     *   <li>查询支付订单</li>
     *   <li>查询 3DS 配置（商户级优先，回退租户级）</li>
     *   <li>若 3DS 未启用，返回跳过结果</li>
     *   <li>创建认证记录</li>
     *   <li>调用模拟 ACS 发起认证</li>
     *   <li>更新认证记录</li>
     *   <li>发布认证完成事件（Frictionless 时）</li>
     * </ol>
     * </p>
     *
     * @param paymentOrderId 支付订单 ID
     * @param deviceInfo     设备信息
     * @return 3DS 认证结果
     */
    @Transactional
    public ThreeDsServer.ThreeDsAuthResult initiateAuth(Long paymentOrderId,
                                                        RiskAssessor.DeviceInfo deviceInfo) {
        log.info("发起 3DS 认证: paymentOrderId={}", paymentOrderId);

        // 1. 查询支付订单
        PaymentOrder order = paymentOrderRepository.findById(paymentOrderId)
                .orElseThrow(() -> new IllegalArgumentException("订单不存在: " + paymentOrderId));

        // 2. 查询 3DS 配置
        ThreeDsConfig config = configService.getConfig(order.getTenantId(), order.getMerchantId());

        // 3. 若 3DS 未启用，返回跳过结果
        if (!config.isEnabled()) {
            log.info("3DS 未启用，跳过认证: paymentOrderId={}", paymentOrderId);
            return ThreeDsServer.ThreeDsAuthResult.builder()
                    .transStatus(TransStatus.U)
                    .authStatus(AuthStatus.COMPLETED)
                    .riskScore(null)
                    .frictionlessFlow(false)
                    .challengeFlow(false)
                    .completedAt(LocalDateTime.now())
                    .build();
        }

        // 4. 创建认证记录
        ThreeDsAuthRecord authRecord = createAuthRecord(order);

        // 5. 调用模拟 ACS 发起认证
        ThreeDsServer.ThreeDsAuthResult result =
                threeDsServer.initiateAuth(order, deviceInfo, config);

        // 6. 更新认证记录
        updateAuthRecord(authRecord, result);

        // 7. 发布事件（Frictionless 认证完成时）
        if (result.getAuthStatus() == AuthStatus.COMPLETED) {
            publishAuthCompletedEvent(authRecord, result);
        }

        log.info("3DS 认证发起完成: paymentOrderId={}, transStatus={}, authStatus={}",
                paymentOrderId, result.getTransStatus(), result.getAuthStatus());

        return result;
    }

    /**
     * 完成 Challenge 认证。
     *
     * @param authRecordId   认证记录 ID
     * @param challengeResult 挑战结果（如 OTP 验证码）
     * @return 3DS 认证结果
     */
    @Transactional
    public ThreeDsServer.ThreeDsAuthResult completeAuth(Long authRecordId, String challengeResult) {
        log.info("完成 3DS Challenge 认证: authRecordId={}", authRecordId);

        ThreeDsAuthRecord authRecord = authRecordRepository.findById(authRecordId)
                .orElseThrow(() -> new IllegalArgumentException("认证记录不存在: " + authRecordId));

        // 检查认证记录状态
        if (authRecord.getAuthStatus() != AuthStatus.INITIATED) {
            log.warn("认证记录状态非 INITIATED，无法完成 Challenge: authRecordId={}, status={}",
                    authRecordId, authRecord.getAuthStatus());
            return ThreeDsServer.ThreeDsAuthResult.builder()
                    .transStatus(TransStatus.N)
                    .authStatus(AuthStatus.FAILED)
                    .errorCode("3DS_CHALLENGE_INVALID")
                    .errorDetail("认证记录状态非 INITIATED")
                    .completedAt(LocalDateTime.now())
                    .build();
        }

        // 调用模拟 ACS 完成 Challenge
        ThreeDsServer.ThreeDsAuthResult result =
                threeDsServer.completeChallenge(authRecord, challengeResult);

        // 更新认证记录
        updateAuthRecord(authRecord, result);

        // 发布认证完成事件
        publishAuthCompletedEvent(authRecord, result);

        log.info("3DS Challenge 认证完成: authRecordId={}, transStatus={}, authStatus={}",
                authRecordId, result.getTransStatus(), result.getAuthStatus());

        return result;
    }

    /**
     * 查询认证状态。
     *
     * @param paymentOrderId 支付订单 ID
     * @return 认证记录（可能为空）
     */
    @Transactional(readOnly = true)
    public Optional<ThreeDsAuthRecord> getAuthStatus(Long paymentOrderId) {
        return authRecordRepository.findByPaymentOrderId(paymentOrderId);
    }

    // --- 内部方法 ---

    /**
     * 创建认证记录。
     */
    private ThreeDsAuthRecord createAuthRecord(PaymentOrder order) {
        ThreeDsAuthRecord record = new ThreeDsAuthRecord();
        record.setTenantId(order.getTenantId());
        record.setMerchantId(order.getMerchantId());
        record.setPaymentOrderId(order.getId());
        record.setAuthStatus(AuthStatus.INITIATED);
        record.setTransStatus(TransStatus.U); // 初始状态，待 ACS 返回
        record.setInitiatedAt(LocalDateTime.now());
        return authRecordRepository.save(record);
    }

    /**
     * 更新认证记录。
     */
    private void updateAuthRecord(ThreeDsAuthRecord record,
                                  ThreeDsServer.ThreeDsAuthResult result) {
        record.setTransStatus(result.getTransStatus());
        record.setAuthStatus(result.getAuthStatus());
        record.setRiskScore(result.getRiskScore());
        record.setFrictionlessFlow(result.isFrictionlessFlow());
        record.setChallengeFlow(result.isChallengeFlow());
        record.setAcsChallengeUrl(result.getAcsChallengeUrl());
        record.setAcsTransId(result.getAcsTransId());
        record.setDsTransId(result.getDsTransId());
        record.setErrorCode(result.getErrorCode());
        record.setErrorDetail(result.getErrorDetail());

        if (result.getCompletedAt() != null) {
            record.setCompletedAt(result.getCompletedAt());
        }

        authRecordRepository.save(record);
    }

    /**
     * 发布认证完成事件，联动风控系统。
     */
    private void publishAuthCompletedEvent(ThreeDsAuthRecord record,
                                           ThreeDsServer.ThreeDsAuthResult result) {
        ThreeDsAuthCompletedEvent event = new ThreeDsAuthCompletedEvent(
                this,
                record.getPaymentOrderId(),
                record.getMerchantId(),
                record.getTenantId(),
                result.getTransStatus(),
                result.getAuthStatus(),
                result.getRiskScore(),
                result.isFrictionlessFlow(),
                result.isChallengeFlow()
        );
        eventPublisher.publishEvent(event);
        log.debug("发布 3DS 认证完成事件: paymentOrderId={}, transStatus={}",
                record.getPaymentOrderId(), result.getTransStatus());
    }
}