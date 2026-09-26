package org.nexus.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.nexus.gateway.security.password.FactorType;
import org.nexus.gateway.security.password.PasswordSecurityConfig;
import org.nexus.gateway.security.password.PasswordSecurityConfigService;
import org.nexus.gateway.security.password.PaymentPasswordService;
import org.nexus.gateway.security.password.SecondFactorRecord;
import org.nexus.gateway.security.password.SecondFactorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * 支付密码验证 REST API。
 *
 * <p>提供支付密码的设置、验证、更换、解锁以及二次验证（OTP）功能。</p>
 *
 * <p>端点鉴权模型：
 * <ul>
 *   <li>密码设置/验证/更换：商户 JWT 鉴权（从请求上下文获取 merchantId）</li>
 *   <li>密码解锁：管理员操作 {@code @PreAuthorize("hasRole('ADMIN')")}</li>
 *   <li>密码策略配置：管理员操作 {@code @PreAuthorize("hasRole('ADMIN')")}</li>
 * </ul></p>
 *
 * <p>来源：设计文档 §4.4 支付密码验证 API。经验指导：
 * 同类 Controller 需类级 {@code @PreAuthorize("isAuthenticated()")} 作为双重防御
 * （来源：2026-09-17-class-level-preauthorize-plus-require-tenant-double-defense）。</p>
 */
@RestController
@RequestMapping("/api/v1/security/payment-password")
@PreAuthorize("isAuthenticated()")
@Tag(name = "Payment Password", description = "支付密码设置、验证、更换、解锁及二次验证")
public class PaymentPasswordController {

    private static final Logger log = LoggerFactory.getLogger(PaymentPasswordController.class);

    private final PaymentPasswordService paymentPasswordService;
    private final PasswordSecurityConfigService configService;
    private final SecondFactorService secondFactorService;
    private final MerchantOwnershipGuard ownershipGuard;

    public PaymentPasswordController(PaymentPasswordService paymentPasswordService,
                                     PasswordSecurityConfigService configService,
                                     SecondFactorService secondFactorService,
                                     MerchantOwnershipGuard ownershipGuard) {
        this.paymentPasswordService = paymentPasswordService;
        this.configService = configService;
        this.secondFactorService = secondFactorService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 设置支付密码。
     *
     * @param request    HTTP 请求（用于获取商户上下文）
     * @param body       请求体，包含 password 字段
     * @return 设置结果
     */
    @Operation(summary = "设置支付密码")
    @PostMapping
    public ResponseEntity<Map<String, Object>> setPassword(HttpServletRequest request,
                                                           @RequestBody Map<String, String> body) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        String password = body.get("password");
        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "密码不能为空"));
        }

        paymentPasswordService.setPassword(merchantId, password, null);

        PasswordSecurityConfig config = configService.getConfig(null);
        Instant expiresAt = Instant.now().plus(config.getPasswordExpiryDays(), ChronoUnit.DAYS);
        return ResponseEntity.ok(Map.of(
                "status", "SET",
                "expiresAt", expiresAt.toString()));
    }

    /**
     * 更改支付密码。
     *
     * @param request    HTTP 请求
     * @param body       请求体，包含 oldPassword 和 newPassword
     * @return 更改结果
     */
    @Operation(summary = "更改支付密码")
    @PutMapping
    public ResponseEntity<Map<String, Object>> changePassword(HttpServletRequest request,
                                                              @RequestBody Map<String, String> body) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        String oldPassword = body.get("oldPassword");
        String newPassword = body.get("newPassword");

        if (oldPassword == null || oldPassword.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "旧密码不能为空"));
        }
        if (newPassword == null || newPassword.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "新密码不能为空"));
        }

        paymentPasswordService.changePassword(merchantId, oldPassword, newPassword, null);

        PasswordSecurityConfig config = configService.getConfig(null);
        Instant expiresAt = Instant.now().plus(config.getPasswordExpiryDays(), ChronoUnit.DAYS);
        return ResponseEntity.ok(Map.of(
                "status", "CHANGED",
                "expiresAt", expiresAt.toString()));
    }

    /**
     * 验证支付密码。
     *
     * @param request    HTTP 请求
     * @param body       请求体，包含 password 字段
     * @return 验证结果
     */
    @Operation(summary = "验证支付密码")
    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyPassword(HttpServletRequest request,
                                                              @RequestBody Map<String, String> body) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        String password = body.get("password");
        if (password == null || password.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "密码不能为空"));
        }

        PaymentPasswordService.PasswordVerifyResult result =
                paymentPasswordService.verifyPassword(merchantId, password, null);

        return switch (result.getStatus()) {
            case SUCCESS -> ResponseEntity.ok(Map.of("status", "VERIFIED"));
            case SKIPPED -> ResponseEntity.ok(Map.of("status", "SKIPPED", "message", "支付密码尚未设置"));
            case INVALID -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", 40302, "status", "INVALID",
                    "remainingAttempts", result.getRemainingAttempts()));
            case LOCKED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", 40302, "status", "LOCKED",
                    "lockedUntil", result.getLockedUntil() != null ? result.getLockedUntil().toString() : ""));
            case EXPIRED -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", 40302, "status", "EXPIRED", "message", "密码过期，需更换"));
        };
    }

    /**
     * 解锁支付密码（管理员操作）。
     *
     * @param merchantId 商户 ID
     * @return 解锁结果
     */
    @Operation(summary = "解锁支付密码（管理员操作）")
    @PostMapping("/unlock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> unlockPassword(@RequestParam Long merchantId) {
        paymentPasswordService.unlockPassword(merchantId);
        return ResponseEntity.ok(Map.of(
                "status", "UNLOCKED",
                "unlockedAt", Instant.now().toString(),
                "requirePasswordReset", false));
    }

    /**
     * 发起二次验证。
     *
     * @param request    HTTP 请求
     * @param body       请求体，包含 factorType 字段（OTP/TOTP/EMAIL）
     * @return 二次验证记录
     */
    @Operation(summary = "发起二次验证")
    @PostMapping("/second-factor")
    public ResponseEntity<Map<String, Object>> generateSecondFactor(HttpServletRequest request,
                                                                    @RequestBody Map<String, String> body) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        String factorTypeStr = body.getOrDefault("factorType", "OTP");
        FactorType factorType;
        try {
            factorType = FactorType.valueOf(factorTypeStr);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "不支持的验证类型: " + factorTypeStr));
        }

        SecondFactorRecord record = secondFactorService.generateSecondFactor(merchantId, factorType);
        return ResponseEntity.ok(Map.of(
                "status", "SENT",
                "factorType", factorType.name(),
                "expiresAt", record.getExpiresAt().toString()));
    }

    /**
     * 验证二次验证码。
     *
     * @param request    HTTP 请求
     * @param body       请求体，包含 code 字段
     * @return 验证结果
     */
    @Operation(summary = "验证二次验证码")
    @PostMapping("/second-factor/verify")
    public ResponseEntity<Map<String, Object>> verifySecondFactor(HttpServletRequest request,
                                                                  @RequestBody Map<String, String> body) {
        Long merchantId = ownershipGuard.requireMerchantId(request);
        String code = body.get("code");
        if (code == null || code.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", 40000, "message", "验证码不能为空"));
        }

        boolean verified = secondFactorService.verifySecondFactor(merchantId, code);
        if (verified) {
            return ResponseEntity.ok(Map.of("status", "VERIFIED"));
        } else {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", 40302, "status", "INVALID", "message", "验证码错误或已过期"));
        }
    }
}