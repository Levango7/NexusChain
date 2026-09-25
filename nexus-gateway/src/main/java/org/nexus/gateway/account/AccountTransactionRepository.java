package org.nexus.gateway.account;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 账户流水 Repository。
 *
 * <p>提供按流水编号、账户编号、商户 ID、关联业务凭证等条件查询流水的方法。
 * 支持分页查询商户流水。</p>
 */
@Repository
public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, Long> {

    /**
     * 按流水编号查询。
     *
     * @param txNo 流水编号
     * @return 流水（可能为空）
     */
    Optional<AccountTransaction> findByTxNo(String txNo);

    /**
     * 按账户编号查询所有流水（按创建时间降序）。
     *
     * @param accountId 账户编号
     * @return 流水列表
     */
    List<AccountTransaction> findByAccountIdOrderByCreatedAtDesc(String accountId);

    /**
     * 按关联业务凭证查询流水。
     *
     * @param reference 关联业务凭证（orderNo/refundNo/escrowNo 等）
     * @return 流水列表
     */
    List<AccountTransaction> findByReference(String reference);

    /**
     * 按商户 ID 查询流水（分页，按创建时间降序）。
     *
     * @param merchantId 商户 ID
     * @param pageable 分页参数
     * @return 流水分页结果
     */
    Page<AccountTransaction> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    /**
     * 按商户 ID 和时间范围查询流水。
     *
     * @param merchantId 商户 ID
     * @param start 开始时间
     * @param end 结束时间
     * @return 流水列表
     */
    List<AccountTransaction> findByMerchantIdAndCreatedAtBetween(
            Long merchantId, LocalDateTime start, LocalDateTime end);

    /**
     * 按账户编号查询流水（分页，按创建时间降序）。
     *
     * @param accountId 账户编号
     * @param pageable 分页参数
     * @return 流水分页结果
     */
    Page<AccountTransaction> findByAccountIdOrderByCreatedAtDesc(String accountId, Pageable pageable);
}