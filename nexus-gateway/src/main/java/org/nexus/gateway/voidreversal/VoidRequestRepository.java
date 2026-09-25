package org.nexus.gateway.voidreversal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 撤销请求 Repository。
 *
 * <p>提供按撤销编号、订单 ID、状态、租户 ID 等条件查询撤销请求的方法。</p>
 */
@Repository
public interface VoidRequestRepository extends JpaRepository<VoidRequest, Long> {

    /**
     * 按撤销编号查询。
     *
     * @param voidNo 撤销编号
     * @return 撤销请求（可能为空）
     */
    Optional<VoidRequest> findByVoidNo(String voidNo);

    /**
     * 按订单 ID 查询所有撤销请求。
     *
     * @param orderId 订单 ID
     * @return 撤销请求列表
     */
    List<VoidRequest> findByOrderId(Long orderId);

    /**
     * 按撤销状态查询。
     *
     * @param status 撤销状态
     * @return 撤销请求列表
     */
    List<VoidRequest> findByStatus(VoidStatus status);

    /**
     * 按租户 ID 查询所有撤销请求。
     *
     * @param tenantId 租户 ID
     * @return 撤销请求列表
     */
    List<VoidRequest> findByTenantId(String tenantId);

    /**
     * 按订单 ID 和状态查询（用于幂等校验：检查订单是否已有撤销请求）。
     *
     * @param orderId 订单 ID
     * @param status 撤销状态
     * @return 撤销请求列表
     */
    List<VoidRequest> findByOrderIdAndStatus(Long orderId, VoidStatus status);
}