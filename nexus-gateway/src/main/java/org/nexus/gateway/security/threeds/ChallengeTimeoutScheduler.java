package org.nexus.gateway.security.threeds;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Challenge 超时调度器 — 定时扫描超时的 Challenge 认证。
 *
 * <p>每 30 秒扫描一次，将超过 {@code challengeTimeoutSeconds}（默认 300 秒 / 5 分钟）
 * 未完成的 Challenge 认证标记为 TIMEOUT 状态，并发布 {@link ThreeDsAuthCompletedEvent}
 * 事件联动风控系统。</p>
 *
 * <p>超时认证视为认证失败（transStatus=N），风控系统应据此调整订单风险等级。</p>
 */
@Component
public class ChallengeTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChallengeTimeoutScheduler.class);

    /** 扫描间隔（毫秒），每 30 秒扫描一次。 */
    private static final long SCAN_INTERVAL_MS = 30_000;

    private final ThreeDsAuthRecordRepository authRecordRepository;
    private final ThreeDsConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    public ChallengeTimeoutScheduler(ThreeDsAuthRecordRepository authRecordRepository,
                                     ThreeDsConfigService configService,
                                     ApplicationEventPublisher eventPublisher) {
        this.authRecordRepository = authRecordRepository;
        this.configService = configService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 定时扫描超时的 Challenge 认证。
     *
     * <p>查询所有 INITIATED 状态的认证记录，按各自租户/商户配置的 challengeTimeoutSeconds
     * 判断是否超时，将超时记录标记为 TIMEOUT 状态并发布认证完成事件。</p>
     */
    @Scheduled(fixedDelay = SCAN_INTERVAL_MS)
    @Transactional
    public void checkChallengeTimeout() {
        // 查询所有 INITIATED 状态记录（使用最长超时时间作为查询上限）
        LocalDateTime maxCutoff = LocalDateTime.now().minusSeconds(
                ThreeDsConfigService.DEFAULT_CHALLENGE_TIMEOUT_SECONDS);
        List<ThreeDsAuthRecord> candidateRecords =
                authRecordRepository.findByAuthStatusAndInitiatedAtBefore(AuthStatus.INITIATED, maxCutoff);

        if (candidateRecords.isEmpty()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        List<ThreeDsAuthRecord> timedOutRecords = new java.util.ArrayList<>();

        // 按各自租户/商户配置的超时时间逐条判断
        for (ThreeDsAuthRecord record : candidateRecords) {
            ThreeDsConfig config = configService.getConfig(record.getTenantId(), record.getMerchantId());
            LocalDateTime cutoff = now.minusSeconds(config.getChallengeTimeoutSeconds());
            if (record.getInitiatedAt().isBefore(cutoff)) {
                timedOutRecords.add(record);
            }
        }

        if (timedOutRecords.isEmpty()) {
            return;
        }

        log.info("发现 {} 条超时的 3DS Challenge 认证记录", timedOutRecords.size());

        for (ThreeDsAuthRecord record : timedOutRecords) {
            ThreeDsConfig config = configService.getConfig(record.getTenantId(), record.getMerchantId());
            int timeoutSeconds = config.getChallengeTimeoutSeconds();

            // 标记为超时
            record.setAuthStatus(AuthStatus.TIMEOUT);
            record.setTransStatus(TransStatus.N); // 超时视为未认证
            record.setCompletedAt(now);
            record.setErrorCode("3DS_CHALLENGE_TIMEOUT");
            record.setErrorDetail("Challenge 认证超时，超过 " + timeoutSeconds + " 秒未完成");
            authRecordRepository.save(record);

            // 发布认证完成事件（超时 = 认证失败），联动风控系统
            ThreeDsAuthCompletedEvent event = new ThreeDsAuthCompletedEvent(
                    this,
                    record.getPaymentOrderId(),
                    record.getMerchantId(),
                    record.getTenantId(),
                    TransStatus.N,
                    AuthStatus.TIMEOUT,
                    record.getRiskScore(),
                    false,
                    true
            );
            eventPublisher.publishEvent(event);

            log.warn("3DS Challenge 认证超时: authRecordId={}, paymentOrderId={}, merchantId={}",
                    record.getId(), record.getPaymentOrderId(), record.getMerchantId());
        }
    }
}