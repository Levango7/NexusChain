package org.nexus.gateway.security.encryption;

/**
 * 加密密钥元数据状态枚举。
 *
 * <p>ACTIVE — 当前活跃使用的密钥元数据；ARCHIVED — 已归档（密钥轮换完成后标记）。</p>
 */
public enum KeyMetadataStatus {
    ACTIVE,
    ARCHIVED
}