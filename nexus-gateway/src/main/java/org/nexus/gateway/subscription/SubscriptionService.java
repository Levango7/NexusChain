package org.nexus.gateway.subscription;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 订阅服务接口（P4-T8 订阅与循环计费引擎）。
 *
 * <p>覆盖订阅全生命周期：创建（含试用期）、取消、升级（按比例即时扣款）、
 * 降级（下个周期生效）、查询、周期扣款处理。扣款通过 {@code RoutingEngine}
 * 选择最优扣款通道，由 {@code PaymentService} 执行。</p>
 *
 * <p>注意：本接口位于 {@code org.nexus.gateway.subscription} 包，与 P1 简单
 * 订阅接口 {@code org.nexus.gateway.SubscriptionService} 不同，类名相同不冲突。</p>
 */
public interface SubscriptionService {

    /**
     * 创建订阅。
     *
     * <p>如果计划有试用期（{@code trialPeriodDays > 0}），订阅状态为 {@link SubscriptionStatus#TRIAL}，
     * 当前周期开始时间为创建时间，试用期结束时间为创建时间 + trialPeriodDays，
     * 下次扣款时间为试用期结束时间；否则状态为 {@link SubscriptionStatus#ACTIVE}，
     * 下次扣款时间为当前周期结束时间。</p>
     *
     * @param merchantId   商户 ID
     * @param customerId   客户 ID
     * @param payerAddress 付款人钱包地址
     * @param payeeAddress 收款人钱包地址
     * @param planId       订阅计划业务编号
     * @return 创建的订阅实体
     */
    Subscription createSubscription(Long merchantId, String customerId,
                                    String payerAddress, String payeeAddress,
                                    String planId);

    /**
     * 取消订阅。
     *
     * <p>状态改为 {@link SubscriptionStatus#CANCELLED}，记录 cancelledAt。
     * 已取消的订阅再次取消幂等返回。</p>
     *
     * @param subscriptionId 订阅业务编号
     * @return 更新后的订阅实体
     */
    Subscription cancelSubscription(String subscriptionId);

    /**
     * 升级订阅（按比例计算差价，立即生效）。
     *
     * <p>计算当前周期剩余天数的按比例差价，立即通过扣款通道收取差价，
     * 然后切换到新计划。新周期窗口保持不变（currentPeriodEnd 不变），
     * 下次扣款时间不变。差价为 0 时不扣款仅切换计划。</p>
     *
     * @param subscriptionId 订阅业务编号
     * @param newPlanId      新计划业务编号
     * @return 升级后的订阅实体
     */
    Subscription upgradeSubscription(String subscriptionId, String newPlanId);

    /**
     * 降级订阅（下个周期生效）。
     *
     * <p>不立即扣款，仅记录待切换的新计划，当前周期结束后下次扣款时
     * 切换到新计划并按新计划金额扣款。本实现通过将新计划 ID 暂存于
     * {@code lastTxHash} 字段（前缀 "PENDING_DOWNGRADE:"）实现，
     * 周期扣款时检测并应用。</p>
     *
     * @param subscriptionId 订阅业务编号
     * @param newPlanId      新计划业务编号
     * @return 降级后的订阅实体
     */
    Subscription downgradeSubscription(String subscriptionId, String newPlanId);

    /**
     * 查询订阅。
     *
     * @param subscriptionId 订阅业务编号
     * @return 订阅实体（Optional）
     */
    Optional<Subscription> getSubscription(String subscriptionId);

    /**
     * 处理单个订阅的周期扣款。
     *
     * <p>由 {@code BillingCycleScheduler} 调用。通过 {@code RoutingEngine}
     * 选择最优扣款通道，执行扣款：</p>
     * <ul>
     *   <li>成功：更新 currentPeriodStart/End，重置 dunningCount，chargedCount++</li>
     *   <li>失败：进入 dunning 流程（由 {@code DunningManager} 处理）</li>
     * </ul>
     *
     * @param subscriptionId 订阅业务编号
     * @return 扣款交易哈希，失败返回 null
     */
    String processBillingCycle(String subscriptionId);

    /**
     * 处理试用期到期转正。
     *
     * <p>当订阅处于 {@link SubscriptionStatus#TRIAL} 且 trialEnd 已过时，
     * 转为 {@link SubscriptionStatus#ACTIVE} 并执行首次扣款。</p>
     *
     * @param subscriptionId 订阅业务编号
     * @return 转正成功返回 true，无需转正返回 false
     */
    boolean convertTrialToActive(String subscriptionId);
}