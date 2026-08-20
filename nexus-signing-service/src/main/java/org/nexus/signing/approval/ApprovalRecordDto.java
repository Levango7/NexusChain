package org.nexus.signing.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

/**
 * 审批记录持久化 DTO（用于 JSON 序列化）。
 *
 * <p>将不可变 {@link SigningApprovalRequest} 的字段展平为可序列化 DTO，
 * 支持 JSON Lines 文件存储，启动时恢复审批记录。
 *
 * @since 2.15.0
 */
public class ApprovalRecordDto {

    private String requestId;
    private String fromPubkey;
    private String toPubkeyHash;
    private String amount;
    private String currency;
    private int requiredApprovers;
    private String createdAt;
    private String deadline;
    private String status;
    private Set<String> approvals;
    private Set<String> rejections;
    private String initiator;

    public ApprovalRecordDto() {}

    /** 从 SigningApprovalRequest 创建 */
    public static ApprovalRecordDto from(SigningApprovalRequest req) {
        ApprovalRecordDto dto = new ApprovalRecordDto();
        dto.requestId = req.getRequestId();
        dto.fromPubkey = req.getFromPubkey();
        dto.toPubkeyHash = req.getToPubkeyHash();
        dto.amount = req.getAmount().toPlainString();
        dto.currency = req.getCurrency();
        dto.requiredApprovers = req.getRequiredApprovers();
        dto.createdAt = req.getCreatedAt().toString();
        dto.deadline = req.getDeadline().toString();
        dto.status = req.getStatus().name();
        dto.approvals = req.getApprovals();
        dto.rejections = req.getRejections();
        dto.initiator = req.getInitiator();
        return dto;
    }

    /** 恢复为 SigningApprovalRequest */
    public SigningApprovalRequest toRequest() {
        return new SigningApprovalRequest(
                requestId, fromPubkey, toPubkeyHash,
                new BigDecimal(amount), currency,
                requiredApprovers,
                Instant.parse(createdAt), Instant.parse(deadline),
                SigningApprovalRequest.Status.valueOf(status),
                approvals, rejections,
                initiator);
    }

    // getters/setters for JSON serialization
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { this.requestId = v; }
    public String getFromPubkey() { return fromPubkey; }
    public void setFromPubkey(String v) { this.fromPubkey = v; }
    public String getToPubkeyHash() { return toPubkeyHash; }
    public void setToPubkeyHash(String v) { this.toPubkeyHash = v; }
    public String getAmount() { return amount; }
    public void setAmount(String v) { this.amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { this.currency = v; }
    public int getRequiredApprovers() { return requiredApprovers; }
    public void setRequiredApprovers(int v) { this.requiredApprovers = v; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String v) { this.createdAt = v; }
    public String getDeadline() { return deadline; }
    public void setDeadline(String v) { this.deadline = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Set<String> getApprovals() { return approvals; }
    public void setApprovals(Set<String> v) { this.approvals = v; }
    public Set<String> getRejections() { return rejections; }
    public void setRejections(Set<String> v) { this.rejections = v; }
    public String getInitiator() { return initiator; }
    public void setInitiator(String v) { this.initiator = v; }
}