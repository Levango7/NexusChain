package org.nexus.gateway.export;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据导出请求 Repository。
 *
 * <p>提供按商户、状态、时间范围查询导出请求等方法。</p>
 */
@Repository
public interface DataExportRequestRepository extends JpaRepository<DataExportRequest, Long> {

    /**
     * 按商户 ID 查询导出请求（按创建时间倒序）。
     *
     * @param merchantId 商户 ID
     * @return 导出请求列表
     */
    List<DataExportRequest> findByMerchantIdOrderByCreatedAtDesc(Long merchantId);

    /**
     * 按商户 ID 和状态查询导出请求。
     *
     * @param merchantId 商户 ID
     * @param status     请求状态
     * @return 导出请求列表
     */
    List<DataExportRequest> findByMerchantIdAndStatus(Long merchantId, DataExportRequest.Status status);

    /**
     * 按状态查询导出请求。
     *
     * @param status 请求状态
     * @return 导出请求列表
     */
    List<DataExportRequest> findByStatus(DataExportRequest.Status status);

    /**
     * 查询已完成且完成时间早于指定时间的导出请求（用于过期清理）。
     *
     * @param status      请求状态
     * @param completedBefore 完成时间阈值
     * @return 导出请求列表
     */
    List<DataExportRequest> findByStatusAndCompletedAtBefore(DataExportRequest.Status status,
                                                              LocalDateTime completedBefore);
}