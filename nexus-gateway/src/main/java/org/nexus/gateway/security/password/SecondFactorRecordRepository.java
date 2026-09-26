package org.nexus.gateway.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * 二次验证记录 JPA 仓储。
 *
 * <p>按 merchantId + expiresAt 查询未消费的验证记录，
 * 用于 OTP/TOTP/EMAIL 验证码的验证流程。</p>
 */
@Repository
public interface SecondFactorRecordRepository extends JpaRepository<SecondFactorRecord, Long> {

    /**
     * 查询商户在指定时间之后未消费的验证记录。
     *
     * @param merchantId 商户 ID
     * @param now        当前时间（仅返回 expires_at 在此之后的记录）
     * @return 未消费且未过期的验证记录列表
     */
    @Query("SELECT r FROM SecondFactorRecord r WHERE r.merchantId = :merchantId " +
           "AND r.consumed = false AND r.expiresAt > :now ORDER BY r.createdAt DESC")
    List<SecondFactorRecord> findActiveByMerchantId(@Param("merchantId") Long merchantId,
                                                     @Param("now") Instant now);
}