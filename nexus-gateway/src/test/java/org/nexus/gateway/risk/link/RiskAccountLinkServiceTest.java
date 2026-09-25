package org.nexus.gateway.risk.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountStatus;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RiskAccountLinkService}.
 * Covers freeze/unfreeze idempotency, status change linkage, and idempotent skip for CLOSED accounts.
 */
@ExtendWith(MockitoExtension.class)
class RiskAccountLinkServiceTest {

    @Mock private RiskAccountLinkRecordRepository linkRecordRepository;
    @Mock private AccountService accountService;
    @Mock private MerchantAccountRepository accountRepository;

    private RiskAccountLinkService riskAccountLinkService;

    private MerchantAccount balanceAccount;
    private MerchantAccount frozenAccount;

    private static final String RISK_EVENT_ID = "RISK-001";
    private static final Long MERCHANT_ID = 100L;
    private static final BigDecimal AMOUNT = new BigDecimal("500");
    private static final String REASON = "风控冻结";

    @BeforeEach
    void setUp() throws Exception {
        riskAccountLinkService = new RiskAccountLinkService(linkRecordRepository, accountService, accountRepository);

        // 设置 self-injection proxy（单元测试中无 Spring 代理，直接设置为自身）
        java.lang.reflect.Field selfField = RiskAccountLinkService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(riskAccountLinkService, riskAccountLinkService);

        balanceAccount = new MerchantAccount();
        balanceAccount.setId(1L);
        balanceAccount.setAccountId("MA100BALANCE");
        balanceAccount.setMerchantId(MERCHANT_ID);
        balanceAccount.setAccountType(AccountType.BALANCE);
        balanceAccount.setBalance(new BigDecimal("1000"));
        balanceAccount.setStatus(AccountStatus.ACTIVE);

        frozenAccount = new MerchantAccount();
        frozenAccount.setId(2L);
        frozenAccount.setAccountId("MA100FROZEN");
        frozenAccount.setMerchantId(MERCHANT_ID);
        frozenAccount.setAccountType(AccountType.FROZEN);
        frozenAccount.setBalance(BigDecimal.ZERO);
        frozenAccount.setStatus(AccountStatus.ACTIVE);
    }

    // ==================== 冻结联动 ====================

    @Test
    @DisplayName("executeFreeze: 冻结正常 — freeze 成功 + RiskAccountLinkRecord 创建")
    void executeFreeze_normalCase_freezeSuccessAndRecordCreated() {
        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.FREEZE))
                .thenReturn(Optional.empty());
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeFreeze(RISK_EVENT_ID, MERCHANT_ID, AMOUNT, REASON);

        assertNotNull(result);
        assertEquals(LinkAction.FREEZE, result.getLinkAction());
        assertEquals(ExecutionStatus.SUCCESS, result.getExecutionStatus());
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(AMOUNT, result.getAmount());
        assertEquals(REASON, result.getReason());
        assertNotNull(result.getExecutedAt());
        assertNotNull(result.getCompletedAt());

        verify(accountService).freeze(MERCHANT_ID, AMOUNT, REASON);
        verify(linkRecordRepository, times(2)).save(any(RiskAccountLinkRecord.class));
    }

    @Test
    @DisplayName("executeFreeze: 幂等跳过冻结 — 同一 riskEventId + FREEZE 重复调用返回 SKIPPED")
    void executeFreeze_idempotentSkip_returnsSkipped() {
        RiskAccountLinkRecord existingRecord = new RiskAccountLinkRecord();
        existingRecord.setId(10L);
        existingRecord.setRiskEventId(RISK_EVENT_ID);
        existingRecord.setLinkAction(LinkAction.FREEZE);
        existingRecord.setMerchantId(MERCHANT_ID);
        existingRecord.setAmount(AMOUNT);
        existingRecord.setReason(REASON);
        existingRecord.setExecutionStatus(ExecutionStatus.SUCCESS);

        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.FREEZE))
                .thenReturn(Optional.of(existingRecord));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeFreeze(RISK_EVENT_ID, MERCHANT_ID, AMOUNT, REASON);

        assertNotNull(result);
        assertEquals(ExecutionStatus.SKIPPED, result.getExecutionStatus());
        assertEquals(LinkAction.FREEZE, result.getLinkAction());

        verify(accountService, never()).freeze(any(), any(), any());
        verify(linkRecordRepository).save(any(RiskAccountLinkRecord.class));
    }

    // ==================== 解冻联动 ====================

    @Test
    @DisplayName("executeUnfreeze: 解冻正常 — unfreeze 成功")
    void executeUnfreeze_normalCase_unfreezeSuccess() {
        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.UNFREEZE))
                .thenReturn(Optional.empty());
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeUnfreeze(RISK_EVENT_ID, MERCHANT_ID, AMOUNT, "风控解冻");

        assertNotNull(result);
        assertEquals(LinkAction.UNFREEZE, result.getLinkAction());
        assertEquals(ExecutionStatus.SUCCESS, result.getExecutionStatus());
        assertEquals(MERCHANT_ID, result.getMerchantId());
        assertEquals(AMOUNT, result.getAmount());
        assertNotNull(result.getExecutedAt());
        assertNotNull(result.getCompletedAt());

        verify(accountService).unfreeze(MERCHANT_ID, AMOUNT);
        verify(linkRecordRepository, times(2)).save(any(RiskAccountLinkRecord.class));
    }

    @Test
    @DisplayName("executeUnfreeze: 幂等跳过解冻 — 同一 riskEventId + UNFREEZE 重复调用返回 SKIPPED")
    void executeUnfreeze_idempotentSkip_returnsSkipped() {
        RiskAccountLinkRecord existingRecord = new RiskAccountLinkRecord();
        existingRecord.setId(20L);
        existingRecord.setRiskEventId(RISK_EVENT_ID);
        existingRecord.setLinkAction(LinkAction.UNFREEZE);
        existingRecord.setMerchantId(MERCHANT_ID);
        existingRecord.setAmount(AMOUNT);
        existingRecord.setExecutionStatus(ExecutionStatus.SUCCESS);

        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.UNFREEZE))
                .thenReturn(Optional.of(existingRecord));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeUnfreeze(RISK_EVENT_ID, MERCHANT_ID, AMOUNT, "风控解冻");

        assertNotNull(result);
        assertEquals(ExecutionStatus.SKIPPED, result.getExecutionStatus());
        assertEquals(LinkAction.UNFREEZE, result.getLinkAction());

        verify(accountService, never()).unfreeze(any(), any());
        verify(linkRecordRepository).save(any(RiskAccountLinkRecord.class));
    }

    // ==================== 状态变更联动 ====================

    @Test
    @DisplayName("executeStatusChange: 状态变更 STATUS_FROZEN → AccountStatus.FROZEN")
    void executeStatusChange_statusFrozen_setsAccountFrozen() {
        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.STATUS_FROZEN))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeStatusChange(
                RISK_EVENT_ID, MERCHANT_ID, LinkAction.STATUS_FROZEN, "风控冻结状态");

        assertNotNull(result);
        assertEquals(LinkAction.STATUS_FROZEN, result.getLinkAction());
        assertEquals(ExecutionStatus.SUCCESS, result.getExecutionStatus());

        verify(accountService).changeStatus(MERCHANT_ID, AccountType.BALANCE, AccountStatus.FROZEN);
        verify(accountService).changeStatus(MERCHANT_ID, AccountType.FROZEN, AccountStatus.FROZEN);
    }

    @Test
    @DisplayName("executeStatusChange: 状态变更 STATUS_CLOSED → AccountStatus.CLOSED")
    void executeStatusChange_statusClosed_setsAccountClosed() {
        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.STATUS_CLOSED))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeStatusChange(
                RISK_EVENT_ID, MERCHANT_ID, LinkAction.STATUS_CLOSED, "风控关闭账户");

        assertNotNull(result);
        assertEquals(LinkAction.STATUS_CLOSED, result.getLinkAction());
        assertEquals(ExecutionStatus.SUCCESS, result.getExecutionStatus());

        verify(accountService).changeStatus(MERCHANT_ID, AccountType.BALANCE, AccountStatus.CLOSED);
        verify(accountService).changeStatus(MERCHANT_ID, AccountType.FROZEN, AccountStatus.CLOSED);
    }

    @Test
    @DisplayName("executeStatusChange: 恢复 ACTIVE — STATUS_RESTORED → AccountStatus.ACTIVE")
    void executeStatusChange_statusRestored_setsAccountActive() {
        // 账户当前处于冻结状态，恢复后应为 ACTIVE
        balanceAccount.setStatus(AccountStatus.FROZEN);
        frozenAccount.setStatus(AccountStatus.FROZEN);

        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.STATUS_RESTORED))
                .thenReturn(Optional.empty());
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.FROZEN))
                .thenReturn(Optional.of(frozenAccount));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeStatusChange(
                RISK_EVENT_ID, MERCHANT_ID, LinkAction.STATUS_RESTORED, "风控恢复账户");

        assertNotNull(result);
        assertEquals(LinkAction.STATUS_RESTORED, result.getLinkAction());
        assertEquals(ExecutionStatus.SUCCESS, result.getExecutionStatus());

        verify(accountService).changeStatus(MERCHANT_ID, AccountType.BALANCE, AccountStatus.ACTIVE);
        verify(accountService).changeStatus(MERCHANT_ID, AccountType.FROZEN, AccountStatus.ACTIVE);
    }

    @Test
    @DisplayName("executeStatusChange: 已 CLOSED 不重复操作 — 幂等跳过返回 SKIPPED")
    void executeStatusChange_alreadyClosed_idempotentSkip() {
        RiskAccountLinkRecord existingRecord = new RiskAccountLinkRecord();
        existingRecord.setId(30L);
        existingRecord.setRiskEventId(RISK_EVENT_ID);
        existingRecord.setLinkAction(LinkAction.STATUS_CLOSED);
        existingRecord.setMerchantId(MERCHANT_ID);
        existingRecord.setExecutionStatus(ExecutionStatus.SUCCESS);

        when(linkRecordRepository.findByRiskEventIdAndLinkAction(RISK_EVENT_ID, LinkAction.STATUS_CLOSED))
                .thenReturn(Optional.of(existingRecord));
        when(linkRecordRepository.save(any(RiskAccountLinkRecord.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RiskAccountLinkRecord result = riskAccountLinkService.executeStatusChange(
                RISK_EVENT_ID, MERCHANT_ID, LinkAction.STATUS_CLOSED, "风控关闭账户");

        assertNotNull(result);
        assertEquals(ExecutionStatus.SKIPPED, result.getExecutionStatus());
        assertEquals(LinkAction.STATUS_CLOSED, result.getLinkAction());

        // 确保不重复执行状态变更操作
        verify(accountService, never()).changeStatus(any(), any(), any());
        verify(accountRepository, never()).save(any(MerchantAccount.class));
        verify(linkRecordRepository).save(any(RiskAccountLinkRecord.class));
    }
}