package org.nexus.gateway.dashboard;

import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商户门户仪表盘 REST API。
 *
 * <p>提供商户维度的交易汇总、结算状态、近期交易、渠道分布、风控摘要等数据端点。
 * 所有端点要求 MERCHANT 或 ADMIN 角色权限。</p>
 *
 * <p>P0-3 修复：所有端点验证路径中的 merchantId 与认证上下文中的 tenantId 一致，
 * 防止跨商户查看仪表盘数据。</p>
 *
 * <p>端点列表：
 * <ul>
 *   <li>GET /api/v1/merchants/{merchantId}/dashboard — 完整仪表盘数据</li>
 *   <li>GET /api/v1/merchants/{merchantId}/dashboard/transactions — 交易汇总</li>
 *   <li>GET /api/v1/merchants/{merchantId}/dashboard/settlement — 结算汇总</li>
 *   <li>GET /api/v1/merchants/{merchantId}/dashboard/recent — 近期交易列表</li>
 *   <li>GET /api/v1/merchants/{merchantId}/dashboard/risk — 风控摘要</li>
 * </ul></p>
 */
@RestController
@RequestMapping("/api/v1/merchants/{merchantId}/dashboard")
@PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")
public class MerchantDashboardController {

    private final MerchantDashboardService dashboardService;
    private final MerchantOwnershipGuard ownershipGuard;

    @Autowired
    public MerchantDashboardController(MerchantDashboardService dashboardService,
                                       MerchantOwnershipGuard ownershipGuard) {
        this.dashboardService = dashboardService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 获取完整仪表盘数据（交易汇总 + 结算汇总 + 近期交易 + 渠道分布 + 风控摘要）。
     *
     * <p>P0-3：验证路径 merchantId 与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 仪表盘完整数据
     */
    @GetMapping
    public MerchantDashboardService.DashboardData getDashboard(@PathVariable Long merchantId,
                                                               HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "dashboard", merchantId);
        return dashboardService.getDashboard(merchantId);
    }

    /**
     * 获取交易汇总数据。
     *
     * <p>P0-3：验证路径 merchantId 与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 交易汇总
     */
    @GetMapping("/transactions")
    public MerchantDashboardService.TransactionSummary getTransactionSummary(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "dashboard", merchantId);
        return dashboardService.getTransactionSummary(merchantId);
    }

    /**
     * 获取结算汇总数据。
     *
     * <p>P0-3：验证路径 merchantId 与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 结算汇总
     */
    @GetMapping("/settlement")
    public MerchantDashboardService.SettlementSummary getSettlementSummary(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "dashboard", merchantId);
        return dashboardService.getSettlementSummary(merchantId);
    }

    /**
     * 获取近期交易列表（最近 10 笔）。
     *
     * <p>P0-3：验证路径 merchantId 与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 近期交易列表
     */
    @GetMapping("/recent")
    public java.util.List<MerchantDashboardService.RecentTransaction> getRecentTransactions(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "dashboard", merchantId);
        return dashboardService.getRecentTransactions(merchantId);
    }

    /**
     * 获取风控摘要数据。
     *
     * <p>P0-3：验证路径 merchantId 与认证上下文一致。</p>
     *
     * @param merchantId  商户 ID
     * @param httpRequest HTTP 请求
     * @return 风控摘要
     */
    @GetMapping("/risk")
    public MerchantDashboardService.RiskSummary getRiskSummary(
            @PathVariable Long merchantId,
            HttpServletRequest httpRequest) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "dashboard", merchantId);
        return dashboardService.getRiskSummary(merchantId);
    }
}
