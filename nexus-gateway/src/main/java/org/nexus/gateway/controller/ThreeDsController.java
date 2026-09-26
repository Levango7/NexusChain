package org.nexus.gateway.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.nexus.gateway.security.threeds.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 3D Secure 2.0 REST API 端点。
 *
 * <p>提供 3DS 认证发起、Challenge 完成、认证状态查询、以及 3DS 配置管理接口。
 * 配置管理端点需 ADMIN 角色（{@code @PreAuthorize("hasRole('ADMIN')")}）。</p>
 *
 * <p>端点路径：
 * <ul>
 *   <li>POST /api/v1/security/3ds/initiate — 发起 3DS 认证</li>
 *   <li>POST /api/v1/security/3ds/{authId}/complete — 完成 Challenge 认证</li>
 *   <li>GET  /api/v1/security/3ds/status/{paymentOrderId} — 查询认证状态</li>
 *   <li>GET  /api/v1/security/3ds/configs — 查询 3DS 配置</li>
 *   <li>POST /api/v1/security/3ds/configs — 更新 3DS 配置（ADMIN）</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/security/3ds")
@Tag(name = "3D Secure", description = "3DS 2.0 authentication and configuration")
public class ThreeDsController {

    private final ThreeDsService threeDsService;
    private final ThreeDsConfigService configService;

    public ThreeDsController(ThreeDsService threeDsService, ThreeDsConfigService configService) {
        this.threeDsService = threeDsService;
        this.configService = configService;
    }

    /**
     * 发起 3DS 认证。
     *
     * @param request 包含 paymentOrderId 和 deviceInfo
     * @return 3DS 认证结果
     */
    @Operation(summary = "Initiate 3DS authentication")
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, Object>> initiateAuth(@RequestBody InitiateAuthRequest request) {
        RiskAssessor.DeviceInfo deviceInfo = new RiskAssessor.DeviceInfo(
                request.getDeviceInfo().getIp(),
                request.getDeviceInfo().getUserAgent(),
                request.getDeviceInfo().getAcceptLanguage(),
                request.getDeviceInfo().getDeviceFingerprint(),
                request.getDeviceInfo().isDeviceFingerprintKnown()
        );

        ThreeDsServer.ThreeDsAuthResult result =
                threeDsService.initiateAuth(request.getPaymentOrderId(), deviceInfo);

        return ResponseEntity.ok(buildInitiateResponse(result));
    }

    /**
     * 完成 Challenge 认证。
     *
     * @param authId  认证记录 ID
     * @param request 包含挑战结果
     * @return 3DS 认证结果
     */
    @Operation(summary = "Complete 3DS challenge authentication")
    @PostMapping("/{authId}/complete")
    public ResponseEntity<Map<String, Object>> completeAuth(@PathVariable Long authId,
                                                            @RequestBody CompleteAuthRequest request) {
        ThreeDsServer.ThreeDsAuthResult result =
                threeDsService.completeAuth(authId, request.getChallengeResult());

        if (result.getAuthStatus() == AuthStatus.FAILED) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(buildCompleteResponse(result));
        }

        return ResponseEntity.ok(buildCompleteResponse(result));
    }

    /**
     * 查询认证状态。
     *
     * @param paymentOrderId 支付订单 ID
     * @return 认证状态信息
     */
    @Operation(summary = "Query 3DS authentication status")
    @GetMapping("/status/{paymentOrderId}")
    public ResponseEntity<Map<String, Object>> getAuthStatus(@PathVariable Long paymentOrderId) {
        return threeDsService.getAuthStatus(paymentOrderId)
                .map(record -> ResponseEntity.ok(buildStatusResponse(record)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 查询 3DS 配置。
     *
     * @param tenantId   租户 ID
     * @param merchantId 商户 ID（可选）
     * @return 3DS 配置
     */
    @Operation(summary = "Query 3DS configuration")
    @GetMapping("/configs")
    public ResponseEntity<Map<String, Object>> getConfigs(
            @RequestParam String tenantId,
            @RequestParam(required = false) Long merchantId) {
        ThreeDsConfig config = configService.getConfig(tenantId, merchantId);
        return ResponseEntity.ok(buildConfigResponse(config));
    }

    /**
     * 更新 3DS 配置（需 ADMIN 角色）。
     *
     * @param request 配置更新请求
     * @return 更新后的配置
     */
    @Operation(summary = "Update 3DS configuration")
    @PostMapping("/configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> updateConfigs(@RequestBody UpdateConfigRequest request) {
        ThreeDsConfig config = configService.updateConfig(
                request.getTenantId(),
                request.getMerchantId(),
                request.isEnabled(),
                request.getFrictionlessThresholdScore(),
                request.getChallengeTimeoutSeconds(),
                request.getAcsUrl()
        );
        return ResponseEntity.ok(buildConfigResponse(config));
    }

    // --- 响应构建 ---

    private Map<String, Object> buildInitiateResponse(ThreeDsServer.ThreeDsAuthResult result) {
        return Map.of(
                "transStatus", result.getTransStatus().name(),
                "authStatus", result.getAuthStatus().name(),
                "riskScore", result.getRiskScore() != null ? result.getRiskScore() : 0,
                "frictionlessFlow", result.isFrictionlessFlow(),
                "challengeFlow", result.isChallengeFlow(),
                "acsChallengeUrl", result.getAcsChallengeUrl() != null ? result.getAcsChallengeUrl() : "",
                "eci", result.getEci() != null ? result.getEci() : "",
                "authenticationValue", result.getAuthenticationValue() != null ? result.getAuthenticationValue() : ""
        );
    }

    private Map<String, Object> buildCompleteResponse(ThreeDsServer.ThreeDsAuthResult result) {
        return Map.of(
                "transStatus", result.getTransStatus().name(),
                "authStatus", result.getAuthStatus().name(),
                "eci", result.getEci() != null ? result.getEci() : "",
                "authenticationValue", result.getAuthenticationValue() != null ? result.getAuthenticationValue() : "",
                "errorCode", result.getErrorCode() != null ? result.getErrorCode() : "",
                "errorDetail", result.getErrorDetail() != null ? result.getErrorDetail() : ""
        );
    }

    private Map<String, Object> buildStatusResponse(ThreeDsAuthRecord record) {
        return Map.of(
                "authRecordId", record.getId(),
                "authStatus", record.getAuthStatus().name(),
                "transStatus", record.getTransStatus().name(),
                "riskScore", record.getRiskScore() != null ? record.getRiskScore() : 0,
                "frictionlessFlow", record.isFrictionlessFlow(),
                "challengeFlow", record.isChallengeFlow(),
                "initiatedAt", record.getInitiatedAt().toString(),
                "completedAt", record.getCompletedAt() != null ? record.getCompletedAt().toString() : "",
                "errorCode", record.getErrorCode() != null ? record.getErrorCode() : "",
                "errorDetail", record.getErrorDetail() != null ? record.getErrorDetail() : ""
        );
    }

    private Map<String, Object> buildConfigResponse(ThreeDsConfig config) {
        return Map.of(
                "id", config.getId() != null ? config.getId() : 0,
                "tenantId", config.getTenantId(),
                "merchantId", config.getMerchantId() != null ? config.getMerchantId() : 0,
                "enabled", config.isEnabled(),
                "frictionlessThresholdScore", config.getFrictionlessThresholdScore(),
                "challengeTimeoutSeconds", config.getChallengeTimeoutSeconds(),
                "acsUrl", config.getAcsUrl() != null ? config.getAcsUrl() : ""
        );
    }

    // --- 请求 DTO ---

    public static class InitiateAuthRequest {
        private Long paymentOrderId;
        private DeviceInfoDto deviceInfo;

        public Long getPaymentOrderId() { return paymentOrderId; }
        public void setPaymentOrderId(Long paymentOrderId) { this.paymentOrderId = paymentOrderId; }

        public DeviceInfoDto getDeviceInfo() { return deviceInfo; }
        public void setDeviceInfo(DeviceInfoDto deviceInfo) { this.deviceInfo = deviceInfo; }
    }

    public static class DeviceInfoDto {
        private String ip;
        private String userAgent;
        private String acceptLanguage;
        private String deviceFingerprint;
        private boolean deviceFingerprintKnown;

        public String getIp() { return ip; }
        public void setIp(String ip) { this.ip = ip; }

        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String acceptLanguage) { this.acceptLanguage = acceptLanguage; }

        public String getDeviceFingerprint() { return deviceFingerprint; }
        public void setDeviceFingerprint(String deviceFingerprint) {
            this.deviceFingerprint = deviceFingerprint;
        }

        public boolean isDeviceFingerprintKnown() { return deviceFingerprintKnown; }
        public void setDeviceFingerprintKnown(boolean deviceFingerprintKnown) {
            this.deviceFingerprintKnown = deviceFingerprintKnown;
        }
    }

    public static class CompleteAuthRequest {
        private String challengeResult;

        public String getChallengeResult() { return challengeResult; }
        public void setChallengeResult(String challengeResult) { this.challengeResult = challengeResult; }
    }

    public static class UpdateConfigRequest {
        private String tenantId;
        private Long merchantId;
        private boolean enabled;
        private int frictionlessThresholdScore;
        private int challengeTimeoutSeconds;
        private String acsUrl;

        public String getTenantId() { return tenantId; }
        public void setTenantId(String tenantId) { this.tenantId = tenantId; }

        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getFrictionlessThresholdScore() { return frictionlessThresholdScore; }
        public void setFrictionlessThresholdScore(int frictionlessThresholdScore) {
            this.frictionlessThresholdScore = frictionlessThresholdScore;
        }

        public int getChallengeTimeoutSeconds() { return challengeTimeoutSeconds; }
        public void setChallengeTimeoutSeconds(int challengeTimeoutSeconds) {
            this.challengeTimeoutSeconds = challengeTimeoutSeconds;
        }

        public String getAcsUrl() { return acsUrl; }
        public void setAcsUrl(String acsUrl) { this.acsUrl = acsUrl; }
    }
}