package org.nexus.gateway.security.password;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 密码历史 JPA 仓储。
 *
 * <p>按 merchantId 查询最近 N 条密码历史记录，用于密码重复检查。</p>
 */
@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    /**
     * 查询商户最近 N 条密码历史（按创建时间倒序）。
     *
     * @param merchantId 商户 ID
     * @param pageable   分页参数（用于限制返回条数）
     * @return 密码历史列表
     */
    @Query("SELECT h FROM PasswordHistory h WHERE h.merchantId = :merchantId ORDER BY h.createdAt DESC")
    List<PasswordHistory> findRecentByMerchantId(@Param("merchantId") Long merchantId, Pageable pageable);

    /**
     * 查询商户最近 N 条密码历史（按创建时间倒序，使用 limit）。
     *
     * @param merchantId 商户 ID
     * @param limit      返回条数上限
     * @return 密码历史列表
     */
    @Query(value = "SELECT * FROM password_history WHERE merchant_id = :merchantId ORDER BY created_at DESC LIMIT :limit",
            nativeQuery = true)
    List<PasswordHistory> findTopNByMerchantIdOrderByCreatedAtDesc(@Param("merchantId") Long merchantId,
                                                                   @Param("limit") int limit);
}