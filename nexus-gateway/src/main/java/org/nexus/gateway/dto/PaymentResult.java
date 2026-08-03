package org.nexus.gateway.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Response DTO representing the result of a payment operation (pay, confirm, or refund).
 */
public class PaymentResult {

    private String orderNo;
    private String status;
    private BigDecimal amount;
    private String tokenSymbol;
    private String chainTxHash;
    private String checkoutUrl;
    private String payerAddress;
    private String payeeAddress;
    private LocalDateTime paidAt;
    private String message;

    // --- Factory methods ---

    public static PaymentResult pending(String orderNo, String checkoutUrl) {
        PaymentResult result = new PaymentResult();
        result.orderNo = orderNo;
        result.status = "PENDING";
        result.checkoutUrl = checkoutUrl;
        result.message = "Payment initiated, awaiting confirmation";
        return result;
    }

    public static PaymentResult success(String orderNo, String chainTxHash, LocalDateTime paidAt) {
        PaymentResult result = new PaymentResult();
        result.orderNo = orderNo;
        result.status = "PAID";
        result.chainTxHash = chainTxHash;
        result.paidAt = paidAt;
        result.message = "Payment confirmed";
        return result;
    }

    public static PaymentResult failed(String orderNo, String message) {
        PaymentResult result = new PaymentResult();
        result.orderNo = orderNo;
        result.status = "FAILED";
        result.message = message;
        return result;
    }

    // --- Getters and Setters ---

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public LocalDateTime getPaidAt() { return paidAt; }
    public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
