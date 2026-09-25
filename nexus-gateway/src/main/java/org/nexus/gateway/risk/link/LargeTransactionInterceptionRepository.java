package org.nexus.gateway.risk.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 大额交易拦截 Repository。
 *
 * <p>提供按拦截 ID、商户 ID、状态、超时时间等条件查询的方法。</p>
 */
@Repository
public interface LargeTransactionInterceptionRepository extends JpaRepository<LargeTransactionInterception, Long> {

    /**
     * 按拦截记录 ID 查询。
     *
     * @param interceptionId 拦截记录唯一标识
     * @return 拦截记录（可能为空）
     */
    Optional<LargeTransactionInterception> findByInterceptionId(String interceptionId);

    /**
     * 按商户 ID 查询所有拦截记录。
     *
     * @param merchantId 商户 ID
     * @return 拦截记录列表
     */
    List<LargeTransactionInterception> findByMerchantId(Long merchantId);

    /**
     * 按拦截状态查询。
     *
     * @param status 拦截状态
     * @return 拦截记录列表
     */
    List<LargeTransactionInterception> findByInterceptionStatus(InterceptionStatus status);

    /**
     * 查询已超时但尚未升级告警的拦截记录 — 供定时任务使用。
     *
     * @param status    拦截状态（PENDING_REVIEW）
     * @param timeoutAt 超时时间阈值
     * @return 需要升级告警的拦截记录列表
     */
    List<LargeTransactionInterception> findByInterceptionStatusAndTimeoutAtBefore(
            InterceptionStatus status, LocalDateTime timeoutAt);
}