package org.nexus.gateway.security.audit.event;

import java.time.Instant;

/**
 * 密钥轮换完成事件。当 KEK 轮换流程完成时发布，包含旧版本和新版本信息。
 *
 * <p>设计依据：Wave 12 设计文档 §2.2.5、§8.2.2 — SecurityEventPublisher。</p>
 */
public class KeyRotationCompletedEvent {

    private final int oldVersion;
    private final int newVersion;
    private final int migratedDekCount;
    private final Instant completedAt;

    public KeyRotationCompletedEvent(int oldVersion, int newVersion, int migratedDekCount) {
        this.oldVersion = oldVersion;
        this.newVersion = newVersion;
        this.migratedDekCount = migratedDekCount;
        this.completedAt = Instant.now();
    }

    public int getOldVersion() {
        return oldVersion;
    }

    public int getNewVersion() {
        return newVersion;
    }

    public int getMigratedDekCount() {
        return migratedDekCount;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}