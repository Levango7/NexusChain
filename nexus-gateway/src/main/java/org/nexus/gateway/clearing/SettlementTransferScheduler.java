package org.nexus.gateway.clearing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 结算划拨调度器 — 定时执行 T+N 结算划拨。
 *
 * <p>定时扫描 PENDING 状态的结算划拨记录，对已到达结算时间的记录执行划拨操作。
 * 默认每 5 分钟执行一次，cron 表达式可配置。</p>
 *
 * <p>使用 ShedLock 保证多实例部署时同一时刻仅一个实例执行（需配合 ShedLockConfig）。</p>
 */
@Component
public class SettlementTransferScheduler {

    private static final Logger log = LoggerFactory.getLogger(SettlementTransferScheduler.class);

    private final ClearingSettlementRecordRepository recordRepository;
    private final SettlementTransferService settlementTransferService;
    private final boolean transferEnabled;

    public SettlementTransferScheduler(ClearingSettlementRecordRepository recordRepository,
                                        SettlementTransferService settlementTransferService,
                                        @Value("${nexus.settlement-transfer.enabled:true}") boolean transferEnabled) {
        this.recordRepository = recordRepository;
        this.settlementTransferService = settlementTransferService;
        this.transferEnabled = transferEnabled;
    }

    /**
     * 定时结算划拨主入口：默认每 5 分钟执行（cron 可配置）。
     *
     * <p>扫描 PENDING 状态的 SETTLEMENT_TRANSFER 类型记录，
     * 对已到达结算时间的记录执行划拨。</p>
     */
    @Scheduled(cron = "${nexus.settlement-transfer.cron:0 */5 * * * *}")
    public void executeScheduledTransfers() {
        if (!transferEnabled) {
            log.debug("结算划拨调度已禁用，跳过");
            return;
        }

        List<ClearingSettlementRecord> pendingRecords = recordRepository
                .findByRecordTypeAndBookingStatus("SETTLEMENT_TRANSFER", BookingStatus.PENDING);

        if (pendingRecords.isEmpty()) {
            log.debug("无待处理的结算划拨记录，跳过");
            return;
        }

        log.info("结算划拨调度开始: 待处理记录数={}", pendingRecords.size());

        int successCount = 0;
        int failCount = 0;

        for (ClearingSettlementRecord record : pendingRecords) {
            try {
                // 检查是否已到达结算时间（settled_at 字段为 null 表示立即执行）
                if (record.getSettledAt() != null && record.getSettledAt().isAfter(LocalDateTime.now())) {
                    continue;
                }

                // 重新执行划拨
                settlementTransferService.executeSettlementTransfer(
                        record.getMerchantId(),
                        record.getAmount(),
                        record.getBatchNo(),
                        record.getDirection());
                successCount++;
            } catch (Exception e) {
                log.warn("结算划拨调度失败: recordNo={}, error={}",
                        record.getRecordNo(), e.getMessage());
                failCount++;
            }
        }

        log.info("结算划拨调度完成: success={}, fail={}", successCount, failCount);
    }
}