package org.nexus.gateway.fundreport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
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
 *
 * <h3>鉴权（2026-10-25 修复跨商户越权）</h3>
 *
 * <p><b>修复前</b>：本类所有端点只要求
 * {@code @PreAuthorize("hasRole('MERCHANT') or hasRole('ADMIN')")}，
 * 然后把路径里的 {@code merchantId} 直接交给 Service —— <b>没有任何归属校验</b>。
 * 用 standalone MockMvc 实测：调用方商户 200 请求 {@code /api/v1/fund-dashboard/999}
 * 得到 <b>200 + 商户 999 的数据</b>（期望 403）。同型的还有账户余额与提现端点。</p>
 *
 * <p><b>修复方式</b>：对齐本仓已验证的商户侧范式（{@code PaymentController}）——
 * 商户身份由 {@code ApiKeyInterceptor} 解析并写入 {@code nexus.merchantId} 属性，
 * 控制器用 {@link MerchantOwnershipGuard} 比对路径 {@code merchantId}。</p>
 *
 * <p>为什么去掉 {@code @PreAuthorize("hasRole('MERCHANT')")}：API Key 调用方
 * 在 Spring Security 里<b>没有 Authentication</b>（{@code ApiKeyInterceptor} 只设
 * request 属性，全仓只有 {@code JwtAuthenticationFilter} 设 SecurityContext），
 * 因此 {@code hasRole('MERCHANT')} 对真实商户恒假、恒 403。保留它等于"修好越权、
 * 但页面永远打不开"。</p>
 *
 * <p><b>已知边界</b>：{@code ADMIN}（只有 JWT、没有商户 API Key）调用这些端点仍会被
 * {@code ApiKeyInterceptor} 挡成 401 —— 这是<b>既有</b>问题（同
 * {@code RiskLinkController} 修前的形状），需把路径从拦截器排除并改走 JWT 鉴权，
 * 属独立变更，本类不引入。</p>
 */
@RestController
@RequestMapping("/api/v1/fund-dashboard")
@Tag(name = "FundDashboard", description = "资金管理仪表盘：资金概览/流水趋势/收支明细/异常监控")
public class FundDashboardController {

    private static final Logger log = LoggerFactory.getLogger(FundDashboardController.class);

    private final FundDashboardService fundDashboardService;
    private final MerchantOwnershipGuard ownershipGuard;

    public FundDashboardController(FundDashboardService fundDashboardService,
                                   MerchantOwnershipGuard ownershipGuard) {
        this.fundDashboardService = fundDashboardService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 归属校验：路径 {@code merchantId} 必须等于 {@code ApiKeyInterceptor} 解析出的调用方商户。
     *
     * <p>fail-closed：属性缺失（未经拦截器、排除名单配错、测试未装配）一律 403，
     * 不解释为"无需校验"。</p>
     */
    private void requireOwnMerchant(HttpServletRequest request, Long merchantId) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "fund-dashboard", merchantId);
    }

    /**
     * 获取完整资金管理仪表盘数据。
     *
     * <p>包含资金概览、流水趋势、收支明细、异常监控四个模块。
     * 30 秒缓存机制：同一商户 30 秒内的重复请求返回缓存数据。</p>
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 仪表盘完整数据
     */
    @Operation(summary = "获取资金管理仪表盘完整数据")
    @GetMapping("/{merchantId}")
    public Map<String, Object> getDashboard(@PathVariable Long merchantId,
                                            HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        return fundDashboardService.getDashboard(merchantId);
    }

    /**
     * 获取资金概览 — 可用余额/冻结金额/备付金/账户状态。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 资金概览数据
     */
    @Operation(summary = "获取资金概览")
    @GetMapping("/{merchantId}/overview")
    public Map<String, Object> getFundOverview(@PathVariable Long merchantId,
                                               HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        return fundDashboardService.getFundOverview(merchantId);
    }

    /**
     * 获取流水趋势 — 近 N 日每日收支汇总。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param days       统计天数（默认 7 天，上限 90）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 流水趋势列表
     */
    @Operation(summary = "获取流水趋势（近 N 日）")
    @GetMapping("/{merchantId}/trend")
    public List<Map<String, Object>> getTransactionTrend(
            @PathVariable Long merchantId,
            @RequestParam(defaultValue = "7") int days,
            HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        // 趋势天数上限验证
        days = Math.min(days, 90);
        return fundDashboardService.getTransactionTrend(merchantId, days);
    }

    /**
     * 获取近期收支明细 — 最近 20 条流水记录。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 收支明细列表
     */
    @Operation(summary = "获取近期收支明细")
    @GetMapping("/{merchantId}/recent")
    public List<Map<String, Object>> getRecentTransactions(@PathVariable Long merchantId,
                                                           HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        return fundDashboardService.getRecentTransactions(merchantId);
    }

    /**
     * 获取异常监控数据 — 预警标志/负余额/异常操作。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param request    HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 异常监控数据
     */
    @Operation(summary = "获取异常监控数据")
    @GetMapping("/{merchantId}/anomaly")
    public Map<String, Object> getAnomalyMonitor(@PathVariable Long merchantId,
                                                 HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        return fundDashboardService.getAnomalyMonitor(merchantId);
    }

    /**
     * 清除仪表盘缓存 — 强制下次请求重新加载数据。
     *
     * <p>管理动作，仍限 {@code ADMIN}（JWT）。<b>注意</b>：该端点位于
     * {@code /api/v1/**} 下、未被 {@code ApiKeyInterceptor} 排除，因此只有 JWT
     * 的管理员会被挡成 401 —— 与修复前行为一致（本类未改动其鉴权），
     * 属已知边界，见类级 javadoc。</p>
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
