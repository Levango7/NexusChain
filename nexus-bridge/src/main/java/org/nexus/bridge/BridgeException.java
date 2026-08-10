package org.nexus.bridge;

/**
 * 桥操作异常，表示跨链操作过程中发生的业务错误。
 *
 * <p>本异常携带错误码，便于调用方区分不同类型的失败原因
 * 并进行相应处理。</p>
 *
 * @since 1.0.0
 */
public class BridgeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码。 */
    private final String errorCode;

    /**
     * 构造桥操作异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     */
    /**
     * Convenience constructor with default error code "BRIDGE_ERROR".
     */
    public BridgeException(String message) {
        super(message);
        this.errorCode = "BRIDGE_ERROR";
    }

    public BridgeException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造桥操作异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @param cause     根因异常
     */
    public BridgeException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 获取错误码。
     *
     * @return 错误码
     */
    public String getErrorCode() {
        return errorCode;
    }
}
