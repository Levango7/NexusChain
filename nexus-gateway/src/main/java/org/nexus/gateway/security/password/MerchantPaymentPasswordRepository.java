package org.nexus.gateway.security.password;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 商户支付密码 JPA 仓储。
 *
 * <p>按 merchantId + status 查询，支持查找活跃密码记录和锁定密码记录。</p>
 */
@Repository
public interface MerchantPaymentPasswordRepository extends JpaRepository<MerchantPaymentPassword, Long> {

    /**
     * 按商户 ID 查找支付密码记录。
     *
     * @param merchantId 商户 ID
     * @return 密码记录（每个商户最多一条）
     */
    Optional<MerchantPaymentPassword> findByMerchantId(Long merchantId);

    /**
     * 按商户 ID 和状态查找支付密码记录。
     *
     * @param merchantId 商户 ID
     * @param status     密码状态
     * @return 密码记录
     */
    Optional<MerchantPaymentPassword> findByMerchantIdAndStatus(Long merchantId, PasswordStatus status);
}