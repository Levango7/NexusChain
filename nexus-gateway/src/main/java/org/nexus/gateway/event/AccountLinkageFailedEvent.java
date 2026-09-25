package org.nexus.gateway.event;

/**
 * 账户联动失败告警事件。
 *
 * <p>当 PaymentEventListener 调用 AccountService 进行余额联动（支付确认增额 / 退款扣额）
 * 失败时发布此事件，供告警系统消费并触发补偿流程。联动失败不阻断 webhook 投递，
 * 但必须留下告警痕迹以确保资金账务最终一致。</p>
 *
 * <p>H-10 修复（Wave 10）：替代原 catch 块中仅 log.error 的做法，
 * 引入事件驱动告警机制，使运维/补偿系统能自动感知并处理账户联动异常。</p>
 */
public class AccountLinkageFailedEvent extends PaymentEvent {

    /** 联动类型：支付确认增额 或 退款扣额 */
    public enum LinkageType {
        CREDIT_ON_PAYMENT,
        DEBIT_ON_REFUND
    }

    /** 触发联动的业务凭证（orderNo 或 refundNo） */
    private final String businessReference;
    /** 联动金额 */
    private final String amount;
    /** 失败原因 */
    private final String failureReason;
    /** 联动类型 */
    private final String linkageType;

    public AccountLinkageFailedEvent(Object source, Long merchantId, String businessReference,
                                      String amount, String failureReason, LinkageType linkageType) {
        super(source, null, businessReference, merchantId);
        this.businessReference = businessReference;
        this.amount = amount;
        this.failureReason = failureReason;
        this.linkageType = linkageType.name();
    }

    @Override
    public String getEventType() { return "ACCOUNT_LINKAGE_FAILED"; }

    public String getBusinessReference() { return businessReference; }
    public String getAmount() { return amount; }
    public String getFailureReason() { return failureReason; }
    public String getLinkageType() { return linkageType; }
}