package org.nexus.gateway.onboarding;

/**
 * 商户入驻申请状态枚举。
 *
 * <p>状态流转：</p>
 * <ul>
 *   <li>{@code PENDING} → {@code REVIEWING}：审核人员领取申请</li>
 *   <li>{@code REVIEWING} → {@code APPROVED}：审核通过，自动执行开通流程</li>
 *   <li>{@code REVIEWING} → {@code REJECTED}：审核拒绝，记录拒绝原因</li>
 *   <li>{@code APPROVED} → {@code SUSPENDED}：已开通的商户因违规被暂停</li>
 *   <li>{@code REJECTED} / {@code SUSPENDED}：终态（SUSPENDED 可申诉恢复）</li>
 * </ul>
 */
public enum ApplicationStatus {

    /** 待审核：申请已提交，等待审核人员领取。 */
    PENDING,

    /** 审核中：审核人员已领取，正在进行资质审核与 KYC 验证。 */
    REVIEWING,

    /** 已通过：审核通过，商户账号已自动开通。 */
    APPROVED,

    /** 已拒绝：审核未通过，记录拒绝原因。 */
    REJECTED,

    /** 已暂停：已开通的商户因违规或风险事件被暂停服务。 */
    SUSPENDED
}