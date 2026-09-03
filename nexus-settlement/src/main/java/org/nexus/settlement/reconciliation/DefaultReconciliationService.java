package org.nexus.settlement.reconciliation;

import org.nexus.settlement.clearing.Ledger;
import org.nexus.settlement.clearing.LedgerEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 默认对账服务实现。
 * <p>
 * 以本地账本（{@link Ledger}）中的商户结算分录为基准，与链上记录 /
 * 银行渠道记录按对账键（reference）逐笔比对：
 * <ul>
 *   <li>双方存在且金额一致 → 匹配</li>
 *   <li>仅本地存在 → 差错「本地有、外部无」（疑似未上链 / 渠道未清算）</li>
 *   <li>仅外部存在 → 差错「外部有、本地无」（疑似漏记账）</li>
 *   <li>双方存在但金额不一致 → 差错「金额不符」</li>
 * </ul>
 * 差错明细通过 {@link #reportDiscrepancy} 汇总并记录日志，供人工介入。
 * </p>
 */
@Service
public class DefaultReconciliationService implements ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(DefaultReconciliationService.class);

    private final Ledger ledger;
    private final ChainRecordSource chainRecordSource;
    private final BankRecordSource bankRecordSource;

    /** 历次差错记录（供审计回溯） */
    private final List<ReconciliationReport> discrepancyHistory = new CopyOnWriteArrayList<>();

    public DefaultReconciliationService(Ledger ledger,
                                        ChainRecordSource chainRecordSource,
                                        BankRecordSource bankRecordSource) {
        this.ledger = ledger;
        this.chainRecordSource = chainRecordSource;
        this.bankRecordSource = bankRecordSource;
    }

    @Override
    public ReconciliationReport reconcileWithChain() {
        return reconcile(chainRecordSource.fetchChainRecords(), "CHAIN");
    }

    @Override
    public ReconciliationReport reconcileWithBank() {
        return reconcile(bankRecordSource.fetchBankRecords(), "BANK");
    }

    @Override
    public ReconciliationReport reportDiscrepancy(ReconciliationReport report) {
        if (report == null) {
            return null;
        }
        discrepancyHistory.add(report);
        if (report.getDiscrepancyCount() > 0) {
            log.warn("Reconciliation found {} discrepancies: date={}, source items={}",
                    report.getDiscrepancyCount(), report.getReconcileDate(), report.getDiscrepancies());
        } else {
            log.info("Reconciliation clean: date={}, matched={}",
                    report.getReconcileDate(), report.getMatchedCount());
        }
        return report;
    }

    /**
     * 核心比对逻辑：本地账本商户结算分录 vs 外部记录。
     *
     * <p>Path C 扩展：产出结构化维度（source/双边总量/差错金额汇总）与
     * {@link DiscrepancyDetail} 明细列表，同时保留既有 String 差错描述。</p>
     *
     * @param externalRecords 外部（链上/银行）记录
     * @param sourceLabel     数据源标签（用于差错描述）
     * @return 对账报告
     */
    private ReconciliationReport reconcile(List<SettlementRecord> externalRecords, String sourceLabel) {
        // 本地侧：商户结算分录（MERCHANT:* 账户的贷方），reference = 清算订单 ID
        Map<String, BigDecimal> localByRef = new HashMap<>();
        ledger.entriesOf(Ledger.SETTLEMENT_PAYABLE).stream()
                .filter(e -> e.getDirection() == LedgerEntry.Direction.DEBIT)
                .forEach(e -> localByRef.put(e.getReference(), e.getAmount()));

        Map<String, BigDecimal> externalByRef = new HashMap<>();
        for (SettlementRecord record : externalRecords == null ? List.<SettlementRecord>of() : externalRecords) {
            if (record.getReference() != null) {
                externalByRef.put(record.getReference(), record.getAmount());
            }
        }

        long matched = 0;
        List<String> discrepancies = new ArrayList<>();
        List<DiscrepancyDetail> details = new ArrayList<>();
        BigDecimal discrepancyAmount = BigDecimal.ZERO;

        for (Map.Entry<String, BigDecimal> entry : localByRef.entrySet()) {
            String ref = entry.getKey();
            BigDecimal localAmount = entry.getValue();
            if (!externalByRef.containsKey(ref)) {
                discrepancies.add(String.format("[%s] ref=%s 本地有、外部无（amount=%s）",
                        sourceLabel, ref, localAmount));
                details.add(new DiscrepancyDetail(
                        DiscrepancyDetail.Type.LOCAL_ONLY, ref, localAmount, null));
                discrepancyAmount = discrepancyAmount.add(localAmount != null ? localAmount : BigDecimal.ZERO);
            } else if (localAmount.compareTo(externalByRef.get(ref)) != 0) {
                BigDecimal externalAmount = externalByRef.get(ref);
                discrepancies.add(String.format("[%s] ref=%s 金额不符（local=%s, external=%s）",
                        sourceLabel, ref, localAmount, externalAmount));
                details.add(new DiscrepancyDetail(
                        DiscrepancyDetail.Type.AMOUNT_MISMATCH, ref, localAmount, externalAmount));
                discrepancyAmount = discrepancyAmount.add(
                        localAmount.subtract(externalAmount).abs());
            } else {
                matched++;
            }
        }
        for (String ref : externalByRef.keySet()) {
            if (!localByRef.containsKey(ref)) {
                BigDecimal externalAmount = externalByRef.get(ref);
                discrepancies.add(String.format("[%s] ref=%s 外部有、本地无（疑似漏记账）",
                        sourceLabel, ref));
                details.add(new DiscrepancyDetail(
                        DiscrepancyDetail.Type.EXTERNAL_ONLY, ref, null, externalAmount));
                discrepancyAmount = discrepancyAmount.add(
                        externalAmount != null ? externalAmount : BigDecimal.ZERO);
            }
        }

        ReconciliationReport report = new ReconciliationReport();
        report.setReconcileDate(LocalDate.now());
        report.setMatchedCount(matched);
        report.setDiscrepancyCount(discrepancies.size());
        report.setDiscrepancies(discrepancies);
        report.setSource(sourceLabel);
        report.setTotalLocal(localByRef.size());
        report.setTotalExternal(externalByRef.size());
        report.setTotalDiscrepancyAmount(discrepancyAmount);
        report.setDetails(details);
        report.setReconciledAt(java.time.Instant.now());
        return report;
    }
}
