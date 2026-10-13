package org.nexus.gateway.clearing;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
 * <p><b>多副本防护（两层，缺一不可）</b>：</p>
 * <ul>
 *   <li>ShedLock {@code @SchedulerLock}：prod 是 {@code replicaCount: 3}
 *       （values-prod.yaml:20-21），同一 tick 只让一个副本进入扫描。</li>
 *   <li>指令级 CAS 认领：{@code PENDING → EXECUTING} 条件 UPDATE，拿到行才动账。
 *       锁挡不住"锁过期后第二个副本进来"和"指令跨 tick 被反复执行"，认领才挡得住 ——
 *       原实现只有"读 PENDING 然后动账"，且从不回写被扫到的那条指令。</li>
 * </ul>
 *
 * <p><b>当前可达性说明（不要误读成"正在漏钱"）</b>：全仓唯一写
 * {@code recordType=SETTLEMENT_TRANSFER} 的地方是
 * {@code SettlementTransferService#executeSettlementTransfer}，它在同一方法内就把状态推到
 * BOOKED/FAILED，所以今天的扫描集恒空、这条定时路径不可达。本类的两层防护是对
 * "将来接上真实生产者或运维手工插入指令"这一时刻的预置，不是对现行资金事故的修复。</p>
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
    @SchedulerLock(name = "settlementTransferSweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
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
            // 检查是否已到达结算时间（settled_at 字段为 null 表示立即执行）；
            // 未到期的指令保持 PENDING，留给下一个 tick。
            if (record.getSettledAt() != null && record.getSettledAt().isAfter(LocalDateTime.now())) {
                continue;
            }

            // 先原子认领（PENDING → EXECUTING），拿到执行权才允许动账。
            // 这一步同时封掉两个重复源：同一 tick 内三个副本各划拨一次、以及
            // 同一条指令跨 tick 被反复划拨（原来循环体从不回写被扫到的指令）。
            if (!settlementTransferService.claimScheduledTransfer(record.getId())) {
                log.debug("划拨指令已被其他副本认领，跳过: recordNo={}", record.getRecordNo());
                continue;
            }

            try {
                settlementTransferService.executeSettlementTransfer(
                        record.getMerchantId(),
                        record.getAmount(),
                        record.getBatchNo(),
                        record.getDirection());
                settlementTransferService.finishScheduledTransfer(record.getId(), true, null);
                successCount++;
            } catch (Exception e) {
                log.warn("结算划拨调度失败: recordNo={}, error={}",
                        record.getRecordNo(), e.getMessage());
                settlementTransferService.finishScheduledTransfer(record.getId(), false,
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failCount++;
            }
        }

        log.info("结算划拨调度完成: success={}, fail={}", successCount, failCount);
    }
}