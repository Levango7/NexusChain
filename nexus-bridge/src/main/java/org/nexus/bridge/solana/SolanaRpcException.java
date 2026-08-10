package org.nexus.bridge.solana;

/**
 * Solana RPC 调用异常。
 *
 * <p>携带错误码，便于调用方区分不同类型的失败原因：
 * <ul>
 *   <li>{@code HTTP_ERROR} — HTTP 状态码非 200</li>
 *   <li>{@code RPC_ERROR_<code>} — Solana JSON RPC 返回 error 对象</li>
 *   <li>{@code SEND_TX_FAILED} — sendTransaction 返回非文本结果</li>
 *   <li>{@code SEND_TX_IO_ERROR} — sendTransaction 网络 IO 错误</li>
 * </ul>
 *
 * @since 2.0.0
 */
public class SolanaRpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码。 */
    private final String errorCode;

    /**
     * 构造 Solana RPC 异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     */
    public SolanaRpcException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造 Solana RPC 异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @param cause     根因异常
     */
    public SolanaRpcException(String errorCode, String message, Throwable cause) {
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