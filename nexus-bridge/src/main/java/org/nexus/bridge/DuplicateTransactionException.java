package org.nexus.bridge;

/**
 * 重复交易异常，表示同一源链交易哈希（sourceTxHash）已被处理过。
 *
 * <p>跨链桥 {@code lock()} / {@code burn()} 在生成新桥交易前会先按
 * {@code sourceTxHash} 查询是否已存在记录，若已存在则抛出本异常，
 * 防止攻击者重放同一笔源链交易导致双倍 mint / unlock。</p>
 *
 * <p>属于幂等性失败（client 重试安全），HTTP 层建议映射为 409 Conflict。</p>
 *
 * @since 2.1.0
 */
public class DuplicateTransactionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码。 */
    private final String errorCode;

    /**
     * 构造重复交易异常，默认错误码 {@code "DUPLICATE_TX"}。
     *
     * @param message 错误描述
     */
    public DuplicateTransactionException(String message) {
        super(message);
        this.errorCode = "DUPLICATE_TX";
    }

    /**
     * 构造重复交易异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     */
    public DuplicateTransactionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * 构造重复交易异常。
     *
     * @param errorCode 错误码
     * @param message   错误描述
     * @param cause     根因异常
     */
    public DuplicateTransactionException(String errorCode, String message, Throwable cause) {
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