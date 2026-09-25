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
 * Unit tests for {@link EscrowServiceImpl}.
 * Covers create escrow, fund escrow, confirm escrow, refund escrow.
 */
@ExtendWith(MockitoExtension.class)
class EscrowServiceImplTest {

    @Mock private EscrowTransactionRepository escrowRepository;
    @Mock private AccountService accountService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private EscrowServiceImpl escrowService;

    private MerchantAccount frozenAccount;

    @BeforeEach
    void setUp() {
        escrowService = new EscrowServiceImpl(escrowRepository, accountService, eventPublisher);

        frozenAccount = new MerchantAccount();
        frozenAccount.setId(2L);
        frozenAccount.setAccountId("MA100FROZEN");
        frozenAccount.setMerchantId(100L);
        frozenAccount.setAccountType(AccountType.FROZEN);
        frozenAccount.setBalance(BigDecimal.ZERO);
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("createEscrow: null merchantId throws IllegalArgumentException")
    void createEscrow_nullMerchantId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.createEscrow(null, 1L, new BigDecimal("100"), "0xBuyer", 7));
    }

    @Test
    @DisplayName("createEscrow: null orderId throws IllegalArgumentException")
    void createEscrow_nullOrderId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.createEscrow(100L, null, new BigDecimal("100"), "0xBuyer", 7));
    }

    @Test
    @DisplayName("createEscrow: zero amount throws IllegalArgumentException")
    void createEscrow_zeroAmount_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.createEscrow(100L, 1L, BigDecimal.ZERO, "0xBuyer", 7));
    }

    @Test
    @DisplayName("createEscrow: null buyerAddress throws IllegalArgumentException")
    void createEscrow_nullBuyerAddress_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.createEscrow(100L, 1L, new BigDecimal("100"), null, 7));
    }

    @Test
    @DisplayName("createEscrow: blank buyerAddress throws IllegalArgumentException")
    void createEscrow_blankBuyerAddress_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.createEscrow(100L, 1L, new BigDecimal("100"), "  ", 7));
    }

    @Test
    @DisplayName("createEscrow: duplicate orderId throws IllegalStateException")
    void createEscrow_duplicateOrderId_throws() {
        EscrowTransaction existing = new EscrowTransaction();
        existing.setEscrowNo("EC001");
        when(escrowRepository.findByOrderId(1L)).thenReturn(Optional.of(existing));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> escrowService.createEscrow(100L, 1L, new BigDecimal("100"), "0xBuyer", 7));
        assertTrue(ex.getMessage().contains("已存在担保交易"));
    }

    @Test
    @DisplayName("fundEscrow: null escrowNo throws IllegalArgumentException")
    void fundEscrow_nullEscrowNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.fundEscrow(null, "0xPayer"));
    }

    @Test
    @DisplayName("fundEscrow: blank escrowNo throws IllegalArgumentException")
    void fundEscrow_blankEscrowNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.fundEscrow("  ", "0xPayer"));
    }

    @Test
    @DisplayName("fundEscrow: escrow not found throws IllegalArgumentException")
    void fundEscrow_notFound_throws() {
        when(escrowRepository.findByEscrowNo("EC999")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> escrowService.fundEscrow("EC999", "0xPayer"));
    }

    @Test
    @DisplayName("fundEscrow: non-CREATED status throws IllegalStateException")
    void fundEscrow_wrongStatus_throws() {
        EscrowTransaction escrow = createEscrowTx();
        escrow.setStatus(EscrowStatus.FUNDED);
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));

        assertThrows(IllegalStateException.class,
                () -> escrowService.fundEscrow("EC001", "0xPayer"));
    }

    @Test
    @DisplayName("confirmEscrow: null escrowNo throws IllegalArgumentException")
    void confirmEscrow_nullEscrowNo_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.confirmEscrow(null));
    }

    @Test
    @DisplayName("confirmEscrow: non-FUNDED status throws IllegalStateException")
    void confirmEscrow_wrongStatus_throws() {
        EscrowTransaction escrow = createEscrowTx();
        escrow.setStatus(EscrowStatus.CREATED);
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));

        assertThrows(IllegalStateException.class,
                () -> escrowService.confirmEscrow("EC001"));
    }

    @Test
    @DisplayName("refundEscrow: non-FUNDED status throws IllegalStateException")
    void refundEscrow_wrongStatus_throws() {
        EscrowTransaction escrow = createEscrowTx();
        escrow.setStatus(EscrowStatus.CREATED);
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));

        assertThrows(IllegalStateException.class,
                () -> escrowService.refundEscrow("EC001"));
    }

    @Test
    @DisplayName("getEscrowStatus: null orderId throws IllegalArgumentException")
    void getEscrowStatus_nullOrderId_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> escrowService.getEscrowStatus(null));
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("createEscrow: valid creation with default autoConfirmDays")
    void createEscrow_validWithDefaultDays() {
        when(escrowRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(accountService.getOrCreateAccount(100L, AccountType.FROZEN)).thenReturn(frozenAccount);
        when(escrowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EscrowTransaction result = escrowService.createEscrow(100L, 1L, new BigDecimal("100"), "0xBuyer", null);

        assertNotNull(result);
        assertEquals(EscrowStatus.CREATED, result.getStatus());
        assertEquals(100L, result.getMerchantId());
        assertEquals(1L, result.getOrderId());
        assertEquals(new BigDecimal("100"), result.getAmount());
        assertEquals("0xBuyer", result.getBuyerAddress());
        assertEquals(7, result.getAutoConfirmDays());
        assertEquals("MA100FROZEN", result.getEscrowAccountId());
        assertNotNull(result.getEscrowNo());
        assertTrue(result.getEscrowNo().startsWith("EC"));
        verify(eventPublisher).publishEvent(any(EscrowStatusChangedEvent.class));
    }

    @Test
    @DisplayName("createEscrow: valid creation with custom autoConfirmDays")
    void createEscrow_validWithCustomDays() {
        when(escrowRepository.findByOrderId(1L)).thenReturn(Optional.empty());
        when(accountService.getOrCreateAccount(100L, AccountType.FROZEN)).thenReturn(frozenAccount);
        when(escrowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EscrowTransaction result = escrowService.createEscrow(100L, 1L, new BigDecimal("100"), "0xBuyer", 14);

        assertEquals(14, result.getAutoConfirmDays());
    }

    @Test
    @DisplayName("fundEscrow: valid funding freezes amount and transitions to FUNDED")
    void fundEscrow_valid_freezesAndTransitions() {
        EscrowTransaction escrow = createEscrowTx();
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));
        when(escrowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EscrowTransaction result = escrowService.fundEscrow("EC001", "0xPayer");

        assertNotNull(result);
        assertEquals(EscrowStatus.FUNDED, result.getStatus());
        assertNotNull(result.getFundedAt());
        verify(accountService).freeze(100L, new BigDecimal("100"), "ESCROW_FREEZE:EC001");
        verify(eventPublisher, times(1)).publishEvent(any(EscrowStatusChangedEvent.class));
    }

    @Test
    @DisplayName("confirmEscrow: valid confirmation releases funds to merchant")
    void confirmEscrow_valid_releasesFunds() {
        EscrowTransaction escrow = createEscrowTx();
        escrow.setStatus(EscrowStatus.FUNDED);
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));
        when(escrowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EscrowTransaction result = escrowService.confirmEscrow("EC001");

        assertNotNull(result);
        assertEquals(EscrowStatus.RELEASED, result.getStatus());
        assertNotNull(result.getConfirmedAt());
        assertNotNull(result.getReleasedAt());
        verify(accountService).unfreeze(100L, new BigDecimal("100"));
        verify(accountService).deposit(eq(100L), eq(new BigDecimal("100")), eq("ESCROW_RELEASE:EC001"));
        // Two status changes: FUNDED→CONFIRMED, CONFIRMED→RELEASED
        verify(eventPublisher, times(2)).publishEvent(any(EscrowStatusChangedEvent.class));
    }

    @Test
    @DisplayName("refundEscrow: valid refund unfreezes and transitions to REFUNDED")
    void refundEscrow_valid_unfreezesAndTransitions() {
        EscrowTransaction escrow = createEscrowTx();
        escrow.setStatus(EscrowStatus.FUNDED);
        when(escrowRepository.findByEscrowNo("EC001")).thenReturn(Optional.of(escrow));
        when(escrowRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        EscrowTransaction result = escrowService.refundEscrow("EC001");

        assertNotNull(result);
        assertEquals(EscrowStatus.REFUNDED, result.getStatus());
        verify(accountService).unfreeze(100L, new BigDecimal("100"));
        verify(accountService, never()).deposit(any(), any(), any());
        verify(eventPublisher, times(1)).publishEvent(any(EscrowStatusChangedEvent.class));
    }

    @Test
    @DisplayName("getEscrowStatus: returns escrow when found")
    void getEscrowStatus_found_returnsEscrow() {
        EscrowTransaction escrow = createEscrowTx();
        when(escrowRepository.findByOrderId(1L)).thenReturn(Optional.of(escrow));

        EscrowTransaction result = escrowService.getEscrowStatus(1L);

        assertNotNull(result);
    }

    @Test
    @DisplayName("getEscrowStatus: returns null when not found")
    void getEscrowStatus_notFound_returnsNull() {
        when(escrowRepository.findByOrderId(99L)).thenReturn(Optional.empty());

        EscrowTransaction result = escrowService.getEscrowStatus(99L);

        assertNull(result);
    }

    // ==================== Helper Methods ====================

    private EscrowTransaction createEscrowTx() {
        EscrowTransaction escrow = new EscrowTransaction();
        escrow.setId(1L);
        escrow.setEscrowNo("EC001");
        escrow.setMerchantId(100L);
        escrow.setOrderId(1L);
        escrow.setAmount(new BigDecimal("100"));
        escrow.setStatus(EscrowStatus.CREATED);
        escrow.setBuyerAddress("0xBuyer");
        escrow.setEscrowAccountId("MA100FROZEN");
        escrow.setAutoConfirmDays(7);
        return escrow;
    }
}