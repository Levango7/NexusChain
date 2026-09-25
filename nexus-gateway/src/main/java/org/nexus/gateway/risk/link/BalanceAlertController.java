package org.nexus.gateway.risk.link;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 余额预警配置 API 端点。
 *
 * <p>提供以下 API：</p>
 * <ul>
 *   <li>{@code POST /api/v1/risk/balance-alert/config} — 配置预警阈值</li>
 *   <li>{@code GET /api/v1/risk/balance-alert/config} — 查询预警配置</li>
 *   <li>{@code POST /api/v1/risk/balance-alert/check} — 手动触发余额预警检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/risk/balance-alert")
@Tag(name = "Balance Alert", description = "余额预警：阈值配置与预警检查")
public class BalanceAlertController {

    private static final Logger log = LoggerFactory.getLogger(BalanceAlertController.class);

    private final BalanceAlertService balanceAlertService;
    private final BalanceAlertConfigRepository configRepository;
    private final MerchantOwnershipGuard ownershipGuard;

    public BalanceAlertController(BalanceAlertService balanceAlertService,
                                   BalanceAlertConfigRepository configRepository,
                                   MerchantOwnershipGuard ownershipGuard) {
        this.balanceAlertService = balanceAlertService;
        this.configRepository = configRepository;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 配置预警阈值。
     *
     * @param body        请求体（warningThreshold, criticalThreshold, emergencyThreshold, notifyChannels）
     * @param httpRequest HTTP 请求
     * @return 预警配置
     */
    @Operation(summary = "配置余额预警阈值")
    @PostMapping("/config")
    public ResponseEntity<BalanceAlertConfig> configureAlert(
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        BigDecimal warningThreshold = new BigDecimal(body.get("warningThreshold").toString());
        BigDecimal criticalThreshold = new BigDecimal(body.get("criticalThreshold").toString());
        BigDecimal emergencyThreshold = new BigDecimal(body.get("emergencyThreshold").toString());
        String notifyChannels = body.get("notifyChannels") != null
                ? body.get("notifyChannels").toString()
                : null;

        BalanceAlertConfig config = balanceAlertService.configureAlert(
                merchantId, warningThreshold, criticalThreshold, emergencyThreshold, notifyChannels);
        return ResponseEntity.ok(config);
    }

    /**
     * 查询预警配置。
     *
     * @param httpRequest HTTP 请求
     * @return 预警配置（可能为空）
     */
    @Operation(summary = "查询余额预警配置")
    @GetMapping("/config")
    public ResponseEntity<BalanceAlertConfig> getAlertConfig(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        return ResponseEntity.of(configRepository.findByMerchantId(merchantId));
    }

    /**
     * 手动触发余额预警检查。
     *
     * @param httpRequest HTTP 请求
     * @return 当前预警级别（null 表示正常）
     */
    @Operation(summary = "手动触发余额预警检查")
    @PostMapping("/check")
    public ResponseEntity<Map<String, Object>> checkBalanceAlert(HttpServletRequest httpRequest) {
        Long merchantId = ownershipGuard.requireMerchantId(httpRequest);
        AlertLevel level = balanceAlertService.checkBalanceAndAlert(merchantId);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("merchantId", merchantId);
        result.put("alertLevel", level != null ? level.name() : "NORMAL");

        return ResponseEntity.ok(result);
    }
}