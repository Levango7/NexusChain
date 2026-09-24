package org.nexus.gateway.reconciliation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * SuspenseAccountService 单元测试。
 *
 * <p>覆盖自动挂账创建、核销、注销、查询等核心场景。</p>
 */
class SuspenseAccountServiceTest {

    private SuspenseAccountRepository suspenseAccountRepository;
    private SuspenseAccountService suspenseAccountService;

    private static final Long MERCHANT_ID = 500L;
    private static final Long DISCREPANCY_ID = 100L;

    @BeforeEach
    void setUp() {
        suspenseAccountRepository = mock(SuspenseAccountRepository.class);
        suspenseAccountService = new SuspenseAccountService(suspenseAccountRepository);
    }

    // ==================== autoCreateSuspenseAccount ====================

    @Test
    @DisplayName("autoCreateSuspenseAccount — LONG_AMOUNT 差错创建 LONG_PAYMENT 挂账")
    void autoCreateLongAmount() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.LONG_AMOUNT);
        discrepancy.setAmountDiff(new BigDecimal("100"));

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(SuspenseAccount.DiscrepancyType.LONG_PAYMENT, result.getDiscrepancyType());
        assertEquals(SuspenseAccount.SuspenseStatus.PENDING, result.getStatus());
        assertEquals(new BigDecimal("100"), result.getAmount());
        assertEquals(DISCREPANCY_ID, result.getDiscrepancyId());
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertNotNull(result.getDescription());
    }

    @Test
    @DisplayName("autoCreateSuspenseAccount — SHORT_AMOUNT 差错创建 SHORT_PAYMENT 挂账")
    void autoCreateShortAmount() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.SHORT_AMOUNT);
        discrepancy.setAmountDiff(new BigDecimal("50"));

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(SuspenseAccount.DiscrepancyType.SHORT_PAYMENT, result.getDiscrepancyType());
        assertEquals(new BigDecimal("50"), result.getAmount());
    }

    @Test
    @DisplayName("autoCreateSuspenseAccount — AMOUNT_MISMATCH 差错创建 AMOUNT_MISMATCH 挂账")
    void autoCreateAmountMismatch() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.AMOUNT_MISMATCH);
        discrepancy.setAmountDiff(new BigDecimal("5"));

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(SuspenseAccount.DiscrepancyType.AMOUNT_MISMATCH, result.getDiscrepancyType());
    }

    @Test
    @DisplayName("autoCreateSuspenseAccount — STATUS_MISMATCH 差错创建 STATUS_MISMATCH 挂账")
    void autoCreateStatusMismatch() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH);

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(SuspenseAccount.DiscrepancyType.STATUS_MISMATCH, result.getDiscrepancyType());
    }

    @Test
    @DisplayName("autoCreateSuspenseAccount — INFO_MISMATCH 差错映射为 STATUS_MISMATCH 挂账")
    void autoCreateInfoMismatch() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.INFO_MISMATCH);

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(SuspenseAccount.DiscrepancyType.STATUS_MISMATCH, result.getDiscrepancyType());
    }

    @Test
    @DisplayName("autoCreateSuspenseAccount — amountDiff 为 null 时金额默认为 0")
    void autoCreateNullAmountDiff() {
        ReconciliationDiscrepancy discrepancy = createDiscrepancy(
                ReconciliationDiscrepancy.DiscrepancyType.STATUS_MISMATCH);
        discrepancy.setAmountDiff(null);

        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.autoCreateSuspenseAccount(discrepancy);

        assertEquals(BigDecimal.ZERO, result.getAmount());
    }

    // ==================== resolveSuspenseAccount ====================

    @Test
    @DisplayName("resolveSuspenseAccount — PENDING → RESOLVED")
    void resolveFromPending() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);

        when(suspenseAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.resolveSuspenseAccount(1L, "Resolved: matched with channel");

        assertEquals(SuspenseAccount.SuspenseStatus.RESOLVED, result.getStatus());
        assertEquals("Resolved: matched with channel", result.getResolutionNote());
        assertNotNull(result.getResolvedAt());
    }

    @Test
    @DisplayName("resolveSuspenseAccount — RESOLVED 状态不允许再次核销")
    void resolveFromResolvedThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setId(1L);

        when(suspenseAccountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> suspenseAccountService.resolveSuspenseAccount(1L, "test"));
    }

    @Test
    @DisplayName("resolveSuspenseAccount — WRITTEN_OFF 状态不允许核销")
    void resolveFromWrittenOffThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.WRITTEN_OFF);
        account.setId(1L);

        when(suspenseAccountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> suspenseAccountService.resolveSuspenseAccount(1L, "test"));
    }

    @Test
    @DisplayName("resolveSuspenseAccount — 不存在的 ID 抛出异常")
    void resolveNotFoundThrows() {
        when(suspenseAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> suspenseAccountService.resolveSuspenseAccount(999L, "test"));
    }

    // ==================== writeOffSuspenseAccount ====================

    @Test
    @DisplayName("writeOffSuspenseAccount — PENDING → WRITTEN_OFF")
    void writeOffFromPending() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);
        account.setId(1L);

        when(suspenseAccountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(suspenseAccountRepository.save(any(SuspenseAccount.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        SuspenseAccount result = suspenseAccountService.writeOffSuspenseAccount(1L, "Unrecoverable funds");

        assertEquals(SuspenseAccount.SuspenseStatus.WRITTEN_OFF, result.getStatus());
        assertEquals("Unrecoverable funds", result.getResolutionNote());
        assertNotNull(result.getResolvedAt());
    }

    @Test
    @DisplayName("writeOffSuspenseAccount — RESOLVED 状态不允许注销")
    void writeOffFromResolvedThrows() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.RESOLVED);
        account.setId(1L);

        when(suspenseAccountRepository.findById(1L)).thenReturn(Optional.of(account));

        assertThrows(IllegalStateException.class,
                () -> suspenseAccountService.writeOffSuspenseAccount(1L, "test"));
    }

    @Test
    @DisplayName("writeOffSuspenseAccount — 不存在的 ID 抛出异常")
    void writeOffNotFoundThrows() {
        when(suspenseAccountRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> suspenseAccountService.writeOffSuspenseAccount(999L, "test"));
    }

    // ==================== 查询方法 ====================

    @Test
    @DisplayName("findByMerchantId — 返回商户的所有挂账")
    void findByMerchantId() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);

        when(suspenseAccountRepository.findByMerchantId(MERCHANT_ID))
                .thenReturn(List.of(account));

        List<SuspenseAccount> result = suspenseAccountService.findByMerchantId(MERCHANT_ID);

        assertEquals(1, result.size());
        verify(suspenseAccountRepository).findByMerchantId(MERCHANT_ID);
    }

    @Test
    @DisplayName("findByStatus — 按挂账状态查询")
    void findByStatus() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);

        when(suspenseAccountRepository.findByStatus(SuspenseAccount.SuspenseStatus.PENDING))
                .thenReturn(List.of(account));

        List<SuspenseAccount> result =
                suspenseAccountService.findByStatus(SuspenseAccount.SuspenseStatus.PENDING);

        assertEquals(1, result.size());
        verify(suspenseAccountRepository).findByStatus(SuspenseAccount.SuspenseStatus.PENDING);
    }

    @Test
    @DisplayName("findByDiscrepancyType — 按差错类型查询")
    void findByDiscrepancyType() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);

        when(suspenseAccountRepository.findByDiscrepancyType(
                SuspenseAccount.DiscrepancyType.LONG_PAYMENT))
                .thenReturn(List.of(account));

        List<SuspenseAccount> result =
                suspenseAccountService.findByDiscrepancyType(
                        SuspenseAccount.DiscrepancyType.LONG_PAYMENT);

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("findByMerchantIdAndStatus — 按商户+状态查询")
    void findByMerchantIdAndStatus() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);

        when(suspenseAccountRepository.findByMerchantIdAndStatus(
                MERCHANT_ID, SuspenseAccount.SuspenseStatus.PENDING))
                .thenReturn(List.of(account));

        List<SuspenseAccount> result =
                suspenseAccountService.findByMerchantIdAndStatus(
                        MERCHANT_ID, SuspenseAccount.SuspenseStatus.PENDING);

        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("findByDiscrepancyId — 按关联差错 ID 查询")
    void findByDiscrepancyId() {
        SuspenseAccount account = createSuspenseAccount(SuspenseAccount.SuspenseStatus.PENDING);

        when(suspenseAccountRepository.findByDiscrepancyId(DISCREPANCY_ID))
                .thenReturn(List.of(account));

        List<SuspenseAccount> result =
                suspenseAccountService.findByDiscrepancyId(DISCREPANCY_ID);

        assertEquals(1, result.size());
        verify(suspenseAccountRepository).findByDiscrepancyId(DISCREPANCY_ID);
    }

    // ==================== 辅助方法 ====================

    private ReconciliationDiscrepancy createDiscrepancy(
            ReconciliationDiscrepancy.DiscrepancyType type) {
        ReconciliationDiscrepancy discrepancy = new ReconciliationDiscrepancy();
        discrepancy.setId(DISCREPANCY_ID);
        discrepancy.setMerchantId(MERCHANT_ID);
        discrepancy.setDiscrepancyType(type);
        discrepancy.setStatus(ReconciliationDiscrepancy.DiscrepancyStatus.DISCOVERED);
        discrepancy.setTransactionId("ORD-001");
        discrepancy.setResolutionType(ReconciliationDiscrepancy.ResolutionType.PENDING_INVESTIGATION);
        discrepancy.setCreatedAt(LocalDateTime.now());
        discrepancy.setUpdatedAt(LocalDateTime.now());
        return discrepancy;
    }

    private SuspenseAccount createSuspenseAccount(SuspenseAccount.SuspenseStatus status) {
        SuspenseAccount account = new SuspenseAccount();
        account.setDiscrepancyId(DISCREPANCY_ID);
        account.setMerchantId(MERCHANT_ID);
        account.setAmount(new BigDecimal("100"));
        account.setDiscrepancyType(SuspenseAccount.DiscrepancyType.LONG_PAYMENT);
        account.setStatus(status);
        account.setCreatedAt(LocalDateTime.now());
        return account;
    }
}