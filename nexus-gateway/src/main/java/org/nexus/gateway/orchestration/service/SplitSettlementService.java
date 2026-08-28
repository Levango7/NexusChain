package org.nexus.gateway.orchestration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Split Settlement Engine - handles multi-party payment splitting (分账).
 * Given a payment and a set of split rules, calculates how much each recipient gets.
 * Supports: percentage-based, fixed-amount, and remainder-to-owner strategies.
 */
@Service
public class SplitSettlementService {

    private static final Logger log = LoggerFactory.getLogger(SplitSettlementService.class);

    /**
     * Execute a split settlement for a given payment amount.
     * @param paymentId the original payment ID
     * @param totalAmount total amount to split (smallest unit)
     * @param rules list of split rules
     * @return list of settlement entries
     * @throws IllegalArgumentException 分账总额与原始金额不一致时抛出
     *         （审计修复：原实现仅 warn 继续——超额/不足分账属资金一致性错误，
     *         必须 fail-closed；另 PERCENTAGE 计算由 double 改为 BigDecimal，
     *         避免 long &gt; 2^53 时精度丢失）
     */
    public List<SplitEntry> executeSplit(String paymentId, long totalAmount, List<SplitRule> rules) {
        List<SplitEntry> entries = new ArrayList<>();
        long allocated = 0;

        for (SplitRule rule : rules) {
            long amount = switch (rule.getType()) {
                // value in bps：BigDecimal 交叉乘法消除 double 精度损失
                case PERCENTAGE -> java.math.BigDecimal.valueOf(totalAmount)
                        .multiply(java.math.BigDecimal.valueOf(rule.getValue()))
                        .divide(java.math.BigDecimal.valueOf(10_000), 0, java.math.RoundingMode.HALF_UP)
                        .longValueExact();
                case FIXED -> rule.getValue();
                case REMAINDER -> totalAmount - allocated;
            };
            if (amount <= 0) continue;
            allocated += amount;
            entries.add(new SplitEntry(paymentId, rule.getRecipientAddress(), amount, rule.getLabel()));
            log.debug("Split: paymentId={} recipient={} amount={} ({})", paymentId, rule.getRecipientAddress(), amount, rule.getLabel());
        }

        if (allocated != totalAmount) {
            // 审计修复：不匹配即拒绝（原仅 warn 并返回错误分账结果）
            throw new IllegalArgumentException(
                    "Split mismatch: paymentId=" + paymentId + " total=" + totalAmount
                            + " allocated=" + allocated);
        }
        log.info("Split settlement executed: paymentId={} entries={} total={}", paymentId, entries.size(), totalAmount);
        return entries;
    }

    // === Models ===

    public enum SplitType { PERCENTAGE, FIXED, REMAINDER }

    public static class SplitRule {
        private String recipientAddress;
        private SplitType type;
        private long value; // bps for PERCENTAGE, absolute for FIXED
        private String label;

        public SplitRule() {}
        public SplitRule(String recipientAddress, SplitType type, long value, String label) {
            this.recipientAddress = recipientAddress;
            this.type = type;
            this.value = value;
            this.label = label;
        }

        public String getRecipientAddress() { return recipientAddress; }
        public void setRecipientAddress(String a) { this.recipientAddress = a; }
        public SplitType getType() { return type; }
        public void setType(SplitType t) { this.type = t; }
        public long getValue() { return value; }
        public void setValue(long v) { this.value = v; }
        public String getLabel() { return label; }
        public void setLabel(String l) { this.label = l; }
    }

    public static class SplitEntry {
        private String paymentId;
        private String recipientAddress;
        private long amount;
        private String label;

        public SplitEntry(String paymentId, String recipientAddress, long amount, String label) {
            this.paymentId = paymentId;
            this.recipientAddress = recipientAddress;
            this.amount = amount;
            this.label = label;
        }

        public String getPaymentId() { return paymentId; }
        public String getRecipientAddress() { return recipientAddress; }
        public long getAmount() { return amount; }
        public String getLabel() { return label; }
    }
}