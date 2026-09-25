package org.nexus.gateway.clearing;

import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 清算批次完成事件 — 清算批次完成后发布，供 ClearingSettlementListener 消费。
 *
 * <p>携带清算批次编号、商户结算明细列表等信息，触发清算入账流程。</p>
 */
public class ClearingBatchCompletedEvent extends ApplicationEvent {

    private final String batchNo;
    private final List<MerchantSettlementDetail> settlementDetails;
    private final LocalDateTime completedAt;

    /**
     * 商户结算明细 — 每个商户在清算批次中的结算金额。
     */
    public static class MerchantSettlementDetail {
        private final Long merchantId;
        private final java.math.BigDecimal netAmount;
        private final java.math.BigDecimal feeAmount;

        public MerchantSettlementDetail(Long merchantId, java.math.BigDecimal netAmount, java.math.BigDecimal feeAmount) {
            this.merchantId = merchantId;
            this.netAmount = netAmount;
            this.feeAmount = feeAmount;
        }

        public Long getMerchantId() { return merchantId; }
        public java.math.BigDecimal getNetAmount() { return netAmount; }
        public java.math.BigDecimal getFeeAmount() { return feeAmount; }
    }

    public ClearingBatchCompletedEvent(Object source, String batchNo,
                                        List<MerchantSettlementDetail> settlementDetails,
                                        LocalDateTime completedAt) {
        super(source);
        this.batchNo = batchNo;
        this.settlementDetails = settlementDetails;
        this.completedAt = completedAt;
    }

    public String getBatchNo() { return batchNo; }
    public List<MerchantSettlementDetail> getSettlementDetails() { return settlementDetails; }
    public LocalDateTime getCompletedAt() { return completedAt; }
}