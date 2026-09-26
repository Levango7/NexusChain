package org.nexus.gateway.controller.dto;

/**
 * 密钥轮换进度查询响应 DTO。
 *
 * <p>对应 GET /api/v1/security/encryption-configs/{id}/rotation-status 响应体。
 * 来源：设计文档 §4.1.3。</p>
 */
public class RotationStatusResponse {

    private int oldKekVersion;
    private int newKekVersion;
    private String migrationStatus;
    private long totalRecords;
    private long migratedRecords;
    private long remainingRecords;

    public RotationStatusResponse() {}

    public RotationStatusResponse(int oldKekVersion, int newKekVersion,
                                    String migrationStatus, long totalRecords,
                                    long migratedRecords, long remainingRecords) {
        this.oldKekVersion = oldKekVersion;
        this.newKekVersion = newKekVersion;
        this.migrationStatus = migrationStatus;
        this.totalRecords = totalRecords;
        this.migratedRecords = migratedRecords;
        this.remainingRecords = remainingRecords;
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

    public long getRemainingRecords() { return remainingRecords; }
    public void setRemainingRecords(long remainingRecords) { this.remainingRecords = remainingRecords; }
}