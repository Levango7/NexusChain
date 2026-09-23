package org.nexus.gateway.limit;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 商户限额管理 API 端点。
 *
 * <ul>
 *   <li>{@code GET /api/v1/limits/config} — 获取商户限额配置</li>
 *   <li>{@code PUT /api/v1/limits/config} — 更新商户限额配置</li>
 *   <li>{@code GET /api/v1/limits/status} — 获取当前限额使用情况</li>
 *   <li>{@code POST /api/v1/limits/check} — 检查某笔交易是否满足限额</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/limits")
@Tag(name = "Limits", description = "商户限额：配置管理与限额检查")
public class LimitController {

    private static final Logger log = LoggerFactory.getLogger(LimitController.class);

    private final LimitCheckService limitCheckService;
    private final MerchantOwnershipGuard ownershipGuard;

    public LimitController(LimitCheckService limitCheckService,
                           MerchantOwnershipGuard ownershipGuard) {
        this.limitCheckService = limitCheckService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 获取商户限额配置。无配置时返回默认值（所有限额为 null，表示无限制）。
     *
     * @param httpRequest HTTP 请求
     * @return 限额配置
     */
    @Operation(summary = "获取商户限额配置")
    @GetMapping("/config")
    public ResponseEntity<MerchantLimitConfig> getConfig(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        MerchantLimitConfig config = limitCheckService.getConfig(merchantId);

        if (config == null) {
            // 无配置时返回默认值
            config = new MerchantLimitConfig();
            config.setMerchantId(merchantId);
            config.setActive(true);
        }

        return ResponseEntity.ok(config);
    }

    /**
     * 更新商户限额配置。
     *
     * @param request    更新请求体
     * @param httpRequest HTTP 请求
     * @return 更新后的配置
     */
    @Operation(summary = "更新商户限额配置")
    @PutMapping("/config")
    public ResponseEntity<MerchantLimitConfig> updateConfig(@RequestBody UpdateLimitConfigRequest request,
                                                              HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        MerchantLimitConfig config = limitCheckService.createOrUpdateConfig(
                merchantId,
                request.getSingleTransactionMinAmount(),
                request.getSingleTransactionMaxAmount(),
                request.getDailyAccumulatedMaxAmount(),
                request.getMonthlyAccumulatedMaxAmount(),
                request.getDailyMaxTransactionCount(),
                request.getMonthlyMaxTransactionCount());

        log.info("Limit config updated: merchantId={}, id={}", merchantId, config.getId());
        return ResponseEntity.ok(config);
    }

    /**
     * 获取当前限额使用情况。
     *
     * @param httpRequest HTTP 请求
     * @return 使用情况 + 配置
     */
    @Operation(summary = "获取当前限额使用情况")
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        BigDecimal dailyAccumulatedAmount = limitCheckService.getDailyAccumulatedAmount(merchantId);
        int dailyTransactionCount = limitCheckService.getDailyTransactionCount(merchantId);
        BigDecimal monthlyAccumulatedAmount = limitCheckService.getMonthlyAccumulatedAmount(merchantId);
        int monthlyTransactionCount = limitCheckService.getMonthlyTransactionCount(merchantId);
        MerchantLimitConfig config = limitCheckService.getConfig(merchantId);

        Map<String, Object> response = new HashMap<>();
        response.put("dailyAccumulatedAmount", dailyAccumulatedAmount);
        response.put("dailyTransactionCount", dailyTransactionCount);
        response.put("monthlyAccumulatedAmount", monthlyAccumulatedAmount);
        response.put("monthlyTransactionCount", monthlyTransactionCount);
        response.put("config", config != null ? config : defaultConfig(merchantId));

        return ResponseEntity.ok(response);
    }

    /**
     * 检查某笔交易是否满足限额。
     *
     * @param request    检查请求体（包含 amount）
     * @param httpRequest HTTP 请求
     * @return 限额检查结果
     */
    @Operation(summary = "检查某笔交易是否满足限额")
    @PostMapping("/check")
    public ResponseEntity<LimitCheckResult> checkTransaction(@RequestBody CheckLimitRequest request,
                                                              HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);

        if (request.getAmount() == null) {
            throw new IllegalArgumentException("amount is required");
        }

        LimitCheckResult result = limitCheckService.checkLimits(merchantId, request.getAmount());
        return ResponseEntity.ok(result);
    }

    // --- Helper ---

    private MerchantLimitConfig defaultConfig(Long merchantId) {
        MerchantLimitConfig config = new MerchantLimitConfig();
        config.setMerchantId(merchantId);
        config.setActive(true);
        return config;
    }

    // --- Request DTOs ---

    public static class UpdateLimitConfigRequest {
        private BigDecimal singleTransactionMinAmount;
        private BigDecimal singleTransactionMaxAmount;
        private BigDecimal dailyAccumulatedMaxAmount;
        private BigDecimal monthlyAccumulatedMaxAmount;
        private Integer dailyMaxTransactionCount;
        private Integer monthlyMaxTransactionCount;

        public BigDecimal getSingleTransactionMinAmount() { return singleTransactionMinAmount; }
        public void setSingleTransactionMinAmount(BigDecimal singleTransactionMinAmount) {
            this.singleTransactionMinAmount = singleTransactionMinAmount;
        }

        public BigDecimal getSingleTransactionMaxAmount() { return singleTransactionMaxAmount; }
        public void setSingleTransactionMaxAmount(BigDecimal singleTransactionMaxAmount) {
            this.singleTransactionMaxAmount = singleTransactionMaxAmount;
        }

        public BigDecimal getDailyAccumulatedMaxAmount() { return dailyAccumulatedMaxAmount; }
        public void setDailyAccumulatedMaxAmount(BigDecimal dailyAccumulatedMaxAmount) {
            this.dailyAccumulatedMaxAmount = dailyAccumulatedMaxAmount;
        }

        public BigDecimal getMonthlyAccumulatedMaxAmount() { return monthlyAccumulatedMaxAmount; }
        public void setMonthlyAccumulatedMaxAmount(BigDecimal monthlyAccumulatedMaxAmount) {
            this.monthlyAccumulatedMaxAmount = monthlyAccumulatedMaxAmount;
        }

        public Integer getDailyMaxTransactionCount() { return dailyMaxTransactionCount; }
        public void setDailyMaxTransactionCount(Integer dailyMaxTransactionCount) {
            this.dailyMaxTransactionCount = dailyMaxTransactionCount;
        }

        public Integer getMonthlyMaxTransactionCount() { return monthlyMaxTransactionCount; }
        public void setMonthlyMaxTransactionCount(Integer monthlyMaxTransactionCount) {
            this.monthlyMaxTransactionCount = monthlyMaxTransactionCount;
        }
    }

    public static class CheckLimitRequest {
        private BigDecimal amount;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }
}