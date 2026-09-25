package org.nexus.gateway.clearing;

import org.nexus.gateway.account.AccountOperationType;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.MerchantAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 清算结算服务 — 处理清算批次入账，将清算结果写入商户余额。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #processClearingBatch} — 处理清算批次：为每个商户创建清算入账记录并执行入账</li>
 *   <li>{@link #getClearingRecords} — 查询清算入账记录</li>
 *   <li>{@link #retryFailedRecords} — 重试失败的入账记录</li>
 * </ul>
 *
 * <p>所有资金操作通过 {@link AccountService#creditOnClearing} 执行，
 * 使用 clearingOrderId 作为幂等键确保同一清算订单不重复入账。</p>
 */
@Service
public class ClearingSettlementService {

    private static final Logger log = LoggerFactory.getLogger(ClearingSettlementService.class);

    private final ClearingSettlementRecordRepository recordRepository;
    private final AccountService accountService;
    private final ApplicationEventPublisher eventPublisher;

    public ClearingSettlementService(ClearingSettlementRecordRepository recordRepository,
                                      AccountService accountService,
                                      ApplicationEventPublisher eventPublisher) {
        this.recordRepository = recordRepository;
        this.accountService = accountService;
        this.eventPublisher = eventPublisher;
    }

    // === 清算入账 ===

    /**
     * 处理清算批次 — 为每个商户创建清算入账记录并执行入账。
     *
     * <p>操作步骤：</p>
     * <ol>
     *   <li>为每个商户创建 PENDING 状态的清算入账记录</li>
     *   <li>通过 AccountService.creditOnClearing 执行入账</li>
     *   <li>更新记录状态为 BOOKED 或 FAILED</li>
     * </ol>
     *
     * @param batchNo 清算批次编号
     * @param settlementDetails 商户结算明细列表
     * @return 入账结果摘要
     */
    @Transactional
    public ClearingBatchResult processClearingBatch(String batchNo,
                                                     List<ClearingBatchCompletedEvent.MerchantSettlementDetail> settlementDetails) {
        log.info("处理清算批次: batchNo={}, merchantCount={}", batchNo, settlementDetails.size());

        int successCount = 0;
        int failCount = 0;
        List<ClearingSettlementRecord> records = new ArrayList<>();

        for (ClearingBatchCompletedEvent.MerchantSettlementDetail detail : settlementDetails) {
            ClearingSettlementRecord record = createClearingRecord(batchNo, detail);
            records.add(record);

            try {
                // 通过 AccountService 执行清算入账（幂等）
                String clearingOrderId = "CLR_" + batchNo + "_" + detail.getMerchantId();
                MerchantAccount account = accountService.creditOnClearing(
                        detail.getMerchantId(), detail.getNetAmount(), clearingOrderId);

                if (account != null) {
                    record.setBookingStatus(BookingStatus.BOOKED);
                    record.setAccountId(account.getAccountId());
                    record.setOperationType(AccountOperationType.CLEARING_SETTLE.name());
                    record.setTransferReference(clearingOrderId);
                    record.setSettledAt(LocalDateTime.now());
                    successCount++;
                } else {
                    // 幂等跳过（已入账），标记为 BOOKED
                    record.setBookingStatus(BookingStatus.BOOKED);
                    record.setOperationType(AccountOperationType.CLEARING_SETTLE.name());
                    record.setTransferReference(clearingOrderId);
                    record.setSettledAt(LocalDateTime.now());
                    successCount++;
                }
            } catch (Exception e) {
                record.setBookingStatus(BookingStatus.FAILED);
                record.setErrorMessage(e.getMessage());
                failCount++;
                log.warn("清算入账失败: batchNo={}, merchantId={}, error={}",
                        batchNo, detail.getMerchantId(), e.getMessage());
            }

            recordRepository.save(record);
        }

        log.info("清算批次处理完成: batchNo={}, success={}, fail={}", batchNo, successCount, failCount);

        return new ClearingBatchResult(batchNo, successCount, failCount, records);
    }

    /**
     * 创建清算入账记录（PENDING 状态）。
     */
    private ClearingSettlementRecord createClearingRecord(String batchNo,
                                                           ClearingBatchCompletedEvent.MerchantSettlementDetail detail) {
        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordNo("CSR" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        record.setRecordType("CLEARING");
        record.setBatchNo(batchNo);
        record.setMerchantId(detail.getMerchantId());
        record.setAmount(detail.getNetAmount());
        record.setDirection("CREDIT");
        record.setBookingStatus(BookingStatus.PENDING);
        return record;
    }

    // === 查询 ===

    /**
     * 查询清算入账记录。
     *
     * @param batchNo 清算批次编号（为 null 时查询全部）
     * @return 清算入账记录列表
     */
    @Transactional(readOnly = true)
    public List<ClearingSettlementRecord> getClearingRecords(String batchNo) {
        if (batchNo != null) {
            return recordRepository.findByBatchNo(batchNo);
        }
        return recordRepository.findByRecordType("CLEARING");
    }

    /**
     * 查询指定商户的清算入账记录。
     *
     * @param merchantId 商户ID
     * @return 清算入账记录列表
     */
    @Transactional(readOnly = true)
    public List<ClearingSettlementRecord> getClearingRecordsByMerchant(Long merchantId) {
        return recordRepository.findByMerchantId(merchantId);
    }

    // === 重试 ===

    /**
     * 重试失败的入账记录。
     *
     * @param batchNo 清算批次编号
     * @return 重试结果摘要
     */
    @Transactional
    public ClearingBatchResult retryFailedRecords(String batchNo) {
        List<ClearingSettlementRecord> failedRecords = recordRepository
                .findByBatchNoAndBookingStatus(batchNo, BookingStatus.FAILED);

        log.info("重试失败入账记录: batchNo={}, failCount={}", batchNo, failedRecords.size());

        int successCount = 0;
        int failCount = 0;

        for (ClearingSettlementRecord record : failedRecords) {
            try {
                String clearingOrderId = record.getTransferReference() != null
                        ? record.getTransferReference()
                        : "CLR_" + batchNo + "_" + record.getMerchantId();

                MerchantAccount account = accountService.creditOnClearing(
                        record.getMerchantId(), record.getAmount(), clearingOrderId);

                if (account != null) {
                    record.setBookingStatus(BookingStatus.BOOKED);
                    record.setAccountId(account.getAccountId());
                    record.setErrorMessage(null);
                    record.setSettledAt(LocalDateTime.now());
                    successCount++;
                } else {
                    record.setBookingStatus(BookingStatus.BOOKED);
                    record.setSettledAt(LocalDateTime.now());
                    successCount++;
                }
            } catch (Exception e) {
                record.setErrorMessage(e.getMessage());
                failCount++;
                log.warn("重试入账失败: recordNo={}, error={}", record.getRecordNo(), e.getMessage());
            }

            recordRepository.save(record);
        }

        return new ClearingBatchResult(batchNo, successCount, failCount, failedRecords);
    }

    // === 清算批次结果 DTO ===

    /**
     * 清算批次处理结果。
     */
    public static class ClearingBatchResult {
        private final String batchNo;
        private final int successCount;
        private final int failCount;
        private final List<ClearingSettlementRecord> records;

        public ClearingBatchResult(String batchNo, int successCount, int failCount,
                                    List<ClearingSettlementRecord> records) {
            this.batchNo = batchNo;
            this.successCount = successCount;
            this.failCount = failCount;
            this.records = records;
        }

        public String getBatchNo() { return batchNo; }
        public int getSuccessCount() { return successCount; }
        public int getFailCount() { return failCount; }
        public List<ClearingSettlementRecord> getRecords() { return records; }
    }
}