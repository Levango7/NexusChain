package org.nexus.gateway.reconciliation.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对账报表内容 DTO — 包含报表的完整数据结构。
 *
 * <p>分为 5 个部分：metadata、summary、discrepancies、compensations、suspenseAccounts。</p>
 */
public class ReconciliationReportContent {

    /** 报表元数据 */
    private Metadata metadata;

    /** 汇总统计 */
    private Summary summary;

    /** 差异明细列表 */
    private List<DiscrepancyDto> discrepancies;

    /** 补偿记录列表 */
    private List<CompensationDto> compensations;

    /** 挂账记录列表 */
    private List<SuspenseDto> suspenseAccounts;

    // === 内部 DTO 类 ===

    public static class Metadata {
        private Long merchantId;
        private LocalDate reportDate;
        private String channelType;
        private LocalDateTime generatedAt;

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public LocalDate getReportDate() { return reportDate; }
        public void setReportDate(LocalDate reportDate) { this.reportDate = reportDate; }

        public String getChannelType() { return channelType; }
        public void setChannelType(String channelType) { this.channelType = channelType; }

        public LocalDateTime getGeneratedAt() { return generatedAt; }
        public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    }


    public static class Summary {
        private int totalTransactions;
        private int matchedCount;
        private int discrepancyCount;
        private Map<String, Integer> discrepancyByType;
        private BigDecimal totalDiscrepancyAmount;
        private int suspenseCount;
        private int compensationCount;
        private int compensationSuccessCount;
        private int compensationFailedCount;

        public int getTotalTransactions() { return totalTransactions; }
        public void setTotalTransactions(int totalTransactions) { this.totalTransactions = totalTransactions; }

        public int getMatchedCount() { return matchedCount; }
        public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }

        public int getDiscrepancyCount() { return discrepancyCount; }
        public void setDiscrepancyCount(int discrepancyCount) { this.discrepancyCount = discrepancyCount; }

        public Map<String, Integer> getDiscrepancyByType() { return discrepancyByType; }
        public void setDiscrepancyByType(Map<String, Integer> discrepancyByType) { this.discrepancyByType = discrepancyByType; }

        public BigDecimal getTotalDiscrepancyAmount() { return totalDiscrepancyAmount; }
        public void setTotalDiscrepancyAmount(BigDecimal totalDiscrepancyAmount) { this.totalDiscrepancyAmount = totalDiscrepancyAmount; }

        public int getSuspenseCount() { return suspenseCount; }
        public void setSuspenseCount(int suspenseCount) { this.suspenseCount = suspenseCount; }

        public int getCompensationCount() { return compensationCount; }
        public void setCompensationCount(int compensationCount) { this.compensationCount = compensationCount; }

        public int getCompensationSuccessCount() { return compensationSuccessCount; }
        public void setCompensationSuccessCount(int compensationSuccessCount) { this.compensationSuccessCount = compensationSuccessCount; }

        public int getCompensationFailedCount() { return compensationFailedCount; }
        public void setCompensationFailedCount(int compensationFailedCount) { this.compensationFailedCount = compensationFailedCount; }
    }

    public static class DiscrepancyDto {
        private Long id;
        private String transactionId;
        private String discrepancyType;
        private BigDecimal amountDiff;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getTransactionId() { return transactionId; }
        public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

        public String getDiscrepancyType() { return discrepancyType; }
        public void setDiscrepancyType(String discrepancyType) { this.discrepancyType = discrepancyType; }

        public BigDecimal getAmountDiff() { return amountDiff; }
        public void setAmountDiff(BigDecimal amountDiff) { this.amountDiff = amountDiff; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class CompensationDto {
        private Long id;
        private Long discrepancyId;
        private String compensationType;
        private BigDecimal amount;
        private String status;
        private String failureReason;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public Long getDiscrepancyId() { return discrepancyId; }
        public void setDiscrepancyId(Long discrepancyId) { this.discrepancyId = discrepancyId; }

        public String getCompensationType() { return compensationType; }
        public void setCompensationType(String compensationType) { this.compensationType = compensationType; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public String getFailureReason() { return failureReason; }
        public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    }

    public static class SuspenseDto {
        private Long id;
        private String suspenseType;
        private BigDecimal amount;
        private String status;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }

        public String getSuspenseType() { return suspenseType; }
        public void setSuspenseType(String suspenseType) { this.suspenseType = suspenseType; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    // === Getters & Setters ===

    public Metadata getMetadata() { return metadata; }
    public void setMetadata(Metadata metadata) { this.metadata = metadata; }

    public Summary getSummary() { return summary; }
    public void setSummary(Summary summary) { this.summary = summary; }

    public List<DiscrepancyDto> getDiscrepancies() { return discrepancies; }
    public void setDiscrepancies(List<DiscrepancyDto> discrepancies) { this.discrepancies = discrepancies; }

    public List<CompensationDto> getCompensations() { return compensations; }
    public void setCompensations(List<CompensationDto> compensations) { this.compensations = compensations; }

    public List<SuspenseDto> getSuspenseAccounts() { return suspenseAccounts; }
    public void setSuspenseAccounts(List<SuspenseDto> suspenseAccounts) { this.suspenseAccounts = suspenseAccounts; }
}