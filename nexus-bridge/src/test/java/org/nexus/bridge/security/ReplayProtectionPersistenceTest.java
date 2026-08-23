package org.nexus.bridge.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.bridge.entity.NonceRecord;
import org.nexus.bridge.repository.NonceRecordRepository;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReplayProtection} 持久化测试（B-22/B-23 修复专项）。
 *
 * <p>验证 P0 修复：</p>
 * <ul>
 *   <li>B-22：nonce 持久化到 DB（{@link NonceRecordRepository}），节点重启后仍能防重放</li>
 *   <li>B-23：nonce 长度不足 16 字符时拒绝（返回 false / 抛 SecurityException），
 *       不再抛 StringIndexOutOfBoundsException</li>
 * </ul>
 *
 * <p>使用 Mockito Mock {@link NonceRecordRepository} 模拟 DB 行为。</p>
 */
@ExtendWith(MockitoExtension.class)
class ReplayProtectionPersistenceTest {

    @Mock
    private NonceRecordRepository nonceRecordRepository;

    private ReplayProtection protection;

    @BeforeEach
    void setUp() {
        protection = new ReplayProtection(nonceRecordRepository);
    }

    // ==================== B-22: nonce 持久化到 DB ====================

    @Test
    @DisplayName("should_persistNonceToDb_when_checkAndRecordNonceFirstTime")
    void should_persistNonceToDb_when_checkAndRecordNonceFirstTime() {
        String nonce = "nonce-1234567890abcdef"; // 20 字符 >= 16
        when(nonceRecordRepository.existsById(nonce)).thenReturn(false);
        when(nonceRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 首次记录应成功（不抛异常）
        org.assertj.core.api.Assertions.assertThatCode(() -> protection.checkAndRecordNonce(nonce))
                .as("首次记录不应抛异常")
                .doesNotThrowAnyException();

        // 验证 DB existsById 和 save 都被调用
        verify(nonceRecordRepository).existsById(nonce);
        verify(nonceRecordRepository).save(any(NonceRecord.class));
    }

    @Test
    @DisplayName("should_rejectDuplicate_when_nonceAlreadyInDb")
    void should_rejectDuplicate_when_nonceAlreadyInDb() {
        String nonce = "nonce-1234567890abcdef";
        when(nonceRecordRepository.existsById(nonce)).thenReturn(true);

        // 重复 nonce 应抛 SecurityException
        assertThatThrownBy(() -> protection.checkAndRecordNonce(nonce))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Duplicate nonce");

        // 应调用 existsById 但不应调用 save
        verify(nonceRecordRepository).existsById(nonce);
        verify(nonceRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_preventReplayAfterRestart_when_noncePersistedToDb")
    void should_preventReplayAfterRestart_when_noncePersistedToDb() {
        // 模拟首次记录：DB 中不存在
        String nonce = "nonce-restart-test-001";
        when(nonceRecordRepository.existsById(nonce)).thenReturn(false);
        when(nonceRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 首次记录成功
        protection.checkAndRecordNonce(nonce);

        // 捕获写入 DB 的 NonceRecord
        org.mockito.ArgumentCaptor<NonceRecord> captor =
                org.mockito.ArgumentCaptor.forClass(NonceRecord.class);
        verify(nonceRecordRepository).save(captor.capture());
        NonceRecord savedRecord = captor.getValue();
        assertThat(savedRecord.getNonce()).isEqualTo(nonce);

        // 模拟 restart：新的 ReplayProtection 实例，DB 中已有该 nonce
        org.mockito.Mockito.reset(nonceRecordRepository);
        when(nonceRecordRepository.existsById(nonce)).thenReturn(true);

        ReplayProtection protectionAfterRestart = new ReplayProtection(nonceRecordRepository);

        // 关键断言：restart 后同一 nonce 应被拒绝（防重放）
        assertThatThrownBy(() -> protectionAfterRestart.checkAndRecordNonce(nonce))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Duplicate nonce");
    }

    @Test
    @DisplayName("should_failClosed_when_dbOperationFails")
    void should_failClosed_when_dbOperationFails() {
        String nonce = "nonce-dbfail-test-001";
        when(nonceRecordRepository.existsById(nonce))
                .thenThrow(new RuntimeException("DB connection lost"));

        // DB 操作失败时应 fail-closed（拒绝该 nonce）
        assertThatThrownBy(() -> protection.checkAndRecordNonce(nonce))
                .isInstanceOf(SecurityException.class);

        // 不应调用 save（DB 不可用时不应绕过重放保护）
        verify(nonceRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_returnFalse_when_checkAndRecordNonceBooleanWithDbFail")
    void should_returnFalse_when_checkAndRecordNonceBooleanWithDbFail() {
        String nonce = "nonce-boolfail-test-01";
        when(nonceRecordRepository.existsById(nonce))
                .thenThrow(new RuntimeException("DB fail"));

        // boolean 版本应返回 false
        boolean result = protection.checkAndRecordNonceBoolean(nonce);
        assertThat(result).isFalse();
    }

    // ==================== B-23: 短 nonce 被拒绝 ====================

    @Test
    @DisplayName("should_throwSecurityException_when_nonceTooShort")
    void should_throwSecurityException_when_nonceTooShort() {
        // 15 字符 < 16
        String shortNonce = "123456789012345"; // 15 字符

        assertThatThrownBy(() -> protection.checkAndRecordNonce(shortNonce))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Nonce too short");

        // 不应访问 DB
        verify(nonceRecordRepository, never()).existsById(anyString());
        verify(nonceRecordRepository, never()).save(any());
    }

    @Test
    @DisplayName("should_returnFalse_when_checkAndRecordNonceBooleanWithShortNonce")
    void should_returnFalse_when_checkAndRecordNonceBooleanWithShortNonce() {
        String shortNonce = "short"; // 5 字符

        boolean result = protection.checkAndRecordNonceBoolean(shortNonce);
        assertThat(result).isFalse();

        // 不应访问 DB
        verify(nonceRecordRepository, never()).existsById(anyString());
    }

    @Test
    @DisplayName("should_throwSecurityException_when_nonceNull")
    void should_throwSecurityException_when_nonceNull() {
        assertThatThrownBy(() -> protection.checkAndRecordNonce(null))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("null");

        verify(nonceRecordRepository, never()).existsById(anyString());
    }

    @Test
    @DisplayName("should_returnFalse_when_checkAndRecordNonceBooleanWithNull")
    void should_returnFalse_when_checkAndRecordNonceBooleanWithNull() {
        boolean result = protection.checkAndRecordNonceBoolean(null);
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("should_acceptNonce_when_lengthExactly16")
    void should_acceptNonce_when_lengthExactly16() {
        // 恰好 16 字符应被接受
        String nonce = "1234567890123456"; // 16 字符
        when(nonceRecordRepository.existsById(nonce)).thenReturn(false);
        when(nonceRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 不应抛异常
        protection.checkAndRecordNonce(nonce);

        verify(nonceRecordRepository).existsById(nonce);
        verify(nonceRecordRepository).save(any());
    }

    @Test
    @DisplayName("should_acceptNonce_when_lengthGreaterThan16")
    void should_acceptNonce_when_lengthGreaterThan16() {
        String nonce = "12345678901234567890"; // 20 字符
        when(nonceRecordRepository.existsById(nonce)).thenReturn(false);
        when(nonceRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        protection.checkAndRecordNonce(nonce);

        verify(nonceRecordRepository).existsById(nonce);
        verify(nonceRecordRepository).save(any());
    }

    // ==================== B-22: evictExpired 清理 DB ====================

    @Test
    @DisplayName("should_evictExpiredNoncesFromDb_when_evictExpired")
    void should_evictExpiredNoncesFromDb_when_evictExpired() {
        // 模拟 DB 中有过期 nonce
        NonceRecord expired1 = new NonceRecord("nonce-expired-001", Instant.now().minusSeconds(3600));
        NonceRecord expired2 = new NonceRecord("nonce-expired-002", Instant.now().minusSeconds(3700));
        when(nonceRecordRepository.findByCreatedAtBefore(any()))
                .thenReturn(Arrays.asList(expired1, expired2));

        // 调用 evictExpired 应清理过期 nonce
        protection.evictExpired();

        // 验证调用了 findByCreatedAtBefore 和 deleteAll
        verify(nonceRecordRepository).findByCreatedAtBefore(any());
        verify(nonceRecordRepository).deleteAll(Arrays.asList(expired1, expired2));
    }

    @Test
    @DisplayName("should_notDelete_when_noExpiredNoncesInDb")
    void should_notDelete_when_noExpiredNoncesInDb() {
        when(nonceRecordRepository.findByCreatedAtBefore(any()))
                .thenReturn(Collections.emptyList());

        protection.evictExpired();

        verify(nonceRecordRepository).findByCreatedAtBefore(any());
        verify(nonceRecordRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("should_notThrow_when_evictExpiredDbFails")
    void should_notThrow_when_evictExpiredDbFails() {
        when(nonceRecordRepository.findByCreatedAtBefore(any()))
                .thenThrow(new RuntimeException("DB fail"));

        // evictExpired 不应抛异常（仅告警）
        protection.evictExpired();

        verify(nonceRecordRepository).findByCreatedAtBefore(any());
    }

    // ==================== 降级模式（无 Repository） ====================

    @Test
    @DisplayName("should_useMemoryStore_when_repositoryNull")
    void should_useMemoryStore_when_repositoryNull() {
        ReplayProtection memoryOnly = new ReplayProtection(); // 无 Repository

        String nonce = "nonce-memory-test-001";
        // 首次记录成功
        memoryOnly.checkAndRecordNonce(nonce);
        // 重复记录抛异常
        assertThatThrownBy(() -> memoryOnly.checkAndRecordNonce(nonce))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Duplicate nonce");

        // 内存中应有 1 条
        assertThat(memoryOnly.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("should_rejectShortNonce_when_memoryMode")
    void should_rejectShortNonce_when_memoryMode() {
        ReplayProtection memoryOnly = new ReplayProtection();

        // 短 nonce 在内存模式下也应被拒绝
        assertThatThrownBy(() -> memoryOnly.checkAndRecordNonce("short"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("too short");

        assertThat(memoryOnly.size()).isEqualTo(0);
    }
}