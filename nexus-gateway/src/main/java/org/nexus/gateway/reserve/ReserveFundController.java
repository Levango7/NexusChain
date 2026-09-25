package org.nexus.gateway.reserve;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.nexus.gateway.account.AccountType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 备付金管理 REST API — 备付金配置、余额查询、手动补充、监控预警。
 *
 * <p>路径前缀：{@code /api/v1/reserve}</p>
 *
 * <p>提供以下功能：</p>
 * <ul>
 *   <li>备付金配置 CRUD（创建/更新/查询）</li>
 *   <li>备付金余额查询</li>
 *   <li>手动补充备付金</li>
 *   <li>监控预警检查</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reserve")
@Tag(name = "ReserveFund", description = "备付金管理：配置/余额查询/补充/监控预警")
public class ReserveFundController {

    private static final Logger log = LoggerFactory.getLogger(ReserveFundController.class);

    private final ReserveFundService reserveFundService;

    public ReserveFundController(ReserveFundService reserveFundService) {
        this.reserveFundService = reserveFundService;
    }

    // === 配置管理 ===

    /**
     * 创建或更新备付金配置。
     *
     * @param merchantId 商户ID
     * @param body 配置请求体
     * @return 200 + 配置信息
     */
    @Operation(summary = "创建或更新备付金配置")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/configs/{merchantId}")
    public ResponseEntity<Map<String, Object>> createOrUpdateConfig(
            @PathVariable Long merchantId,
            @RequestBody ReserveConfigRequest body) {
        ReserveConfig config = buildReserveConfig(body);
        config = reserveFundService.createOrUpdateConfig(merchantId, config);

        return ResponseEntity.ok(toConfigMap(config));
    }

    /**
     * 查询商户备付金配置。
     *
     * @param merchantId 商户ID
     * @return 200 + 配置信息
     */
    @Operation(summary = "查询商户备付金配置")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
    @GetMapping("/configs/{merchantId}")
    public ResponseEntity<Map<String, Object>> getConfig(@PathVariable Long merchantId) {
        ReserveConfig config = reserveFundService.getConfig(merchantId)
                .orElseThrow(() -> new IllegalArgumentException("备付金配置不存在: merchantId=" + merchantId));

        return ResponseEntity.ok(toConfigMap(config));
    }

    // === 余额查询 ===

    /**
     * 查询商户备付金余额。
     *
     * @param merchantId 商户ID
     * @return 200 + 余额信息
     */
    @Operation(summary = "查询商户备付金余额")
    @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")
    @GetMapping("/{merchantId}/balance")
    public ResponseEntity<Map<String, Object>> getReserveBalance(@PathVariable Long merchantId) {
        BigDecimal balance = reserveFundService.getReserveBalance(merchantId);
        String alertFlag = reserveFundService.getConfig(merchantId)
                .map(ReserveConfig::getAlertFlag)
                .orElse(null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("reserveBalance", balance);
        result.put("alertFlag", alertFlag);

        return ResponseEntity.ok(result);
    }

    // === 手动补充 ===

    /**
     * 手动补充备付金。
     *
     * @param merchantId 商户ID
     * @param body 补充请求体
     * @return 200 + 补充后的余额信息
     */
    @Operation(summary = "手动补充备付金")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/replenish")
    public ResponseEntity<Map<String, Object>> manualReplenish(
            @PathVariable Long merchantId,
            @RequestBody ReplenishRequest body) {
        BigDecimal amount = new BigDecimal(body.getAmount());
        AccountType fromAccountType = body.getFromAccountType() != null
                ? AccountType.valueOf(body.getFromAccountType())
                : AccountType.BALANCE;

        var account = reserveFundService.manualReplenish(merchantId, amount, fromAccountType);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("accountId", account.getAccountId());
        result.put("reserveBalance", account.getBalance());
        result.put("status", account.getStatus().name());

        return ResponseEntity.ok(result);
    }

    // === 监控预警 ===

    /**
     * 检查商户备付金预警级别。
     *
     * @param merchantId 商户ID
     * @return 200 + 预警级别
     */
    @Operation(summary = "检查商户备付金预警级别")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/check-alert")
    public ResponseEntity<Map<String, Object>> checkAlert(@PathVariable Long merchantId) {
        String alertFlag = reserveFundService.checkAndAlert(merchantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("alertFlag", alertFlag);

        return ResponseEntity.ok(result);
    }

    /**
     * 检查所有启用监控的商户备付金预警级别。
     *
     * @return 200 + 预警结果列表
     */
    @Operation(summary = "检查所有商户备付金预警级别")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/check-all-alerts")
    public ResponseEntity<Map<String, Object>> checkAllAlerts() {
        List<ReserveFundService.AlertResult> results = reserveFundService.checkAllMonitored();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", results.size());
        response.put("alerts", results.stream()
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("merchantId", r.getMerchantId());
                    m.put("alertFlag", r.getAlertFlag());
                    return m;
                })
                .collect(Collectors.toList()));

        return ResponseEntity.ok(response);
    }

    // === 异常处理 ===

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAM");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        log.warn("操作失败: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "OPERATION_FAILED");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        log.warn("金额格式错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_AMOUNT_FORMAT");
        error.put("message", "金额格式不正确");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // === 内部方法 ===

    private ReserveConfig buildReserveConfig(ReserveConfigRequest body) {
        ReserveConfig config = new ReserveConfig();
        config.setConfigCode(body.getConfigCode());
        if (body.getMinReserveAmount() != null) {
            config.setMinReserveAmount(new BigDecimal(body.getMinReserveAmount()));
        }
        if (body.getMaxReserveAmount() != null) {
            config.setMaxReserveAmount(new BigDecimal(body.getMaxReserveAmount()));
        }
        if (body.getAutoReplenishEnabled() != null) {
            config.setAutoReplenishEnabled(body.getAutoReplenishEnabled());
        }
        if (body.getReplenishThreshold() != null) {
            config.setReplenishThreshold(new BigDecimal(body.getReplenishThreshold()));
        }
        if (body.getReplenishAmount() != null) {
            config.setReplenishAmount(new BigDecimal(body.getReplenishAmount()));
        }
        if (body.getReplenishSourceAccountType() != null) {
            config.setReplenishSourceAccountType(AccountType.valueOf(body.getReplenishSourceAccountType()));
        }
        if (body.getAlertThreshold() != null) {
            config.setAlertThreshold(new BigDecimal(body.getAlertThreshold()));
        }
        if (body.getMonitoringEnabled() != null) {
            config.setMonitoringEnabled(body.getMonitoringEnabled());
        }
        config.setDescription(body.getDescription());
        return config;
    }

    private Map<String, Object> toConfigMap(ReserveConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", config.getId());
        map.put("merchantId", config.getMerchantId());
        map.put("configCode", config.getConfigCode());
        map.put("minReserveAmount", config.getMinReserveAmount());
        map.put("maxReserveAmount", config.getMaxReserveAmount());
        map.put("autoReplenishEnabled", config.getAutoReplenishEnabled());
        map.put("replenishThreshold", config.getReplenishThreshold());
        map.put("replenishAmount", config.getReplenishAmount());
        map.put("replenishSourceAccountType", config.getReplenishSourceAccountType());
        map.put("alertThreshold", config.getAlertThreshold());
        map.put("alertFlag", config.getAlertFlag());
        map.put("monitoringEnabled", config.getMonitoringEnabled());
        map.put("description", config.getDescription());
        return map;
    }

    // === DTO ===

    public static class ReserveConfigRequest {
        private String configCode;
        private String minReserveAmount;
        private String maxReserveAmount;
        private Boolean autoReplenishEnabled;
        private String replenishThreshold;
        private String replenishAmount;
        private String replenishSourceAccountType;
        private String alertThreshold;
        private Boolean monitoringEnabled;
        private String description;

        public String getConfigCode() { return configCode; }
        public void setConfigCode(String configCode) { this.configCode = configCode; }

        public String getMinReserveAmount() { return minReserveAmount; }
        public void setMinReserveAmount(String minReserveAmount) { this.minReserveAmount = minReserveAmount; }

        public String getMaxReserveAmount() { return maxReserveAmount; }
        public void setMaxReserveAmount(String maxReserveAmount) { this.maxReserveAmount = maxReserveAmount; }

        public Boolean getAutoReplenishEnabled() { return autoReplenishEnabled; }
        public void setAutoReplenishEnabled(Boolean autoReplenishEnabled) { this.autoReplenishEnabled = autoReplenishEnabled; }

        public String getReplenishThreshold() { return replenishThreshold; }
        public void setReplenishThreshold(String replenishThreshold) { this.replenishThreshold = replenishThreshold; }

        public String getReplenishAmount() { return replenishAmount; }
        public void setReplenishAmount(String replenishAmount) { this.replenishAmount = replenishAmount; }

        public String getReplenishSourceAccountType() { return replenishSourceAccountType; }
        public void setReplenishSourceAccountType(String replenishSourceAccountType) { this.replenishSourceAccountType = replenishSourceAccountType; }

        public String getAlertThreshold() { return alertThreshold; }
        public void setAlertThreshold(String alertThreshold) { this.alertThreshold = alertThreshold; }

        public Boolean getMonitoringEnabled() { return monitoringEnabled; }
        public void setMonitoringEnabled(Boolean monitoringEnabled) { this.monitoringEnabled = monitoringEnabled; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class ReplenishRequest {
        private String amount;
        private String fromAccountType;

        public String getAmount() { return amount; }
        public void setAmount(String amount) { this.amount = amount; }

        public String getFromAccountType() { return fromAccountType; }
        public void setFromAccountType(String fromAccountType) { this.fromAccountType = fromAccountType; }
    }
}