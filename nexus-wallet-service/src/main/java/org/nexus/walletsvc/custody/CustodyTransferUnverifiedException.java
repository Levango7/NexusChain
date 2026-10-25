package org.nexus.walletsvc.custody;

/**
 * 托管出金「签名已执行但结果不可核对」异常（2026-10-25，Board 裁决 20261025-0955 §五）。
 *
 * <p>与 {@code IllegalStateException}（确定未动账 → 回滚）的关键区别在传播方式：
 * {@code DefaultCustodyService} 的 {@code depositToCold} / {@code withdrawFromCold}
 * 以 {@code @Transactional(noRollbackFor = CustodyTransferUnverifiedException.class)}
 * 标注，本异常抛出时<b>冷热余额调整保持提交</b>——钱可能已经出去，回滚账面等于制造对账缺口。
 * 同时它仍然是异常，调用方绝不会被误导成「拿到了哈希、可以安全重试」。</p>
 *
 * <p>抛出前已在 {@code custody_transfer_pending} 表登记一条
 * {@code VERIFY_REQUIRED}（独立事务，不受本事务结局影响），人工处置入口即那条记录。</p>
 */
public class CustodyTransferUnverifiedException extends RuntimeException {

    private final String requestKey;

    private final String approvalId;

    public CustodyTransferUnverifiedException(String message, String requestKey, String approvalId) {
        super(message);
        this.requestKey = requestKey;
        this.approvalId = approvalId;
    }

    /** 本次托管出金的幂等键（= 发给执行通道的 requestId）。 */
    public String getRequestKey() {
        return requestKey;
    }

    /** 签名侧审批请求 ID，可能为 null。 */
    public String getApprovalId() {
        return approvalId;
    }
}
