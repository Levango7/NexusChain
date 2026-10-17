package org.nexus.gateway.account;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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
     * 按账户类型查询所有账户。
     *
     * @param accountType 账户类型
     * @return 账户列表
     */
    List<MerchantAccount> findByAccountType(AccountType accountType);

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

    /**
     * alertFlag 原子跃迁（CAS）：把 {@code oldFlag} 翻成 {@code newFlag}，只有真的翻动的那一方返回 1。
     *
     * <p>为什么不用 {@code findBy… → setAlertFlag → save}：那是 check-then-act。prod {@code replicaCount=3}
     * 且余额变更走事件驱动（{@code BalanceAlertListener} 是 {@code @Async}），三副本会各自读到同一个旧值、
     * 各自判定"要跃迁"、各自发一次通知 ⇒ 同一级别跃迁通知 3 份。@Version 乐观锁在这里救不了：
     * 它让输者在<b>提交时</b>抛异常，而通知早在<b>提交前</b>就发出去了。</p>
     *
     * <p>返回 0 表示别的副本已经翻过（旧值不再是 oldFlag）——调用方据此<b>不发</b>通知。</p>
     *
     * <p>bulk update 绕过 JPA 的 @Version 检查，故这里显式 {@code version = version + 1}，
     * 让同账户上其它实体级写入方的乐观锁仍然成立（不然后者会覆盖掉这次跃迁却自以为赢了）。
     * 同时手工推 {@code updated_at}：该列 NOT NULL，而 bulk update 不触发实体监听器。</p>
     *
     * <p>{@code clearAutomatically}：语句执行后持久化上下文里的旧实体已失真，必须清掉，
     * 否则同事务后续读取会拿到未跃迁的 alertFlag（"自己骗自己"）。</p>
     *
     * @param id      账户主键
     * @param oldFlag 期望的当前值（非 null；null 走 {@link #compareAndSetAlertFlagFromNull}）
     * @param newFlag 目标值，可为 null（表示预警解除）
     * @param now     本次跃迁时间
     * @return 被翻动的行数：1 = 本次调用完成了跃迁，0 = 已被他人翻走
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MerchantAccount a SET a.alertFlag = :newFlag, a.updatedAt = :now, a.version = a.version + 1 "
            + "WHERE a.id = :id AND a.alertFlag = :oldFlag")
    int compareAndSetAlertFlag(@Param("id") Long id, @Param("oldFlag") String oldFlag,
                               @Param("newFlag") String newFlag, @Param("now") LocalDateTime now);

    /**
     * {@link #compareAndSetAlertFlag} 的"旧值为 NULL"分支——JPQL 里 {@code a.alertFlag = NULL}
     * 恒不成立（SQL 三值逻辑），所以 NULL 起点必须单列一条 {@code IS NULL} 谓词。
     *
     * @param id      账户主键
     * @param newFlag 目标值（这条路径上不为 null：null→null 不算跃迁，调用方不会进来）
     * @param now     本次跃迁时间
     * @return 1 = 本次调用完成了跃迁，0 = 已被他人翻走
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE MerchantAccount a SET a.alertFlag = :newFlag, a.updatedAt = :now, a.version = a.version + 1 "
            + "WHERE a.id = :id AND a.alertFlag IS NULL")
    int compareAndSetAlertFlagFromNull(@Param("id") Long id, @Param("newFlag") String newFlag,
                                       @Param("now") LocalDateTime now);
}