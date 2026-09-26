package org.nexus.gateway.controller.dto;

import java.util.List;

/**
 * 加密策略配置请求 DTO。
 *
 * <p>对应 POST /api/v1/security/encryption-configs 请求体。
 * 来源：设计文档 §4.1.1。</p>
 */
public class EncryptionConfigRequest {

    private String tenantId;
    private Long merchantId;
    private List<String> encryptedFields;
    private String encryptionAlgorithm = "AES-256-GCM";
    private Integer kekRotationPeriodDays = 90;
    private Boolean appLayerEncryptionEnabled = true;

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public List<String> getEncryptedFields() { return encryptedFields; }
    public void setEncryptedFields(List<String> encryptedFields) { this.encryptedFields = encryptedFields; }

    public String getEncryptionAlgorithm() { return encryptionAlgorithm; }
    public void setEncryptionAlgorithm(String encryptionAlgorithm) { this.encryptionAlgorithm = encryptionAlgorithm; }

    public Integer getKekRotationPeriodDays() { return kekRotationPeriodDays; }
    public void setKekRotationPeriodDays(Integer kekRotationPeriodDays) { this.kekRotationPeriodDays = kekRotationPeriodDays; }

    public Boolean getAppLayerEncryptionEnabled() { return appLayerEncryptionEnabled; }
    public void setAppLayerEncryptionEnabled(Boolean appLayerEncryptionEnabled) { this.appLayerEncryptionEnabled = appLayerEncryptionEnabled; }
}