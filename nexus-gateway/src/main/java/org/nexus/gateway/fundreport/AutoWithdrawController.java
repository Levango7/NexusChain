package org.nexus.gateway.fundreport;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
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

/**
 * 自动提现规则 REST API — 商户自动提现配置与管理。
 *
 * <p>提供自动提现规则配置、查询、禁用等功能。
 * 路径前缀：{@code /api/v1/auto-withdraw}</p>
 *
 * <h3>鉴权（2026-10-25 修复跨商户越权）</h3>
 *
 * <p><b>修复前</b>：{@code configure} 与查询端点只要求
 * {@code hasRole('MERCHANT') or hasRole('ADMIN')}，路径 {@code merchantId}
 * 直接交给 Service —— <b>没有归属校验</b>。自动提现规则决定"什么时候、往哪、提多少"，
 * 能替他人配置等于能替他人设定自动出款，风险高于只读。</p>
 *
 * <p><b>修复方式</b>：对齐 {@code PaymentController} 范式，用
 * {@link MerchantOwnershipGuard} 比对 {@code nexus.merchantId} 与路径参数。</p>
 *
 * <p>{@code disable} 是管理动作（{@code hasRole('ADMIN')}），本次未改动。</p>
 */
@RestController
@RequestMapping("/api/v1/auto-withdraw")
@Tag(name = "AutoWithdraw", description = "自动提现管理：规则配置/查询/禁用")
public class AutoWithdrawController {

    private static final Logger log = LoggerFactory.getLogger(AutoWithdrawController.class);

    private final AutoWithdrawService autoWithdrawService;
    private final MerchantOwnershipGuard ownershipGuard;

    public AutoWithdrawController(AutoWithdrawService autoWithdrawService,
                                  MerchantOwnershipGuard ownershipGuard) {
        this.autoWithdrawService = autoWithdrawService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 归属校验：路径 {@code merchantId} 必须等于调用方商户（fail-closed）。
     */
    private void requireOwnMerchant(HttpServletRequest request, Long merchantId) {
        Long callerMerchantId = ownershipGuard.requireMerchantId(request);
        ownershipGuard.requireOwned(callerMerchantId, merchantId, "auto-withdraw", merchantId);
    }

    /**
     * 配置自动提现规则。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param body 请求体（frequency + threshold + targetAmount + minRetain）
     * @param request HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 200 + 配置后的规则信息
     */
    @Operation(summary = "配置自动提现规则")
    @PostMapping("/{merchantId}/configure")
    public ResponseEntity<Map<String, Object>> configureAutoWithdraw(
            @PathVariable Long merchantId,
            @RequestBody ConfigureRequest body,
            HttpServletRequest request) {

        requireOwnMerchant(request, merchantId);

        // 输入验证：frequency 和 threshold 不能为空
        if (body.getFrequency() == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "INVALID_PARAMETER");
            error.put("message", "提现频率不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }
        if (body.getThreshold() == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", "INVALID_PARAMETER");
            error.put("message", "触发阈值不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        WithdrawFrequency frequency = WithdrawFrequency.valueOf(body.getFrequency());
        BigDecimal threshold = new BigDecimal(body.getThreshold());
        BigDecimal targetAmount = body.getTargetAmount() != null ? new BigDecimal(body.getTargetAmount()) : null;
        BigDecimal minRetain = body.getMinRetain() != null ? new BigDecimal(body.getMinRetain()) : BigDecimal.ZERO;

        AutoWithdrawRule rule = autoWithdrawService.configureAutoWithdraw(
                merchantId, frequency, threshold, targetAmount, minRetain);

        Map<String, Object> result = toRuleMap(rule);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询商户自动提现规则列表。
     *
     * @param merchantId 商户 ID（必须属于调用方）
     * @param request HTTP 请求（承载 ApiKeyInterceptor 注入的商户上下文）
     * @return 200 + 规则列表
     */
    @Operation(summary = "查询商户自动提现规则")
    @GetMapping("/{merchantId}")
    public ResponseEntity<Map<String, Object>> getAutoWithdrawRules(@PathVariable Long merchantId,
                                                                   HttpServletRequest request) {
        requireOwnMerchant(request, merchantId);
        List<AutoWithdrawRule> rules = autoWithdrawService.getAutoWithdrawRule(merchantId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("merchantId", merchantId);
        result.put("rules", rules.stream().map(this::toRuleMap).toList());
        result.put("count", rules.size());

        return ResponseEntity.ok(result);
    }

    /**
     * 禁用自动提现规则。
     *
     * @param ruleId 规则 ID
     * @return 200 + 禁用后的规则信息
     */
    @Operation(summary = "禁用自动提现规则")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{merchantId}/disable/{ruleId}")
    public ResponseEntity<Map<String, Object>> disableAutoWithdraw(
            @PathVariable Long merchantId,
            @PathVariable Long ruleId) {
        AutoWithdrawRule rule = autoWithdrawService.disableAutoWithdraw(ruleId, merchantId);

        Map<String, Object> result = toRuleMap(rule);
        return ResponseEntity.ok(result);
    }

    // === 异常处理 ===

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_PARAMETER");
        error.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(NumberFormatException.class)
    public ResponseEntity<Map<String, Object>> handleNumberFormat(NumberFormatException e) {
        log.warn("金额格式错误: {}", e.getMessage());
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", "INVALID_AMOUNT_FORMAT");
        error.put("message", "金额格式不正确，请输入有效的数字");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    // === 内部方法 ===

    private Map<String, Object> toRuleMap(AutoWithdrawRule rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ruleId", rule.getId());
        map.put("merchantId", rule.getMerchantId());
        map.put("frequency", rule.getFrequency().name());
        map.put("threshold", rule.getThreshold());
        map.put("targetAmount", rule.getTargetAmount());
        map.put("minRetain", rule.getMinRetain());
        map.put("enabled", rule.getEnabled());
        map.put("lastExecutedAt", rule.getLastExecutedAt());
        map.put("createdAt", rule.getCreatedAt());
        map.put("updatedAt", rule.getUpdatedAt());
        return map;
    }

    // === DTO ===

    public static class ConfigureRequest {
        /** 提现频率：DAILY/WEEKLY/MONTHLY */
        private String frequency;
        /** 触发阈值（字符串形式，避免精度丢失） */
        private String threshold;
        /** 目标提现金额（可为空，表示提现全部余额） */
        private String targetAmount;
        /** 最低保留余额（可为空，默认 0） */
        private String minRetain;

        public String getFrequency() { return frequency; }
        public void setFrequency(String frequency) { this.frequency = frequency; }

        public String getThreshold() { return threshold; }
        public void setThreshold(String threshold) { this.threshold = threshold; }

        public String getTargetAmount() { return targetAmount; }
        public void setTargetAmount(String targetAmount) { this.targetAmount = targetAmount; }

        public String getMinRetain() { return minRetain; }
        public void setMinRetain(String minRetain) { this.minRetain = minRetain; }
    }
}