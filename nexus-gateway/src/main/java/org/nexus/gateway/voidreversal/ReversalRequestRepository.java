package org.nexus.gateway.voidreversal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 冲正请求 Repository。
 *
 * <p>提供按冲正编号、订单 ID、状态、冲正类型、租户 ID 等条件查询冲正请求的方法。</p>
 */
@Repository
public interface ReversalRequestRepository extends JpaRepository<ReversalRequest, Long> {

    /**
     * 按冲正编号查询。
     *
     * @param reversalNo 冲正编号
     * @return 冲正请求（可能为空）
     */
    Optional<ReversalRequest> findByReversalNo(String reversalNo);

    /**
     * 按订单 ID 查询所有冲正请求。
     *
     * @param orderId 订单 ID
     * @return 冲正请求列表
     */
    List<ReversalRequest> findByOrderId(Long orderId);

    /**
     * 按冲正状态查询。
     *
     * @param status 冲正状态
     * @return 冲正请求列表
     */
    List<ReversalRequest> findByStatus(ReversalStatus status);

    /**
     * 按冲正类型查询。
     *
     * @param reversalType 冲正类型
     * @return 冲正请求列表
     */
    List<ReversalRequest> findByReversalType(ReversalType reversalType);

    /**
     * 按租户 ID 查询所有冲正请求。
     *
     * @param tenantId 租户 ID
     * @return 冲正请求列表
     */
    List<ReversalRequest> findByTenantId(String tenantId);

    /**
     * 按订单 ID 和状态查询（用于幂等校验：检查订单是否已有冲正请求）。
     *
     * @param orderId 订单 ID
     * @param status 冲正状态
     * @return 冲正请求列表
     */
    List<ReversalRequest> findByOrderIdAndStatus(Long orderId, ReversalStatus status);
}