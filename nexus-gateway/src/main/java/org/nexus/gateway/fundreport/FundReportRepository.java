package org.nexus.gateway.fundreport;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 资金报表 Repository。
 *
 * <p>提供按报表编号、商户 ID、报表类型等条件查询报表的方法。</p>
 */
@Repository
public interface FundReportRepository extends JpaRepository<FundReport, Long> {

    /**
     * 按报表编号查询。
     *
     * @param reportNo 报表编号
     * @return 报表（可能为空）
     */
    Optional<FundReport> findByReportNo(String reportNo);

    /**
     * 按商户 ID 查询所有报表（分页，按创建时间降序）。
     *
     * @param merchantId 商户 ID
     * @param pageable 分页参数
     * @return 报表分页结果
     */
    Page<FundReport> findByMerchantIdOrderByCreatedAtDesc(Long merchantId, Pageable pageable);

    /**
     * 按商户 ID 和报表类型查询报表。
     *
     * @param merchantId 商户 ID
     * @param reportType 报表类型
     * @return 报表列表
     */
    List<FundReport> findByMerchantIdAndReportType(Long merchantId, ReportType reportType);

    /**
     * 按商户 ID 和时间范围查询报表。
     *
     * @param merchantId 商户 ID
     * @param start 开始时间
     * @param end 结束时间
     * @return 报表列表
     */
    List<FundReport> findByMerchantIdAndCreatedAtBetween(
            Long merchantId, LocalDateTime start, LocalDateTime end);
}