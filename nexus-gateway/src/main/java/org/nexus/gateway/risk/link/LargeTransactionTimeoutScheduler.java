package org.nexus.gateway.risk.link;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大额交易超时升级调度器 — 每 5 分钟检查超时未审核的拦截记录。
 *
 * <p>超过 24 小时未审核的 PENDING_REVIEW 状态拦截记录自动升级为
 * TIMEOUT_ESCALATED 告警状态，触发运维介入。</p>
 */
@Component
public class LargeTransactionTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(LargeTransactionTimeoutScheduler.class);

    private final LargeTransactionInterceptionRepository interceptionRepository;
    private final LargeTransactionInterceptionService interceptionService;

    public LargeTransactionTimeoutScheduler(
            LargeTransactionInterceptionRepository interceptionRepository,
            LargeTransactionInterceptionService interceptionService) {
        this.interceptionRepository = interceptionRepository;
        this.interceptionService = interceptionService;
    }

    /**
     * 每 5 分钟检查超时未审核的拦截记录。
     */
    @Scheduled(fixedRate = 300_000)
    public void checkTimeoutInterceptions() {
        LocalDateTime now = LocalDateTime.now();
        List<LargeTransactionInterception> timeoutRecords = interceptionRepository
                .findByInterceptionStatusAndTimeoutAtBefore(InterceptionStatus.PENDING_REVIEW, now);

        if (timeoutRecords.isEmpty()) {
            return;
        }

        log.info("发现 {} 条超时未审核的大额交易拦截记录，开始升级告警", timeoutRecords.size());

        for (LargeTransactionInterception record : timeoutRecords) {
            try {
                interceptionService.escalateTimeout(record);
            } catch (Exception e) {
                log.error("超时升级告警失败: interceptionId={}, error={}",
                        record.getInterceptionId(), e.getMessage(), e);
            }
        }
    }
}