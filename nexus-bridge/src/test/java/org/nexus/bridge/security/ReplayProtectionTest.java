package org.nexus.bridge.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ReplayProtection} 单元测试：覆盖时间戳校验、nonce 防重放与过期清理。
 */
class ReplayProtectionTest {

    private ReplayProtection protection;

    @BeforeEach
    void setUp() {
        protection = new ReplayProtection();
    }

    @Test
    @DisplayName("validateTimestamp: 当前时间戳应通过")
    void validateTimestamp_currentTimePasses() {
        long now = System.currentTimeMillis() / 1000;
        assertDoesNotThrow(() -> protection.validateTimestamp(now));
    }

    @Test
    @DisplayName("validateTimestamp: 5 分钟内偏移应通过")
    void validateTimestamp_withinDriftPasses() {
        long now = System.currentTimeMillis() / 1000;
        assertDoesNotThrow(() -> protection.validateTimestamp(now - 60));
        assertDoesNotThrow(() -> protection.validateTimestamp(now + 60));
        assertDoesNotThrow(() -> protection.validateTimestamp(now - 299));
        assertDoesNotThrow(() -> protection.validateTimestamp(now + 299));
    }

    @Test
    @DisplayName("validateTimestamp: 超过 5 分钟偏移应抛 SecurityException")
    void validateTimestamp_exceedsDriftThrows() {
        long now = System.currentTimeMillis() / 1000;
        assertThrows(SecurityException.class, () -> protection.validateTimestamp(now - 600));
        assertThrows(SecurityException.class, () -> protection.validateTimestamp(now + 600));
    }

    @Test
    @DisplayName("checkAndRecordNonce: 首次记录不抛异常")
    void checkAndRecordNonce_firstTimeNoException() {
        assertDoesNotThrow(() -> protection.checkAndRecordNonce("nonce-000000000001"));
        assertEquals(1, protection.size());
    }

    @Test
    @DisplayName("checkAndRecordNonce: 重复 nonce 应抛 SecurityException")
    void checkAndRecordNonce_duplicateThrows() {
        protection.checkAndRecordNonce("nonce-000000000001");
        assertThrows(SecurityException.class, () -> protection.checkAndRecordNonce("nonce-000000000001"));
        assertEquals(1, protection.size());
    }

    @Test
    @DisplayName("checkAndRecordNonce: 不同 nonce 应全部记录")
    void checkAndRecordNonce_differentNoncesAllRecorded() {
        protection.checkAndRecordNonce("nonce-000000000001");
        protection.checkAndRecordNonce("nonce-000000000002");
        protection.checkAndRecordNonce("nonce-000000000003");
        assertEquals(3, protection.size());
    }

    @Test
    @DisplayName("evictExpired: 应清理过期 nonce")
    void evictExpired_removesOldNonces() {
        protection.checkAndRecordNonce("nonce-000000000001");
        protection.checkAndRecordNonce("nonce-000000000002");
        assertEquals(2, protection.size());

        // evictExpired 清理超过 10 分钟（2 * 5min）的 nonce
        protection.evictExpired();
        // 刚加入的 nonce 不应被清理
        assertEquals(2, protection.size());
    }

    @Test
    @DisplayName("size: 初始为 0")
    void size_initialZero() {
        assertEquals(0, protection.size());
    }
}