package org.nexus.gateway.compliance;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lightweight transaction view used as input to AML screening and suspicious
 * activity reporting.
 *
 * <p>This is intentionally a self-contained POJO so the compliance service can
 * be exercised without depending on the full JPA entity graph.</p>
 */
public class Transaction {

    /** Internal transaction ID. */
    private String transactionId;

    /** Merchant ID involved in the transaction. */
    private Long merchantId;

    /** Payer wallet address (source). */
    private String fromAddress;

    /** Payee wallet address (destination). */
    private String toAddress;

    /** Transaction amount in the smallest unit of the token. */
    private BigDecimal amount;

    /** Token symbol (e.g. NEX, USDT). */
    private String tokenSymbol;

    /** On-chain transaction hash, if available. */
    private String chainTxHash;

    /** Timestamp when the transaction was initiated. */
    private LocalDateTime timestamp;

    public Transaction() {}

    public Transaction(String transactionId, BigDecimal amount, String tokenSymbol) {
        this.transactionId = transactionId;
        this.amount = amount;
        this.tokenSymbol = tokenSymbol;
        this.timestamp = LocalDateTime.now();
    }

    // --- Getters and Setters ---

    public String getTransactionId() { return transactionId; }
    public void setTransactionId(String transactionId) { this.transactionId = transactionId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getFromAddress() { return fromAddress; }
    public void setFromAddress(String fromAddress) { this.fromAddress = fromAddress; }

    public String getToAddress() { return toAddress; }
    public void setToAddress(String toAddress) { this.toAddress = toAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public String getChainTxHash() { return chainTxHash; }
    public void setChainTxHash(String chainTxHash) { this.chainTxHash = chainTxHash; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}