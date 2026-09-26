package org.nexus.gateway.controller.dto;

/**
 * 密钥轮换响应 DTO。
 *
 * <p>对应 POST /api/v1/security/encryption-configs/{id}/rotate-key 响应体。
 * 来源：设计文档 §4.1.2。</p>
 */
public class KeyRotationResponse {

    private int oldKekVersion;
    private int newKekVersion;
    private String migrationStatus;
    private long totalRecords;
    private long migratedRecords;

    public KeyRotationResponse() {}

    public KeyRotationResponse(int oldKekVersion, int newKekVersion,
                                 String migrationStatus, long totalRecords, long migratedRecords) {
        this.oldKekVersion = oldKekVersion;
        this.newKekVersion = newKekVersion;
        this.migrationStatus = migrationStatus;
        this.totalRecords = totalRecords;
        this.migratedRecords = migratedRecords;
    }

    public int getOldKekVersion() { return oldKekVersion; }
    public void setOldKekVersion(int oldKekVersion) { this.oldKekVersion = oldKekVersion; }

    public int getNewKekVersion() { return newKekVersion; }
    public void setNewKekVersion(int newKekVersion) { this.newKekVersion = newKekVersion; }

    public String getMigrationStatus() { return migrationStatus; }
    public void setMigrationStatus(String migrationStatus) { this.migrationStatus = migrationStatus; }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public long getMigratedRecords() { return migratedRecords; }
    public void setMigratedRecords(long migratedRecords) { this.migratedRecords = migratedRecords; }
}