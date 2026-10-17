package org.nexus.gateway.risk.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.nexus.gateway.account.MerchantAccountRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 余额预警 alertFlag 的 CAS 语义测试（#35）。
 *
 * <p>守的是"谁翻动成功谁才发通知"这条去重规则本身：prod {@code replicaCount=3} 且除了 cron
 * 还有一条 {@code @Async} 事件驱动入口（{@link BalanceAlertListener}），旧写法
 * {@code findBy → setAlertFlag → save} 是 check-then-act，各副本都自认为赢了 ⇒ 同一跃迁 N 份告警。</p>
 *
 * <p>与 {@code BalanceAlertCasH2IT} 分工：这里验**接线**（返回值如何决定发不发通知、
 * 走哪条 CAS 分支），SQL 语句本身在真实 H2 上成立与否由那边验。</p>
 */
@ExtendWith(MockitoExtension.class)
class BalanceAlertCasTest {

    private static final long MERCHANT_ID = 77001L;
    private static final long ACCOUNT_ID = 9001L;

    @Mock private BalanceAlertConfigRepository configRepository;
    @Mock private MerchantAccountRepository accountRepository;
    @Mock private AccountService accountService;
    @Mock private BalanceAlertNotifier notifier;

    private BalanceAlertService service;

    @BeforeEach
    void setUp() {
        service = new BalanceAlertService(configRepository, accountRepository, accountService, notifier);
    }

    // ==================== 赢得 CAS 才发通知 ====================

    @Test
    @DisplayName("旧值为 NULL ⇒ 走 IS NULL 分支；CAS 返回 1 才发通知")
    void nullOldFlagUsesIsNullBranchAndNotifiesOnlyOnWin() {
        givenConfig();
        givenAccount(null, new BigDecimal("500"));
        when(accountRepository.compareAndSetAlertFlagFromNull(eq(ACCOUNT_ID), eq("WARNING"), any()))
                .thenReturn(1);

        AlertLevel level = service.checkBalanceAndAlert(MERCHANT_ID);

        assertEquals(AlertLevel.WARNING, level);
        verify(notifier).sendAlertNotification(eq(MERCHANT_ID), eq(AlertLevel.WARNING),
                eq(new BigDecimal("500")), any(BalanceAlertConfig.class));
        // 旧的 check-then-act 写路径必须已经消失
        verify(accountRepository, never()).save(any());
        verify(accountRepository, never()).compareAndSetAlertFlag(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("CAS 返回 0（别的副本已翻过）⇒ 一条通知都不发，但返回值仍是当前级别")
    void lostCasSuppressesNotification() {
        givenConfig();
        givenAccount(null, new BigDecimal("500"));
        when(accountRepository.compareAndSetAlertFlagFromNull(eq(ACCOUNT_ID), eq("WARNING"), any()))
                .thenReturn(0);

        AlertLevel level = service.checkBalanceAndAlert(MERCHANT_ID);

        assertEquals(AlertLevel.WARNING, level, "本副本算出的级别仍要回给调用方（controller 要展示）");
        verifyNoInteractions(notifier);
    }

    @Test
    @DisplayName("旧值非 NULL ⇒ 走 compareAndSetAlertFlag，且带的必须是「刚读到的那个旧值」")
    void nonNullOldFlagUsesEqualityBranch() {
        givenConfig();
        givenAccount("WARNING", new BigDecimal("150"));
        when(accountRepository.compareAndSetAlertFlag(eq(ACCOUNT_ID), eq("WARNING"), eq("CRITICAL"), any()))
                .thenReturn(1);

        assertEquals(AlertLevel.CRITICAL, service.checkBalanceAndAlert(MERCHANT_ID));

        ArgumentCaptor<String> oldCap = ArgumentCaptor.forClass(String.class);
        verify(accountRepository).compareAndSetAlertFlag(eq(ACCOUNT_ID), oldCap.capture(), eq("CRITICAL"), any());
        assertEquals("WARNING", oldCap.getValue(), "CAS 的期望旧值必须是刚读到的那个，不能拿目标值自比");
        verify(notifier).sendAlertNotification(eq(MERCHANT_ID), eq(AlertLevel.CRITICAL), any(), any());
        verify(accountRepository, never()).compareAndSetAlertFlagFromNull(anyLong(), any(), any());
    }

    // ==================== 不该动的情况 ====================

    @Test
    @DisplayName("级别没变 ⇒ 不发 CAS、不发通知（不给同一行制造无意义版本抖动）")
    void unchangedFlagTouchesNeitherCasNorNotifier() {
        givenConfig();
        givenAccount("WARNING", new BigDecimal("500"));

        assertEquals(AlertLevel.WARNING, service.checkBalanceAndAlert(MERCHANT_ID));

        verifyNoInteractions(notifier);
        verify(accountRepository, never()).compareAndSetAlertFlag(anyLong(), any(), any(), any());
        verify(accountRepository, never()).compareAndSetAlertFlagFromNull(anyLong(), any(), any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    @DisplayName("余额恢复（要清空 alertFlag）⇒ CAS 写 null 成功，但不发通知")
    void clearingFlagWinsCasButSendsNoNotification() {
        givenConfig();
        givenAccount("WARNING", new BigDecimal("999999"));
        when(accountRepository.compareAndSetAlertFlag(eq(ACCOUNT_ID), eq("WARNING"), isNull(), any()))
                .thenReturn(1);

        assertNull(service.checkBalanceAndAlert(MERCHANT_ID), "恢复正常应返回 null");

        verify(accountRepository).compareAndSetAlertFlag(eq(ACCOUNT_ID), eq("WARNING"), isNull(), any());
        verifyNoInteractions(notifier);
    }

    // ==================== 辅助 ====================

    private void givenConfig() {
        BalanceAlertConfig config = new BalanceAlertConfig();
        config.setMerchantId(MERCHANT_ID);
        config.setWarningThreshold(new BigDecimal("1000"));
        config.setCriticalThreshold(new BigDecimal("200"));
        config.setEmergencyThreshold(new BigDecimal("50"));
        config.setEnabled(true);
        when(configRepository.findByMerchantId(MERCHANT_ID)).thenReturn(Optional.of(config));
    }

    private void givenAccount(String alertFlag, BigDecimal balance) {
        MerchantAccount account = new MerchantAccount();
        account.setId(ACCOUNT_ID);
        account.setAccountId("MA" + MERCHANT_ID + "BALANCE");
        account.setMerchantId(MERCHANT_ID);
        account.setAccountType(AccountType.BALANCE);
        account.setBalance(balance);
        account.setAlertFlag(alertFlag);
        account.setUpdatedAt(LocalDateTime.now());
        when(accountRepository.findByMerchantIdAndAccountType(MERCHANT_ID, AccountType.BALANCE))
                .thenReturn(Optional.of(account));
    }
}
