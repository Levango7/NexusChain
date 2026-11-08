package org.nexus.gateway.clearing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.nexus.gateway.alert.AlertEvent;
import org.nexus.gateway.alert.AlertEventRepository;
import org.nexus.gateway.alert.AlertNotifier;
import org.nexus.gateway.alert.AlertRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 清算入账失败重试调度（2026-11-07 生产就绪度审计修复，对应发现 A5）。
 *
 * <h3>修的是什么</h3>
 * <p>审计发现：{@code ClearingSettlementService#retryFailedRecords} 是失败的清算入账记录
 * <b>唯一</b>的重试出口，但它<b>只有测试调用方</b>、该服务类<b>没有任何 {@code @Scheduled}</b>、
 * 生产侧唯一调用者 {@code ClearingSettlementListener} 只调 {@code processClearingBatch}。
 * 于是入账失败（{@code BookingStatus.FAILED}）后：</p>
 * <ul>
 *   <li>{@code failCount > 0} 只有一条 {@code log.info}（{@code ClearingSettlementService}），
 *       listener 的 catch 只有一条 {@code log.error}；</li>
 *   <li><b>无自动重试、无告警、无人工入口</b> ⇒ 失败记录<b>永久停在 FAILED</b>（商户少收钱）。</li>
 * </ul>
 * <p>同仓对照：bridge 侧 {@code BridgeSagaCoordinator#retryFailedSagas} 有 ShedLock 保护的定时重试
 * （{@code ShedLockConfig} 在案）—— <b>同一个仓、同一类问题，bridge 做了、clearing 漏做</b>。
 * 本类把 clearing 侧补齐。</p>
 *
 * <h3>为什么必须先修 {@code retryFailedRecords} 才能接线</h3>
 * <p>原 {@code retryFailedRecords} 的取集不带 {@code recordType} 过滤，而同表多型且共享 batchNo
 * ⇒ 会把划拨/调账/核销记录当清算入账去 credit（反向动账 或 假 BOOKED）。
 * 该缺陷此前因"无生产调用方"而未暴露，<b>接线的瞬间就会变成真实资金缺陷</b>。
 * 故本 PR 先收窄 {@code recordType=CLEARING}，再接定时重试。</p>
 *
 * <h3>并发与幂等</h3>
 * <ul>
 *   <li><b>ShedLock 是主要防线</b>：{@code values-prod.yaml} 的
 *       {@code nexus-gateway.replicaCount: 3} 下，三个副本会同时扫到同一批 FAILED 记录；
 *       {@code @SchedulerLock} 保证同一时刻只有一个副本在跑。</li>
 *   <li><b>账本幂等是兜底</b>：{@code AccountService#creditOnClearing} 以
 *       {@code clearingOrderId} 为幂等键（{@code findByReference} 命中即跳过并回读账户），
 *       故即便锁失效也不会重复入账。</li>
 *   <li><b>逐批独立事务</b>：{@code retryFailedRecords} 是 {@code @Transactional} 且由本类
 *       （不同 bean）调用 ⇒ 代理生效、每批一个事务，单批异常不牵连其它批。</li>
 * </ul>
 *
 * <p>开关：{@code nexus.clearing.retry.enabled}（默认开）；cron：
 * {@code nexus.clearing.retry.cron}（默认每 5 分钟，与同包
 * {@code SettlementTransferScheduler} 同口径）。</p>
 */
@Component
public class ClearingRetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClearingRetryScheduler.class);

    /** 告警规则名（{@code AlertEscalationRule} 可按同名登记升级策略）。 */
    public static final String RULE_CLEARING_RETRY_EXHAUSTED = "domain-clearing-retry-exhausted";

    /** 告警指标名。 */
    public static final String METRIC_CLEARING_RETRY_FAILED = "clearing.booking.retry.failed";

    /** 告警文案上限（{@code AlertEvent.message} 列宽 512）。 */
    private static final int MESSAGE_MAX_LENGTH = 512;

    private final ClearingSettlementRecordRepository recordRepository;
    private final ClearingSettlementService clearingSettlementService;
    private final AlertEventRepository alertEventRepository;
    private final List<AlertNotifier> notifiers;
    private final boolean retryEnabled;

    public ClearingRetryScheduler(ClearingSettlementRecordRepository recordRepository,
                                  ClearingSettlementService clearingSettlementService,
                                  AlertEventRepository alertEventRepository,
                                  List<AlertNotifier> notifiers,
                                  @Value("${nexus.clearing.retry.enabled:true}") boolean retryEnabled) {
        this.recordRepository = recordRepository;
        this.clearingSettlementService = clearingSettlementService;
        this.alertEventRepository = alertEventRepository;
        this.notifiers = notifiers;
        this.retryEnabled = retryEnabled;
    }

    /**
     * 定时重试失败的清算入账记录：默认每 5 分钟扫一次，按批次逐批重试。
     *
     * <p>只扫 {@code recordType=CLEARING} —— 同表的 {@code SETTLEMENT_TRANSFER} /
     * {@code RECON_ADJUST} / {@code SUSPENSE_WRITEOFF} 记录各有自己的语义与重试路径，
     * 混进来会反向动账（见类注释）。</p>
     */
    @Scheduled(cron = "${nexus.clearing.retry.cron:0 */5 * * * *}")
    @SchedulerLock(name = "clearingFailedRetrySweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void retryFailedClearingRecords() {
        if (!retryEnabled) {
            log.debug("清算失败重试调度已禁用，跳过");
            return;
        }

        List<ClearingSettlementRecord> failedRecords = recordRepository
                .findByRecordTypeAndBookingStatus(
                        ClearingSettlementService.RECORD_TYPE_CLEARING, BookingStatus.FAILED);

        if (failedRecords.isEmpty()) {
            log.debug("无失败的清算入账记录，跳过");
            return;
        }

        Set<String> batchNos = new LinkedHashSet<>();
        int missingBatchNo = 0;
        for (ClearingSettlementRecord record : failedRecords) {
            if (record.getBatchNo() == null || record.getBatchNo().isBlank()) {
                missingBatchNo++;
                continue;
            }
            batchNos.add(record.getBatchNo());
        }

        if (batchNos.isEmpty()) {
            // 不静默：这些记录既不重试也无法归批，必须让人看见
            raiseExhaustedAlert(failedRecords.size(), 0, 0, missingBatchNo);
            log.warn("存在 {} 条失败的清算记录但 batchNo 为空，无法按批重试", missingBatchNo);
            return;
        }

        log.info("清算失败重试调度开始: 失败记录={}, 涉及批次={}, 无批次号={}",
                failedRecords.size(), batchNos.size(), missingBatchNo);

        int rebooked = 0;
        int stillFailed = 0;
        int batchErrors = 0;

        for (String batchNo : batchNos) {
            try {
                ClearingSettlementService.ClearingBatchResult result =
                        clearingSettlementService.retryFailedRecords(batchNo);
                rebooked += result.getSuccessCount();
                stillFailed += result.getFailCount();
            } catch (Exception e) {
                // 单批失败不影响其它批次（各批独立事务）
                batchErrors++;
                log.warn("清算重试批次失败: batchNo={}, error={}", batchNo, e.getMessage());
            }
        }

        log.info("清算失败重试调度完成: rebooked={}, stillFailed={}, batchErrors={}",
                rebooked, stillFailed, batchErrors);

        if (stillFailed > 0 || batchErrors > 0 || missingBatchNo > 0) {
            raiseExhaustedAlert(stillFailed, batchNos.size(), batchErrors, missingBatchNo);
        }
    }

    /**
     * 重试一轮后仍有失败 → 落库一条 CRITICAL 告警并扇出到全部 {@link AlertNotifier}。
     *
     * <p>与前一个审计 PR（{@code DomainAlertEventSink}）用同一套出口（同一个
     * {@code AlertEvent} 实体 + 同一组 notifier），故告警会出现在 {@code AlertController}
     * 的查询接口里、也能被 {@code AlertEscalationService} 按 {@code ruleName} 升级。
     * 两处 emit 逻辑刻意各自独立（互不成为对方的合并前置）；若两者都在主干，
     * 可把这段收敛进 {@code DomainAlertEventSink}。</p>
     *
     * <p>整体 try/catch：告警是旁路，任何失败都不得污染调度主流程。</p>
     */
    private void raiseExhaustedAlert(int stillFailed, int batchCount, int batchErrors, int missingBatchNo) {
        try {
            AlertEvent alert = new AlertEvent();
            alert.setRuleName(RULE_CLEARING_RETRY_EXHAUSTED);
            alert.setMetricName(METRIC_CLEARING_RETRY_FAILED);
            alert.setCurrentValue(stillFailed);
            alert.setThreshold(0.0d);
            alert.setSeverity(AlertRule.Severity.CRITICAL);
            alert.setMessage(truncate(String.format(
                    "清算入账重试后仍有失败: stillFailed=%d batches=%d batchErrors=%d missingBatchNo=%d"
                            + "（已重试一轮仍未入账，请人工核对 clearing_settlement_record 的 FAILED 行）",
                    stillFailed, batchCount, batchErrors, missingBatchNo)));
            alert.setTimestamp(LocalDateTime.now());

            alertEventRepository.save(alert);

            for (AlertNotifier notifier : notifiers) {
                try {
                    notifier.notify(alert);
                } catch (Exception e) {
                    log.error("Sink notifier '{}' failed for alert '{}': {}",
                            notifier.channel(), RULE_CLEARING_RETRY_EXHAUSTED, e.getMessage(), e);
                }
            }

            log.info("Domain alert raised: rule={} severity={} message={}",
                    RULE_CLEARING_RETRY_EXHAUSTED, AlertRule.Severity.CRITICAL, alert.getMessage());
        } catch (Exception e) {
            log.error("Failed to raise clearing retry alert: {}", e.getMessage(), e);
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= MESSAGE_MAX_LENGTH
                ? message
                : message.substring(0, MESSAGE_MAX_LENGTH);
    }
}
