package org.nexus.gateway.clearing;

import org.nexus.gateway.account.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 结算划拨服务 — 执行结算资金的双向转移（CREDIT/DEBIT）。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>{@link #executeSettlementTransfer} — 执行结算划拨：将结算资金从清算账户划拨到商户余额账户</li>
 *   <li>{@link #executeReconAdjust} — 执行对账差异调整</li>
 *   <li>{@link #executeSuspenseWriteoff} — 执行挂账核销</li>
 * </ul>
 *
 * <p>所有资金操作通过 {@link AccountService} 执行，使用 depositWithType/withdrawWithType
 * 支持自定义操作类型。每笔划拨创建 ClearingSettlementRecord 记录用于审计追溯。</p>
 */
@Service
public class SettlementTransferService {

    private static final Logger log = LoggerFactory.getLogger(SettlementTransferService.class);

    private final ClearingSettlementRecordRepository recordRepository;
    private final AccountService accountService;
    private final MerchantAccountRepository accountRepository;

    public SettlementTransferService(ClearingSettlementRecordRepository recordRepository,
                                      AccountService accountService,
                                      MerchantAccountRepository accountRepository) {
        this.recordRepository = recordRepository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    // === 结算划拨 ===

    /**
     * 执行结算划拨 — 将结算资金划拨到商户余额账户。
     *
     * <p>操作步骤：</p>
     * <ol>
     *   <li>创建 PENDING 状态的结算划拨记录</li>
     *   <li>通过 AccountService.depositWithType 执行入账（SETTLEMENT_TRANSFER 操作类型）</li>
     *   <li>更新记录状态为 BOOKED 或 FAILED</li>
     * </ol>
     *
     * @param merchantId 商户ID
     * @param amount 划拨金额（必须 > 0）
     * @param batchNo 关联清算批次编号
     * @param direction 方向：CREDIT(入账) / DEBIT(出账)
     * @return 结算划拨记录
     * @throws IllegalArgumentException 金额 <= 0
     */
    @Transactional
    public ClearingSettlementRecord executeSettlementTransfer(Long merchantId, BigDecimal amount,
                                                               String batchNo, String direction) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("划拨金额必须大于 0");
        }

        log.info("执行结算划拨: merchantId={}, amount={}, batchNo={}, direction={}",
                merchantId, amount, batchNo, direction);

        // 创建划拨记录
        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordNo("STR" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        record.setRecordType("SETTLEMENT_TRANSFER");
        record.setBatchNo(batchNo);
        record.setMerchantId(merchantId);
        record.setAmount(amount);
        record.setDirection(direction);
        record.setBookingStatus(BookingStatus.PENDING);
        record = recordRepository.save(record);

        try {
            String reference = "STL_" + batchNo + "_" + merchantId;

            if ("CREDIT".equalsIgnoreCase(direction)) {
                // 入账：商户余额增加
                MerchantAccount account = accountService.depositWithType(
                        merchantId, amount, reference, AccountOperationType.SETTLEMENT_TRANSFER);
                record.setAccountId(account.getAccountId());
            } else if ("DEBIT".equalsIgnoreCase(direction)) {
                // 出账：商户余额减少
                MerchantAccount account = accountService.withdrawWithType(
                        merchantId, amount, reference, AccountOperationType.SETTLEMENT_TRANSFER);
                record.setAccountId(account.getAccountId());
            } else {
                throw new IllegalArgumentException("无效的划拨方向: " + direction);
            }

            record.setBookingStatus(BookingStatus.BOOKED);
            record.setOperationType(AccountOperationType.SETTLEMENT_TRANSFER.name());
            record.setTransferReference(reference);
            record.setSettledAt(LocalDateTime.now());

            log.info("结算划拨成功: merchantId={}, amount={}, direction={}", merchantId, amount, direction);
        } catch (Exception e) {
            record.setBookingStatus(BookingStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.warn("结算划拨失败: merchantId={}, error={}", merchantId, e.getMessage());
        }

        return recordRepository.save(record);
    }

    // === 对账差异调整 ===

    /**
     * 执行对账差异调整 — 对账发现差异后调整商户余额。
     *
     * @param merchantId 商户ID
     * @param amount 调整金额（正数为入账，负数为出账）
     * @param reconNo 对账编号
     * @return 调整记录
     */
    @Transactional
    public ClearingSettlementRecord executeReconAdjust(Long merchantId, BigDecimal amount, String reconNo) {
        String direction = amount.compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT";
        BigDecimal absAmount = amount.abs();

        log.info("执行对账差异调整: merchantId={}, amount={}, reconNo={}, direction={}",
                merchantId, amount, reconNo, direction);

        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordNo("RAR" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        record.setRecordType("RECON_ADJUST");
        record.setMerchantId(merchantId);
        record.setAmount(absAmount);
        record.setDirection(direction);
        record.setBookingStatus(BookingStatus.PENDING);
        record.setTransferReference(reconNo);
        record = recordRepository.save(record);

        try {
            MerchantAccount account;
            if ("CREDIT".equals(direction)) {
                account = accountService.depositWithType(
                        merchantId, absAmount, reconNo, AccountOperationType.RECON_ADJUST);
            } else {
                account = accountService.withdrawWithType(
                        merchantId, absAmount, reconNo, AccountOperationType.RECON_ADJUST);
            }

            record.setBookingStatus(BookingStatus.BOOKED);
            record.setAccountId(account.getAccountId());
            record.setOperationType(AccountOperationType.RECON_ADJUST.name());
            record.setSettledAt(LocalDateTime.now());
        } catch (Exception e) {
            record.setBookingStatus(BookingStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.warn("对账差异调整失败: merchantId={}, error={}", merchantId, e.getMessage());
        }

        return recordRepository.save(record);
    }

    // === 挂账核销 ===

    /**
     * 执行挂账核销 — 挂账资金核销处理。
     *
     * @param merchantId 商户ID
     * @param amount 核销金额（正数为入账，负数为出账）
     * @param suspenseNo 挂账编号
     * @return 核销记录
     */
    @Transactional
    public ClearingSettlementRecord executeSuspenseWriteoff(Long merchantId, BigDecimal amount, String suspenseNo) {
        String direction = amount.compareTo(BigDecimal.ZERO) > 0 ? "CREDIT" : "DEBIT";
        BigDecimal absAmount = amount.abs();

        log.info("执行挂账核销: merchantId={}, amount={}, suspenseNo={}, direction={}",
                merchantId, amount, suspenseNo, direction);

        ClearingSettlementRecord record = new ClearingSettlementRecord();
        record.setRecordNo("SWR" + System.currentTimeMillis() + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        record.setRecordType("SUSPENSE_WRITEOFF");
        record.setMerchantId(merchantId);
        record.setAmount(absAmount);
        record.setDirection(direction);
        record.setBookingStatus(BookingStatus.PENDING);
        record.setTransferReference(suspenseNo);
        record = recordRepository.save(record);

        try {
            MerchantAccount account;
            if ("CREDIT".equals(direction)) {
                account = accountService.depositWithType(
                        merchantId, absAmount, suspenseNo, AccountOperationType.SUSPENSE_WRITEOFF);
            } else {
                account = accountService.withdrawWithType(
                        merchantId, absAmount, suspenseNo, AccountOperationType.SUSPENSE_WRITEOFF);
            }

            record.setBookingStatus(BookingStatus.BOOKED);
            record.setAccountId(account.getAccountId());
            record.setOperationType(AccountOperationType.SUSPENSE_WRITEOFF.name());
            record.setSettledAt(LocalDateTime.now());
        } catch (Exception e) {
            record.setBookingStatus(BookingStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            log.warn("挂账核销失败: merchantId={}, error={}", merchantId, e.getMessage());
        }

        return recordRepository.save(record);
    }

    // === 查询 ===

    /**
     * 查询结算划拨记录。
     *
     * @param merchantId 商户ID
     * @return 划拨记录列表
     */
    @Transactional(readOnly = true)
    public List<ClearingSettlementRecord> getSettlementTransferRecords(Long merchantId) {
        return recordRepository.findByMerchantId(merchantId);
    }
}