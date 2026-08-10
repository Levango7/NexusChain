package org.nexus.gateway.orchestration.connector;

import java.util.Map;

/**
 * Standardized payment request passed to connectors.
 */
public class ConnectorPaymentRequest {
    private String paymentId;
    private long amount;
    private String currency;
    private String description;
    private String payerAddress;
    private String payeeAddress;
    private Map<String, String> metadata;

    public ConnectorPaymentRequest() {}

    public ConnectorPaymentRequest(String paymentId, long amount, String currency, String description) {
        this.paymentId = paymentId;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
    }

    public String getPaymentId() { return paymentId; }
    public void setPaymentId(String paymentId) { this.paymentId = paymentId; }
    public long getAmount() { return amount; }
    public void setAmount(long amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }
}