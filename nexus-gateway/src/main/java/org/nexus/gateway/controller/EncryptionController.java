package org.nexus.gateway.controller;

import org.nexus.gateway.controller.dto.EncryptionConfigRequest;
import org.nexus.gateway.controller.dto.KeyRotationResponse;
import org.nexus.gateway.controller.dto.RotationStatusResponse;
import org.nexus.gateway.security.encryption.EncryptionConfig;
import org.nexus.gateway.security.encryption.EncryptionConfigService;
import org.nexus.gateway.security.encryption.EncryptionException;
import org.nexus.gateway.security.encryption.KeyManagementService;
import org.nexus.gateway.security.encryption.KeyRotationScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * 加密策略配置 / 密钥轮换 REST API。
 *
 * <p>所有端点均需 ADMIN 角色（{@code @PreAuthorize("hasRole('ADMIN')")}），
 * 通过 JWT 方法安全层保护，不修改现有 SecurityConfig 的双层鉴权模型。
 * 来源：经验 2026-09-17-spring-security-dev-prod-dual-mode-no-test-regression —
 * 新增管理端点通过 @PreAuthorize 接入方法安全层。</p>
 *
 * <p>设计文档 §4.1 — 交易加密增强 API。</p>
 */
@RestController
@RequestMapping("/api/v1/security")
@Tag(name = "Security", description = "Encryption configuration and key rotation management")
public class EncryptionController {

    private static final Logger log = LoggerFactory.getLogger(EncryptionController.class);

    private final EncryptionConfigService encryptionConfigService;
    private final KeyManagementService keyManagementService;
    private final KeyRotationScheduler keyRotationScheduler;

    public EncryptionController(EncryptionConfigService encryptionConfigService,
                                  KeyManagementService keyManagementService,
                                  KeyRotationScheduler keyRotationScheduler) {
        this.encryptionConfigService = encryptionConfigService;
        this.keyManagementService = keyManagementService;
        this.keyRotationScheduler = keyRotationScheduler;
    }

    /**
     * 配置加密策略 — 创建租户/商户级加密配置。
     *
     * <p>仅允许 AES-256-GCM 算法，其他算法返回 400 UNSUPPORTED_ENCRYPTION_ALGORITHM。</p>
     */
    @Operation(summary = "Create encryption configuration")
    @PostMapping("/encryption-configs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createEncryptionConfig(@RequestBody EncryptionConfigRequest request) {
        try {
            EncryptionConfig config = encryptionConfigService.createConfig(
                    request.getTenantId(),
                    request.getMerchantId(),
                    request.getEncryptedFields(),
                    request.getEncryptionAlgorithm(),
                    request.getKekRotationPeriodDays(),
                    request.getAppLayerEncryptionEnabled());

            Map<String, Object> response = new HashMap<>();
            response.put("id", config.getId());
            response.put("status", "ACTIVE");
            response.put("createdAt", config.getCreatedAt() != null ?
                    config.getCreatedAt().toString() : Instant.now().toString());

            return ResponseEntity.ok(response);
        } catch (EncryptionException e) {
            if ("UNSUPPORTED_ENCRYPTION_ALGORITHM".equals(e.getErrorCode())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "errorCode", e.getErrorCode(),
                        "message", e.getMessage()));
            }
            log.error("加密配置创建失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "errorCode", e.getErrorCode(),
                    "message", e.getMessage()));
        }
    }

    /**
     * 发起密钥轮换 — 生成新版本 KEK 并启动渐进式 DEK 迁移。
     */
    @Operation(summary = "Initiate key rotation")
    @PostMapping("/encryption-configs/{id}/rotate-key")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> rotateKey(@PathVariable Long id) {
        try {
            EncryptionConfig config = encryptionConfigService.findById(id)
                    .orElseThrow(() -> new EncryptionException("CONFIG_NOT_FOUND",
                            "加密配置不存在: " + id));

            Integer currentVersion = keyManagementService.getCurrentKekVersion();
            if (currentVersion == null) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                        "errorCode", "ENCRYPTION_KEY_UNAVAILABLE",
                        "message", "KEK 不可用"));
            }

            int newVersion = keyRotationScheduler.initiateRotation(currentVersion);
            KeyRotationScheduler.MigrationProgress progress =
                    keyRotationScheduler.getMigrationProgress(currentVersion);

            KeyRotationResponse response = new KeyRotationResponse(
                    currentVersion,
                    newVersion,
                    progress != null ? progress.getMigrationStatus() : "IN_PROGRESS",
                    progress != null ? progress.getTotalRecords() : 0,
                    progress != null ? progress.getMigratedRecords() : 0);

            return ResponseEntity.ok(response);
        } catch (EncryptionException e) {
            if ("KEY_STILL_IN_USE".equals(e.getErrorCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                        "errorCode", e.getErrorCode(),
                        "message", e.getMessage()));
            }
            log.error("密钥轮换失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "errorCode", e.getErrorCode(),
                    "message", e.getMessage()));
        }
    }

    /**
     * 查询密钥轮换进度。
     */
    @Operation(summary = "Query key rotation status")
    @GetMapping("/encryption-configs/{id}/rotation-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getRotationStatus(@PathVariable Long id) {
        EncryptionConfig config = encryptionConfigService.findById(id)
                .orElseThrow(() -> new EncryptionException("CONFIG_NOT_FOUND",
                        "加密配置不存在: " + id));

        Integer currentVersion = keyManagementService.getCurrentKekVersion();
        if (currentVersion == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "errorCode", "ENCRYPTION_KEY_UNAVAILABLE",
                    "message", "KEK 不可用"));
        }

        // 查找正在进行的迁移（oldVersion < currentVersion）
        KeyRotationScheduler.MigrationProgress progress = null;
        for (Map.Entry<Integer, KeyRotationScheduler.MigrationProgress> entry :
                keyRotationScheduler.getAllMigrationProgress().entrySet()) {
            if (!entry.getValue().isCompleted()) {
                progress = entry.getValue();
                break;
            }
        }

        if (progress == null) {
            // 无正在进行的迁移
            RotationStatusResponse response = new RotationStatusResponse(
                    currentVersion, currentVersion, "NO_ROTATION_IN_PROGRESS", 0, 0, 0);
            return ResponseEntity.ok(response);
        }

        RotationStatusResponse response = new RotationStatusResponse(
                progress.getOldVersion(),
                progress.getNewVersion(),
                progress.getMigrationStatus(),
                progress.getTotalRecords(),
                progress.getMigratedRecords(),
                progress.getRemainingRecords());

        return ResponseEntity.ok(response);
    }
}