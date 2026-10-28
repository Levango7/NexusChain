package org.nexus.signing.business;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.nexus.sdk.signing.BusinessApprovalPayloads;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2 业务批准凭据服务用例（ADR-036 §2.2）。
 *
 * <p>钉住的不变式：</p>
 * <ol>
 *   <li>登记：同单号同哈希幂等返回（不再 save）；同单号异哈希 ⇒ 5002 冲突；
 *       body 重算哈希与提交值不符 ⇒ 5002 冲突（防登记入口塞假哈希）；</li>
 *   <li>消费：按<b>请求实际参数</b>重算摘要比对；CAS 单次消费；</li>
 *   <li>不消费分派：ABSENT / HASH_MISMATCH / EXPIRED 可回落（有人审兜底）；
 *       <b>ALREADY_CONSUMED 不得回落</b>（可能已广播，回落即开二次放款通道）；</li>
 *   <li>广播失败回退：CONSUMED → REGISTERED 可重试；回退撞上并发状态变化 ⇒ 保持
 *       CONSUMED 交人工（fail-closed 方向）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessApprovalServiceTest {

    private static final String BA_ID = "WD-a2-0001";
    private static final String FROM = "0xplatform0000000000000000000000deadbeef";
    private static final String TO_HASH = "0xrecipienthash0011";
    private static final BigDecimal AMOUNT = new BigDecimal("50000");

    @Mock
    private SigningBusinessApprovalRepository repository;

    private BusinessApprovalService service;

    @BeforeEach
    void setUp() {
        service = new BusinessApprovalService(repository);
    }

    private static String canonicalHash() {
        return BusinessApprovalPayloads.canonicalHash(FROM, TO_HASH, AMOUNT, BA_ID);
    }

    private static BusinessApprovalService.Registration validRegistration() {
        return new BusinessApprovalService.Registration(
                BA_ID, canonicalHash(), AMOUNT, "USDT", FROM, TO_HASH,
                Instant.now().plusSeconds(3600), "[\"approver-1\"]");
    }

    private static SigningBusinessApprovalEntity storedRow(String state, String payloadHash) {
        SigningBusinessApprovalEntity e = new SigningBusinessApprovalEntity();
        e.setBusinessApprovalId(BA_ID);
        e.setPayloadHash(payloadHash);
        e.setAmount(AMOUNT);
        e.setCurrency("USDT");
        e.setFromPubkey(FROM);
        e.setToPubkeyHash(TO_HASH);
        e.setState(state);
        e.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
        e.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        e.setVersion(0L);
        return e;
    }

    // ==================== 登记 ====================

    @Test
    @DisplayName("登记成功：保存重算哈希 + REGISTERED + UTC expiresAt")
    void register_success_persistsCanonicalHash() {
        when(repository.findByBusinessApprovalId(BA_ID)).thenReturn(Optional.empty());

        Map<String, Object> data = service.register(validRegistration());

        assertEquals("REGISTERED", data.get("state"));
        assertEquals(Boolean.FALSE, data.get("idempotent"));
        ArgumentCaptor<SigningBusinessApprovalEntity> captor =
                ArgumentCaptor.forClass(SigningBusinessApprovalEntity.class);
        verify(repository).save(captor.capture());
        SigningBusinessApprovalEntity saved = captor.getValue();
        assertEquals(BA_ID, saved.getBusinessApprovalId());
        assertEquals(canonicalHash(), saved.getPayloadHash(),
                "登记落库的必须是按 body 字段重算的 canonical 摘要");
        assertEquals("REGISTERED", saved.getState());
        assertEquals(AMOUNT, saved.getAmount());
        assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(59)));
    }

    @Test
    @DisplayName("登记幂等：同单号同哈希 ⇒ 幂等返回，不再 save")
    void register_idempotent_sameHash() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("REGISTERED", canonicalHash())));

        Map<String, Object> data = service.register(validRegistration());

        assertEquals(Boolean.TRUE, data.get("idempotent"));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("登记冲突：同单号异哈希 ⇒ 5002 冲突（防同单号换载荷）")
    void register_conflict_differentHash() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("REGISTERED",
                        BusinessApprovalPayloads.canonicalHash(FROM, TO_HASH,
                                new BigDecimal("99999"), BA_ID))));

        assertThrows(BusinessApprovalService.PayloadHashConflictException.class,
                () -> service.register(validRegistration()));
    }

    @Test
    @DisplayName("登记冲突：body 重算哈希与提交 payloadHash 不符 ⇒ 拒绝（防塞假哈希）")
    void register_conflict_bodyHashMismatch() {
        BusinessApprovalService.Registration tampered = new BusinessApprovalService.Registration(
                BA_ID, "0".repeat(64), AMOUNT, "USDT", FROM, TO_HASH,
                Instant.now().plusSeconds(3600), null);

        assertThrows(BusinessApprovalService.PayloadHashConflictException.class,
                () -> service.register(tampered));
        verify(repository, never()).findByBusinessApprovalId(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("登记参数校验：缺单号 / 哈希格式错 / 金额非正 / 缺 expiresAt ⇒ IAE")
    void register_validation() {
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new BusinessApprovalService.Registration(" ", canonicalHash(), AMOUNT, "USDT",
                        FROM, TO_HASH, Instant.now(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new BusinessApprovalService.Registration(BA_ID, "nothex", AMOUNT, "USDT",
                        FROM, TO_HASH, Instant.now(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new BusinessApprovalService.Registration(BA_ID, canonicalHash(), BigDecimal.ZERO,
                        "USDT", FROM, TO_HASH, Instant.now(), null)));
        assertThrows(IllegalArgumentException.class, () -> service.register(
                new BusinessApprovalService.Registration(BA_ID, canonicalHash(), AMOUNT, "USDT",
                        FROM, TO_HASH, null, null)));
    }

    // ==================== 消费 ====================

    @Test
    @DisplayName("消费成功：CAS 赢得消费权")
    void tryConsume_casWins() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("REGISTERED", canonicalHash())));
        when(repository.consumeIfRegistered(eq(BA_ID), any())).thenReturn(1);

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertTrue(attempt.consumed());
        verify(repository).consumeIfRegistered(eq(BA_ID), any());
    }

    @Test
    @DisplayName("凭据不存在 → ABSENT（可回落旧流程）")
    void tryConsume_absent() {
        when(repository.findByBusinessApprovalId(BA_ID)).thenReturn(Optional.empty());

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertFalse(attempt.consumed());
        assertEquals(BusinessApprovalService.Reason.ABSENT, attempt.reason());
        verify(repository, never()).consumeIfRegistered(anyString(), any());
    }

    @Test
    @DisplayName("载荷不匹配（按请求参数重算 ≠ 登记哈希）→ HASH_MISMATCH，不消费")
    void tryConsume_hashMismatch() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("REGISTERED", canonicalHash())));
        BigDecimal tampered = new BigDecimal("99999");

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, tampered);

        assertFalse(attempt.consumed());
        assertEquals(BusinessApprovalService.Reason.HASH_MISMATCH, attempt.reason());
        verify(repository, never()).consumeIfRegistered(anyString(), any());
    }

    @Test
    @DisplayName("凭据过期 → EXPIRED，不消费（过期不续期）")
    void tryConsume_expired() {
        SigningBusinessApprovalEntity expired = storedRow("REGISTERED", canonicalHash());
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1));
        when(repository.findByBusinessApprovalId(BA_ID)).thenReturn(Optional.of(expired));

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertFalse(attempt.consumed());
        assertEquals(BusinessApprovalService.Reason.EXPIRED, attempt.reason());
    }

    @Test
    @DisplayName("凭据已消费 → ALREADY_CONSUMED（调用方必须 5003 终止，不得回落人审）")
    void tryConsume_alreadyConsumed() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("CONSUMED", canonicalHash())));

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertFalse(attempt.consumed());
        assertEquals(BusinessApprovalService.Reason.ALREADY_CONSUMED, attempt.reason());
        verify(repository, never()).consumeIfRegistered(anyString(), any());
    }

    @Test
    @DisplayName("CAS 输掉并发（另一路已消费）→ ALREADY_CONSUMED（fail-closed）")
    void tryConsume_lostRace() {
        when(repository.findByBusinessApprovalId(BA_ID))
                .thenReturn(Optional.of(storedRow("REGISTERED", canonicalHash())));
        when(repository.consumeIfRegistered(eq(BA_ID), any())).thenReturn(0);

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertFalse(attempt.consumed());
        assertEquals(BusinessApprovalService.Reason.ALREADY_CONSUMED, attempt.reason());
    }

    // ==================== 广播失败回退 ====================

    @Test
    @DisplayName("广播失败 → 回退 CONSUMED→REGISTERED（凭据可重试）")
    void revert_afterFailedBroadcast() {
        when(repository.revertConsumed(BA_ID)).thenReturn(1);

        service.revertConsumedAfterFailedBroadcast(BA_ID);

        verify(repository).revertConsumed(BA_ID);
    }

    @Test
    @DisplayName("回退撞上并发状态变化 → 保持 CONSUMED 交人工（不抛异常）")
    void revert_stateChanged_staysConsumed() {
        when(repository.revertConsumed(BA_ID)).thenReturn(0);

        service.revertConsumedAfterFailedBroadcast(BA_ID);

        verify(repository).revertConsumed(BA_ID);
    }

    // ==================== 审计旁路 ====================

    @Test
    @DisplayName("auditLogService 为 null（单测）→ 全流程照常（审计旁路）")
    void auditAbsent_flowUnaffected() {
        lenient().when(repository.findByBusinessApprovalId(BA_ID)).thenReturn(Optional.empty());

        BusinessApprovalService.ConsumeAttempt attempt =
                service.tryConsume(BA_ID, FROM, TO_HASH, AMOUNT);

        assertEquals(BusinessApprovalService.Reason.ABSENT, attempt.reason());
    }
}
