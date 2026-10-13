package org.nexus.gateway.subscription.claim;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 订阅周期扣款幂等认领（表 {@code subscription_charge_claim}）。
 *
 * <p>一行代表"某个订阅的某个账期"的扣款执行权被某个执行方认领。
 * 唯一键 {@code (subscription_id, cycle_key)} 是并发重复扣款的裁决点：
 * 同一订阅同一账期只能有一行成功落库，并发重复认领方会撞唯一键而放弃。</p>
 *
 * <p>{@code cycleKey} 取认领时的 {@code nextChargeAt}（本次到期的账期点）。
 * 扣款成功推进周期、dunning 失败重设下次扣款时间，均会让 key 变成新值，
 * 因此"失败后的重试"不会被上一轮的认领挡住（dunning 语义不受影响）。</p>
 *
 * @see SubscriptionChargeClaimService
 */
@Entity
@Table(name = "subscription_charge_claim",
        uniqueConstraints = @UniqueConstraint(name = "uk_scc_subscription_cycle",
                columnNames = {"subscription_id", "cycle_key"}))
public class SubscriptionChargeClaim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订阅业务编号。 */
    @Column(name = "subscription_id", nullable = false, length = 64)
    private String subscriptionId;

    /** 账期键：认领时的 nextChargeAt（本次到期的账期点）。 */
    @Column(name = "cycle_key", nullable = false, length = 64)
    private String cycleKey;

    /** 认领提交时间（独立短事务）。 */
    @Column(name = "claimed_at", nullable = false)
    private LocalDateTime claimedAt;

    protected SubscriptionChargeClaim() {
    }

    public SubscriptionChargeClaim(String subscriptionId, String cycleKey, LocalDateTime claimedAt) {
        this.subscriptionId = subscriptionId;
        this.cycleKey = cycleKey;
        this.claimedAt = claimedAt;
    }

    public Long getId() {
        return id;
    }

    public String getSubscriptionId() {
        return subscriptionId;
    }

    public String getCycleKey() {
        return cycleKey;
    }

    public LocalDateTime getClaimedAt() {
        return claimedAt;
    }
}
