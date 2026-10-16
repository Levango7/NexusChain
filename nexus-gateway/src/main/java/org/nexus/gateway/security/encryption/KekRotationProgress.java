package org.nexus.gateway.security.encryption;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * KEK 轮换迁移进度（跨副本共享断点）。
 *
 * <p>{@link KeyRotationScheduler} 的迁移进度从"每-JVM 内存 {@code ConcurrentHashMap}"
 * 升级为本表持久化（#102 PLAN §4.2 / #31-Step3）。读以本表为权威、内存 Map 退化为写穿缓存；
 * {@code lastProcessedId} 成为跨副本共享断点，任一副本续迁都从同一游标继续。</p>
 *
 * <p>{@code old_version} 唯一：同一旧 KEK 版本只对应一个迁移任务。</p>
 */
@Entity
@Table(name = "kek_rotation_progress")
public class KekRotationProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "old_version", nullable = false, unique = true)
    private int oldVersion;

    @Column(name = "new_version", nullable = false)
    private int newVersion;

    @Column(name = "total_records", nullable = false)
    private long totalRecords;

    @Column(name = "migrated_records", nullable = false)
    private long migratedRecords;

    @Column(name = "failed_records", nullable = false)
    private long failedRecords;

    @Column(name = "last_processed_id", nullable = false)
    private long lastProcessedId;

    @Column(name = "completed", nullable = false)
    private boolean completed;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public int getOldVersion() { return oldVersion; }
    public void setOldVersion(int oldVersion) { this.oldVersion = oldVersion; }

    public int getNewVersion() { return newVersion; }
    public void setNewVersion(int newVersion) { this.newVersion = newVersion; }

    public long getTotalRecords() { return totalRecords; }
    public void setTotalRecords(long totalRecords) { this.totalRecords = totalRecords; }

    public long getMigratedRecords() { return migratedRecords; }
    public void setMigratedRecords(long migratedRecords) { this.migratedRecords = migratedRecords; }

    public long getFailedRecords() { return failedRecords; }
    public void setFailedRecords(long failedRecords) { this.failedRecords = failedRecords; }

    public long getLastProcessedId() { return lastProcessedId; }
    public void setLastProcessedId(long lastProcessedId) { this.lastProcessedId = lastProcessedId; }

    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
