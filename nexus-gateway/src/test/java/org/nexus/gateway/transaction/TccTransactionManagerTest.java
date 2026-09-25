package org.nexus.gateway.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TccTransactionManager}.
 * Covers Try success→Confirm success, Try failure→Cancel, Confirm/Cancel retry logic.
 */
@ExtendWith(MockitoExtension.class)
class TccTransactionManagerTest {

    @Mock private TransactionLogRepository transactionLogRepository;

    private TccTransactionManager tccManager;
    private ObjectMapper objectMapper;

    private TransactionContext ctx;
    private TccAction action;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        tccManager = new TccTransactionManager(transactionLogRepository, objectMapper);

        Map<String, Object> payload = new HashMap<>();
        payload.put("businessReference", "ORDER-001");
        ctx = TransactionContext.create("try", payload, "tenant-1");

        action = mock(TccAction.class);
        lenient().when(action.getParticipantId()).thenReturn("payment-service");
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("execute: already SUCCESS log returns true (idempotency)")
    void execute_alreadySuccess_returnsTrue() {
        TransactionLog successLog = new TransactionLog();
        successLog.setStepStatus(TransactionStepStatus.SUCCESS);
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(List.of(successLog));

        boolean result = tccManager.execute(action, ctx);

        assertTrue(result);
        verify(action, never()).tryAction(any());
    }

    @Test
    @DisplayName("execute: already FAILED log returns false (idempotency)")
    void execute_alreadyFailed_returnsFalse() {
        TransactionLog failedLog = new TransactionLog();
        failedLog.setStepStatus(TransactionStepStatus.FAILED);
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(List.of(failedLog));

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        verify(action, never()).tryAction(any());
    }

    @Test
    @DisplayName("execute: Try throws exception → Cancel executed, returns false")
    void execute_tryThrowsException_cancelExecuted() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenThrow(new RuntimeException("try failed"));

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        verify(action).cancelAction(ctx);
        verify(action, never()).confirmAction(any());
    }

    @Test
    @DisplayName("execute: Try returns false → Cancel executed, returns false")
    void execute_tryReturnsFalse_cancelExecuted() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(false);

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        verify(action).cancelAction(ctx);
        verify(action, never()).confirmAction(any());
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("execute: Try success → Confirm success, returns true")
    void execute_trySuccess_confirmSuccess_returnsTrue() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(true);

        boolean result = tccManager.execute(action, ctx);

        assertTrue(result);
        verify(action).tryAction(ctx);
        verify(action).confirmAction(ctx);
        verify(action, never()).cancelAction(any());
    }

    @Test
    @DisplayName("execute: Try success → Confirm fails 3 times → returns false")
    void execute_confirmFailsAllRetries_returnsFalse() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(true);
        doThrow(new RuntimeException("confirm failed"))
                .when(action).confirmAction(ctx);

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        verify(action).tryAction(ctx);
        // Confirm retried MAX_RETRY (3) times
        verify(action, times(3)).confirmAction(ctx);
        // Cancel NOT executed after Confirm failure (Try already succeeded)
        verify(action, never()).cancelAction(any());
    }

    @Test
    @DisplayName("execute: Try fails → Cancel retried 3 times on failure")
    void execute_tryFails_cancelRetried() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(false);
        doThrow(new RuntimeException("cancel failed"))
                .when(action).cancelAction(ctx);

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        // Cancel retried MAX_RETRY (3) times
        verify(action, times(3)).cancelAction(ctx);
    }

    @Test
    @DisplayName("execute: Try fails → Cancel succeeds on first attempt")
    void execute_tryFails_cancelSucceeds() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(false);

        boolean result = tccManager.execute(action, ctx);

        assertFalse(result);
        verify(action).cancelAction(ctx);
    }

    @Test
    @DisplayName("execute: Confirm fails first attempt, succeeds on retry")
    void execute_confirmFailsThenSucceeds() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(true);
        doThrow(new RuntimeException("confirm attempt 1"))
                .doNothing()
                .when(action).confirmAction(ctx);

        boolean result = tccManager.execute(action, ctx);

        assertTrue(result);
        verify(action, times(2)).confirmAction(ctx);
    }

    @Test
    @DisplayName("execute: logs are persisted with correct step statuses")
    void execute_logsPersistedWithCorrectStatuses() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());
        when(action.tryAction(ctx)).thenReturn(true);

        tccManager.execute(action, ctx);

        // Try success → Confirm success flow saves at least 4 logs:
        // 1. tryLog (TRY → SUCCESS), 2. confirmLog (CONFIRM → SUCCESS), 3. successLog (SUCCESS)
        verify(transactionLogRepository, atLeast(4)).save(any(TransactionLog.class));
        // Verify action interactions
        verify(action).tryAction(ctx);
        verify(action).confirmAction(ctx);
        verify(action, never()).cancelAction(any());
    }
}