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
     */
    public List<SplitEntry> executeSplit(String paymentId, long totalAmount, List<SplitRule> rules) {
        List<SplitEntry> entries = new ArrayList<>();
        long allocated = 0;

        for (SplitRule rule : rules) {
            long amount = switch (rule.getType()) {
                case PERCENTAGE -> Math.round(totalAmount * rule.getValue() / 10000.0); // value in bps
                case FIXED -> rule.getValue();
                case REMAINDER -> totalAmount - allocated;
            };
            if (amount <= 0) continue;
            allocated += amount;
            entries.add(new SplitEntry(paymentId, rule.getRecipientAddress(), amount, rule.getLabel()));
            log.debug("Split: paymentId={} recipient={} amount={} ({})", paymentId, rule.getRecipientAddress(), amount, rule.getLabel());
        }

        if (allocated != totalAmount) {
            log.warn("Split mismatch: paymentId={} total={} allocated={}", paymentId, totalAmount, allocated);
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