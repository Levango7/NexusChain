package org.nexus.gateway.subscription;

/**
 * 扣款结果（P4-T8 订阅与循环计费引擎）。
 *
 * <p>封装单次扣款尝试的结果，供 {@link DefaultSubscriptionService} 与
 * {@link DunningManager} 使用。</p>
 */
public class ChargeResult {

    private final boolean success;
    private final String transactionHash;
    private final String errorMessage;
    private final String connectorId;

    private ChargeResult(boolean success, String transactionHash, String errorMessage, String connectorId) {
        this.success = success;
        this.transactionHash = transactionHash;
        this.errorMessage = errorMessage;
        this.connectorId = connectorId;
    }

    public static ChargeResult success(String transactionHash, String connectorId) {
        return new ChargeResult(true, transactionHash, null, connectorId);
    }

    public static ChargeResult failure(String errorMessage, String connectorId) {
        return new ChargeResult(false, null, errorMessage, connectorId);
    }

    public boolean isSuccess() { return success; }
    public String getTransactionHash() { return transactionHash; }
    public String getErrorMessage() { return errorMessage; }
    public String getConnectorId() { return connectorId; }
}