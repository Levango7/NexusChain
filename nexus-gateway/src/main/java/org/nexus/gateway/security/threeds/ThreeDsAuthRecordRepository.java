package org.nexus.gateway.security.threeds;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 3DS 认证记录 JPA 仓储。
 *
 * <p>支持按支付订单查询认证记录、按状态查询、以及超时扫描查询。</p>
 */
@Repository
public interface ThreeDsAuthRecordRepository extends JpaRepository<ThreeDsAuthRecord, Long> {

    /** 按支付订单 ID 查询认证记录。 */
    Optional<ThreeDsAuthRecord> findByPaymentOrderId(Long paymentOrderId);

    /** 按租户 + 商户 + 认证状态查询。 */
    List<ThreeDsAuthRecord> findByTenantIdAndMerchantIdAndAuthStatus(
            String tenantId, Long merchantId, AuthStatus authStatus);

    /** 按认证状态查询所有记录。 */
    List<ThreeDsAuthRecord> findByAuthStatus(AuthStatus authStatus);

    /**
     * 查询指定状态且发起时间早于 cutoff 的认证记录（用于超时扫描）。
     *
     * @param authStatus 认证状态（通常为 INITIATED）
     * @param cutoff     时间截止点（当前时间减去超时秒数）
     * @return 超时的认证记录列表
     */
    List<ThreeDsAuthRecord> findByAuthStatusAndInitiatedAtBefore(
            AuthStatus authStatus, LocalDateTime cutoff);
}