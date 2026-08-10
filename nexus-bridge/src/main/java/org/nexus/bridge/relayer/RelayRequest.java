package org.nexus.bridge.relayer;

import java.math.BigDecimal;

/**
 * 跨链中继请求实体。
 *
 * <p>描述一次跨链转发请求的源链、目标链、交易哈希、金额与状态。</p>
 *
 * @since 1.2
 */
public class RelayRequest {

    /** 请求 ID */
    private String requestId;

    /** 源链 ID */
    private String sourceChain;

    /** 目标链 ID */
    private String targetChain;

    /** 源链交易哈希 */
    private String sourceTxHash;

    /** 跨链金额 */
    private BigDecimal amount;

    /** 请求状态 */
    private RelayRequestStatus status;

    /** 分配的 relayer ID */
    private String assignedRelayerId;

    public RelayRequest() {
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getSourceChain() {
        return sourceChain;
    }

    public void setSourceChain(String sourceChain) {
        this.sourceChain = sourceChain;
    }

    public String getTargetChain() {
        return targetChain;
    }

    public void setTargetChain(String targetChain) {
        this.targetChain = targetChain;
    }

    public String getSourceTxHash() {
        return sourceTxHash;
    }

    public void setSourceTxHash(String sourceTxHash) {
        this.sourceTxHash = sourceTxHash;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public RelayRequestStatus getStatus() {
        return status;
    }

    public void setStatus(RelayRequestStatus status) {
        this.status = status;
    }

    public String getAssignedRelayerId() {
        return assignedRelayerId;
    }

    public void setAssignedRelayerId(String assignedRelayerId) {
        this.assignedRelayerId = assignedRelayerId;
    }
}