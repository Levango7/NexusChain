package org.nexus.gateway.escrow;

import org.nexus.gateway.account.AccountService;
import org.nexus.gateway.account.AccountType;
import org.nexus.gateway.account.MerchantAccount;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PreAuthServiceImpl}.
 * Covers create pre-auth, capture pre-auth, void pre-auth, partial capture.
 */
@ExtendWith(MockitoExtension.class)
class PreAuthServiceImplTest {

    @Mock private PreAuthTransactionRepository preauthRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private PreAuthServiceImpl preAuthService;

    private MerchantAccount frozenAccount;

    @BeforeEach
    void setUp() {
        preAuthService = new PreAuthServiceImpl(preauthRepository, accountService, eventPublisher);

        frozenAccount = new MerchantAccount();
        frozenAccount.setId(2L);
        frozenAccount.setAccountId("MA100FROZEN");
        frozenAccount.setMerchantId(100L);
        frozenAccount.setAccountType(AccountType.FROZEN);
        frozenAccount.setBalance(BigDecimal.ZERO);
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("createPreAuth: null merchantId throws IllegalArgumentException")
    void createPreAuth_nullMerchantId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.createPreAuth(null, 1L, new BigDecimal("100"), 3));
    }

    @Test
    @DisplayName("createPreAuth: zero freezeAmount throws IllegalArgumentException")
    void createPreAuth_zeroAmount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.createPreAuth(100L, 1L, BigDecimal.ZERO, 3));
    }

    @Test
    @DisplayName("createPreAuth: null freezeAmount throws IllegalArgumentException")
    void createPreAuth_nullAmount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.createPreAuth(100L, 1L, null, 3));
    }

    @Test
    @DisplayName("capturePreAuth: null preauthNo throws IllegalArgumentException")
    void capturePreAuth_nullPreauthNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.capturePreAuth(null, new BigDecimal("50")));
    }

    @Test
    @DisplayName("capturePreAuth: blank preauthNo throws IllegalArgumentException")
    void capturePreAuth_blankPreauthNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.capturePreAuth("  ", new BigDecimal("50")));
    }

    @Test
    @DisplayName("capturePreAuth: zero captureAmount throws IllegalArgumentException")
    void capturePreAuth_zeroAmount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.capturePreAuth("PA001", BigDecimal.ZERO));
    }

    @Test
    @DisplayName("capturePreAuth: preauth not found throws IllegalArgumentException")
    void capturePreAuth_notFound_throws() {
        when(preauthRepository.findByPreauthNo("PA999")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.capturePreAuth("PA999", new BigDecimal("50")));
    }

    @Test
    @DisplayName("capturePreAuth: non-AUTHORIZED status throws IllegalStateException")
    void capturePreAuth_wrongStatus_throws() {
        PreAuthTransaction preauth = createPreAuthTx();
        preauth.setStatus(PreAuthStatus.CAPTURED);
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));

        assertThrows(IllegalStateException.class,
                () -> preAuthService.capturePreAuth("PA001", new BigDecimal("50")));
    }

    @Test
    @DisplayName("capturePreAuth: captureAmount exceeds freezeAmount throws IllegalArgumentException")
    void capturePreAuth_exceedsFreeze_throws() {
        PreAuthTransaction preauth = createPreAuthTx();
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> preAuthService.capturePreAuth("PA001", new BigDecimal("150")));
        assertTrue(ex.getMessage().contains("扣款金额超过冻结金额"));
    }

    @Test
    @DisplayName("voidPreAuth: null preauthNo throws IllegalArgumentException")
    void voidPreAuth_nullPreauthNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.voidPreAuth(null));
    }

    @Test
    @DisplayName("voidPreAuth: non-AUTHORIZED status throws IllegalStateException")
    void voidPreAuth_wrongStatus_throws() {
        PreAuthTransaction preauth = createPreAuthTx();
        preauth.setStatus(PreAuthStatus.VOIDED);
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));

        assertThrows(IllegalStateException.class,
                () -> preAuthService.voidPreAuth("PA001"));
    }

    @Test
    @DisplayName("getPreAuthStatus: null orderId throws IllegalArgumentException")
    void getPreAuthStatus_nullOrderId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> preAuthService.getPreAuthStatus(null));
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("createPreAuth: valid creation freezes amount and sets AUTHORIZED")
    void createPreAuth_valid_freezesAndCreates() {
        when(accountService.getOrCreateAccount(100L, AccountType.FROZEN)).thenReturn(frozenAccount);
        when(preauthRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PreAuthTransaction result = preAuthService.createPreAuth(100L, 1L, new BigDecimal("100"), null);

        assertNotNull(result);
        assertEquals(PreAuthStatus.AUTHORIZED, result.getStatus());
        assertEquals(100L, result.getMerchantId());
        assertEquals(new BigDecimal("100"), result.getFreezeAmount());
        assertEquals(BigDecimal.ZERO, result.getCaptureAmount());
        assertEquals(3, result.getAutoReleaseDays());
        assertEquals("MA100FROZEN", result.getFrozenAccountId());
        assertNotNull(result.getPreauthNo());
        assertTrue(result.getPreauthNo().startsWith("PA"));
        verify(accountService).freeze(100L, new BigDecimal("100"), "PREAUTH_FREEZE");
        verify(eventPublisher).publishEvent(any(PreAuthStatusChangedEvent.class));
    }

    @Test
    @DisplayName("createPreAuth: custom autoReleaseDays")
    void createPreAuth_customDays() {
        when(accountService.getOrCreateAccount(100L, AccountType.FROZEN)).thenReturn(frozenAccount);
        when(preauthRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PreAuthTransaction result = preAuthService.createPreAuth(100L, 1L, new BigDecimal("100"), 5);

        assertEquals(5, result.getAutoReleaseDays());
    }

    @Test
    @DisplayName("capturePreAuth: full capture (captureAmount == freezeAmount)")
    void capturePreAuth_fullCapture() {
        PreAuthTransaction preauth = createPreAuthTx();
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));
        when(preauthRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PreAuthTransaction result = preAuthService.capturePreAuth("PA001", new BigDecimal("100"));

        assertNotNull(result);
        assertEquals(PreAuthStatus.CAPTURED, result.getStatus());
        assertEquals(new BigDecimal("100"), result.getCaptureAmount());
        assertNotNull(result.getCapturedAt());
        verify(accountService).unfreeze(100L, new BigDecimal("100"));
        verify(accountService).deposit(100L, new BigDecimal("100"), "PREAUTH_CAPTURE:PA001");
        verify(eventPublisher).publishEvent(any(PreAuthStatusChangedEvent.class));
    }

    @Test
    @DisplayName("capturePreAuth: partial capture (captureAmount < freezeAmount)")
    void capturePreAuth_partialCapture() {
        PreAuthTransaction preauth = createPreAuthTx();
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));
        when(preauthRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PreAuthTransaction result = preAuthService.capturePreAuth("PA001", new BigDecimal("30"));

        assertNotNull(result);
        assertEquals(PreAuthStatus.CAPTURED, result.getStatus());
        assertEquals(new BigDecimal("30"), result.getCaptureAmount());
        // unfreeze full amount, deposit only captured amount
        verify(accountService).unfreeze(100L, new BigDecimal("100"));
        verify(accountService).deposit(100L, new BigDecimal("30"), "PREAUTH_CAPTURE:PA001");
    }

    @Test
    @DisplayName("voidPreAuth: valid void releases all frozen amount")
    void voidPreAuth_valid_releasesFrozen() {
        PreAuthTransaction preauth = createPreAuthTx();
        when(preauthRepository.findByPreauthNo("PA001")).thenReturn(Optional.of(preauth));
        when(preauthRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        PreAuthTransaction result = preAuthService.voidPreAuth("PA001");

        assertNotNull(result);
        assertEquals(PreAuthStatus.VOIDED, result.getStatus());
        assertNotNull(result.getVoidedAt());
        verify(accountService).unfreeze(100L, new BigDecimal("100"));
        verify(accountService, never()).deposit(any(), any(), any());
        verify(eventPublisher).publishEvent(any(PreAuthStatusChangedEvent.class));
    }

    @Test
    @DisplayName("getPreAuthStatus: returns preauth when found")
    void getPreAuthStatus_found_returnsPreauth() {
        PreAuthTransaction preauth = createPreAuthTx();
        when(preauthRepository.findByOrderId(1L)).thenReturn(Optional.of(preauth));

        PreAuthTransaction result = preAuthService.getPreAuthStatus(1L);

        assertNotNull(result);
    }

    @Test
    @DisplayName("getPreAuthStatus: returns null when not found")
    void getPreAuthStatus_notFound_returnsNull() {
        when(preauthRepository.findByOrderId(99L)).thenReturn(Optional.empty());

        PreAuthTransaction result = preAuthService.getPreAuthStatus(99L);

        assertNull(result);
    }

    // ==================== Helper Methods ====================

    private PreAuthTransaction createPreAuthTx() {
        PreAuthTransaction preauth = new PreAuthTransaction();
        preauth.setId(1L);
        preauth.setPreauthNo("PA001");
        preauth.setMerchantId(100L);
        preauth.setOrderId(1L);
        preauth.setFreezeAmount(new BigDecimal("100"));
        preauth.setCaptureAmount(BigDecimal.ZERO);
        preauth.setStatus(PreAuthStatus.AUTHORIZED);
        preauth.setFrozenAccountId("MA100FROZEN");
        preauth.setAutoReleaseDays(3);
        return preauth;
    }
}