package org.nexus.gateway.dto;

/**
 * Unified error code enumeration for the NexusChain Gateway.
 * 0 = success, 4xxxx = client error, 5xxxx = server error.
 */
public enum ErrorCode {

    SUCCESS(0, "success"),

    // 400xx - Bad Request
    BAD_REQUEST(40000, "Bad request"),
    INVALID_PARAMS(40001, "Invalid parameters"),
    ORDER_NOT_FOUND(40400, "Order not found"),
    MERCHANT_NOT_FOUND(40401, "Merchant not found"),

    // 401xx - Authentication
    MISSING_API_KEY(40100, "Missing API key"),
    INVALID_API_KEY(40101, "Invalid or revoked API key"),

    // 403xx - Authorization
    MERCHANT_NOT_VERIFIED(40300, "Merchant not verified"),

    // 409xx - Conflict / State
    ILLEGAL_STATE_TRANSITION(40900, "Illegal state transition"),
    ORDER_ALREADY_PAID(40901, "Order already paid"),
    ORDER_EXPIRED(40902, "Order expired"),

    // 429xx - Rate Limit
    RATE_LIMIT_EXCEEDED(42900, "Rate limit exceeded"),

    // 500xx - Server Error
    INTERNAL_ERROR(50000, "Internal server error"),
    CHAIN_NODE_UNAVAILABLE(50200, "Chain node unavailable"),
    WALLET_SERVICE_UNAVAILABLE(50201, "Wallet service unavailable");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public String getMessage() { return message; }
}