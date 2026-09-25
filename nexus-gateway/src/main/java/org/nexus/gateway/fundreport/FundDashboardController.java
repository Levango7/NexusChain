package org.nexus.gateway.fundreport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资金管理仪表盘 REST API — 商户资金概览与监控。
 *
 * <p>提供资金概览、流水趋势、收支明细、异常监控等数据端点。
 * 路径前缀：{@code /api/v1/fund-dashboard}</p>
 *
 * <p>30 秒缓存：同一商户的仪表盘数据 30 秒内复用缓存。</p>
 */
@RestController
@RequestMapping("/api/v1/fund-dashboard")
@Tag(name = "FundDashboard", description = "资金管理仪表盘：资金概览/流水趋势/收支明细/异常监控")
public class FundDashboardController {

    private static final Logger log = LoggerFactory.getLogger(FundDashboardController.class);

    private final FundDashboardService fundDashboardService;

    public FundDashboardController(FundDashboardService fundDashboardService) {
        this.fundDashboardService = fundDashboardService;
    }

    /**
     * 获取完整资金管理仪表盘数据。
     *
     * <p>包含资金概览、流水趋势、收支明细、异常监控四个模块。
     * 30 秒缓存机制：同一商户 30 秒内的重复请求返回缓存数据。</p>
     *
     * @param merchantId 商户 ID
     * @return 仪表盘完整数据
     */
    @Operation(summary = "获取资金管理仪表盘完整数据")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}")
    public Map<String, Object> getDashboard(@PathVariable Long merchantId) {
        return fundDashboardService.getDashboard(merchantId);
    }

    /**
     * 获取资金概览 — 可用余额/冻结金额/备付金/账户状态。
     *
     * @param merchantId 商户 ID
     * @return 资金概览数据
     */
    @Operation(summary = "获取资金概览")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}/overview")
    public Map<String, Object> getFundOverview(@PathVariable Long merchantId) {
        return fundDashboardService.getFundOverview(merchantId);
    }

    /**
     * 获取流水趋势 — 近 N 日每日收支汇总。
     *
     * @param merchantId 商户 ID
     * @param days 统计天数（默认 7 天）
     * @return 流水趋势列表
     */
    @Operation(summary = "获取流水趋势（近 N 日）")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}/trend")
    public List<Map<String, Object>> getTransactionTrend(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "7") int days) {
        return fundDashboardService.getTransactionTrend(merchantId, days);
    }

    /**
     * 获取近期收支明细 — 最近 20 条流水记录。
     *
     * @param merchantId 商户 ID
     * @return 收支明细列表
     */
    @Operation(summary = "获取近期收支明细")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}/recent")
    public List<Map<String, Object>> getRecentTransactions(@PathVariable Long merchantId) {
        return fundDashboardService.getRecentTransactions(merchantId);
    }

    /**
     * 获取异常监控数据 — 预警标志/负余额/异常操作。
     *
     * @param merchantId 商户 ID
     * @return 异常监控数据
     */
    @Operation(summary = "获取异常监控数据")
    @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
    @GetMapping("/{merchantId}/anomaly")
    public Map<String, Object> getAnomalyMonitor(@PathVariable Long merchantId) {
        return fundDashboardService.getAnomalyMonitor(merchantId);
    }

    /**
     * 清除仪表盘缓存 — 强制下次请求重新加载数据。
     *
     * @param merchantId 商户 ID
     * @return 操作结果
     */
    @Operation(summary = "清除仪表盘缓存")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/cache/evict")
    public Map<String, Object> evictCache(@PathVariable Long merchantId) {
        fundDashboardService.evictCache(merchantId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("cacheEvicted", true);
        return result;
    }
}