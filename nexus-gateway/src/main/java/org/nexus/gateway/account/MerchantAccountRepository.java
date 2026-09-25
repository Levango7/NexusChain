package org.nexus.gateway.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

/**
 * 商户虚拟账户 Repository。
 *
 * <p>提供按商户 ID、账户类型、状态等条件查询账户的方法。
 * {@link #findByAccountIdForUpdate} 使用悲观写锁保证并发安全。</p>
 */
@Repository
public interface MerchantAccountRepository extends JpaRepository<MerchantAccount, Long> {

    /**
     * 按账户编号查询。
     *
     * @param accountId 账户编号
     * @return 账户（可能为空）
     */
    Optional<MerchantAccount> findByAccountId(String accountId);

    /**
     * 按商户 ID 和账户类型查询。
     *
     * @param merchantId 商户 ID
     * @param accountType 账户类型
     * @return 账户（可能为空）
     */
    Optional<MerchantAccount> findByMerchantIdAndAccountType(Long merchantId, AccountType accountType);

    /**
     * 按商户 ID 查询所有账户。
     *
     * @param merchantId 商户 ID
     * @return 账户列表
     */
    List<MerchantAccount> findByMerchantId(Long merchantId);

    /**
     * 按账户状态查询。
     *
     * @param status 账户状态
     * @return 账户列表
     */
    List<MerchantAccount> findByStatus(AccountStatus status);

    /**
     * 按租户 ID 查询所有账户。
     *
     * @param tenantId 租户 ID
     * @return 账户列表
     */
    List<MerchantAccount> findByTenantId(String tenantId);

    /**
     * 悲观写锁查询账户 — 防止并发修改余额。
     *
     * <p>使用 {@code SELECT FOR UPDATE} 锁定账户行，保证同一时刻只有一个事务
     * 能读取并修改该账户的余额。在充值/提现/冻结等余额变更场景下使用。</p>
     *
     * <p>注意：方法名不能用 {@code findByAccountIdForUpdate}，因为 Spring Data JPA
     * 会将 {@code ForUpdate} 解析为属性路径，导致 {@code PropertyReferenceException}。
     * 使用 {@code @Query} 显式定义 JPQL 避免此问题。</p>
     *
     * @param accountId 账户编号
     * @return 锁定后的账户（可能为空）
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MerchantAccount m WHERE m.accountId = :accountId")
    Optional<MerchantAccount> findLockedByAccountId(@Param("accountId") String accountId);
}