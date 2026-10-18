package org.nexus.gateway.fundreport;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 自动提现规则 Repository。
 *
 * <p>提供按商户 ID、启用状态、频率等条件查询规则的方法。</p>
 */
@Repository
public interface AutoWithdrawRuleRepository extends JpaRepository<AutoWithdrawRule, Long> {

    /**
     * 按商户 ID 查询所有自动提现规则。
     *
     * @param merchantId 商户 ID
     * @return 规则列表
     */
    List<AutoWithdrawRule> findByMerchantId(Long merchantId);

    /**
     * 按商户 ID 和启用状态查询规则。
     *
     * @param merchantId 商户 ID
     * @param enabled 是否启用
     * @return 规则列表
     */
    List<AutoWithdrawRule> findByMerchantIdAndEnabled(Long merchantId, Boolean enabled);

    /**
     * 按启用状态和频率查询规则（调度器使用）。
     *
     * @param enabled 是否启用
     * @param frequency 提现频率
     * @return 规则列表
     */
    List<AutoWithdrawRule> findByEnabledAndFrequency(Boolean enabled, WithdrawFrequency frequency);

    /**
     * 按商户 ID 查询启用的规则。
     *
     * @param merchantId 商户 ID
     * @return 规则列表
     */
    Optional<AutoWithdrawRule> findByMerchantIdAndEnabledTrue(Long merchantId);

    /**
     * 加行级排它锁查询规则（批次⑤ P0 加固，2026-10-18）。
     *
     * <p>出款认领事务内使用：同一规则的并发执行（调度器与手工触发、多入口）
     * 在数据库行锁上串行化，配合 {@code payout_claim_reference} 的未决判定，
     * 保证"认领 + 扣账"原子且至多一次。</p>
     *
     * @param id 规则 ID
     * @return 加锁后的规则
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM AutoWithdrawRule r WHERE r.id = :id")
    Optional<AutoWithdrawRule> findByIdForUpdate(@Param("id") Long id);
}