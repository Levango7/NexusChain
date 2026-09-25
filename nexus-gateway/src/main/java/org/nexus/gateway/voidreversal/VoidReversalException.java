package org.nexus.gateway.voidreversal;

/**
 * 撤销/冲正业务异常 — 携带错误码，替代字符串匹配的错误码映射方式。
 *
 * <p>错误码定义：</p>
 * <ul>
 *   <li>ORDER_NOT_FOUND — 订单不存在</li>
 *   <li>ORDER_NOT_PAID — 订单状态非 PAID</li>
 *   <li>VOID_WINDOW_EXPIRED — 超过撤销窗口</li>
 *   <li>REFUND_EXISTS — 已有退款的订单不能撤销</li>
 *   <li>VOID_REQUEST_EXISTS — 订单已有进行中的撤销请求</li>
 *   <li>REVERSAL_NOT_FOUND — 冲正请求不存在</li>
 *   <li>REVERSAL_ALREADY_PROCESSED — 冲正请求已处理</li>
 *   <li>INSUFFICIENT_BALANCE — 余额不足</li>
 * </ul>
 */
public class VoidReversalException extends RuntimeException {

    private final String errorCode;

    public VoidReversalException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}