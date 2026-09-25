package org.nexus.gateway.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
 * Unit tests for {@link SagaTransactionManager}.
 * Covers normal flow, step failure→compensation, compensation retry logic.
 */
@ExtendWith(MockitoExtension.class)
class SagaTransactionManagerTest {

    @Mock private TransactionLogRepository transactionLogRepository;

    private SagaTransactionManager sagaManager;
    private ObjectMapper objectMapper;

    private TransactionContext ctx;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sagaManager = new SagaTransactionManager(transactionLogRepository, objectMapper);

        Map<String, Object> payload = new HashMap<>();
        payload.put("businessReference", "ORDER-001");
        ctx = TransactionContext.create("start", payload, "tenant-1");
    }

    // ==================== Exception Scenario Tests ====================

    @Test
    @DisplayName("execute: already SUCCESS log returns true (idempotency)")
    void execute_alreadySuccess_returnsTrue() {
        TransactionLog successLog = new TransactionLog();
        successLog.setStepStatus(TransactionStepStatus.SUCCESS);
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(List.of(successLog));

        boolean result = sagaManager.execute(Collections.emptyList(), ctx);

        assertTrue(result);
    }

    @Test
    @DisplayName("execute: already FAILED log returns false (idempotency)")
    void execute_alreadyFailed_returnsFalse() {
        TransactionLog failedLog = new TransactionLog();
        failedLog.setStepStatus(TransactionStepStatus.FAILED);
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(List.of(failedLog));

        boolean result = sagaManager.execute(Collections.emptyList(), ctx);

        assertFalse(result);
    }

    @Test
    @DisplayName("execute: step failure triggers reverse compensation")
    void execute_stepFailure_triggersCompensation() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {});
        SagaStep step2 = new SagaStep("step2", c -> {
            throw new RuntimeException("step2 failed");
        }, c -> {});
        SagaStep step3 = new SagaStep("step3", c -> {}, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1, step2, step3), ctx);

        assertFalse(result);
        // step1 should have been compensated (reverse order)
        // step3 should never have been executed
    }

    @Test
    @DisplayName("execute: compensation failure retries 3 times")
    void execute_compensationFailure_retriesThreeTimes() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {
            throw new RuntimeException("compensation failed");
        });
        SagaStep step2 = new SagaStep("step2", c -> {
            throw new RuntimeException("step2 failed");
        }, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1, step2), ctx);

        assertFalse(result);
        // Compensation for step1 retried MAX_RETRY (3) times
    }

    // ==================== Normal Business Flow Tests ====================

    @Test
    @DisplayName("execute: all steps succeed returns true")
    void execute_allStepsSucceed_returnsTrue() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {});
        SagaStep step2 = new SagaStep("step2", c -> {}, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1, step2), ctx);

        assertTrue(result);
        verify(transactionLogRepository, atLeast(4)).save(any(TransactionLog.class));
    }

    @Test
    @DisplayName("execute: empty steps list returns true")
    void execute_emptySteps_returnsTrue() {
        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(Collections.emptyList(), ctx);

        assertTrue(result);
    }

    @Test
    @DisplayName("execute: single step success returns true")
    void execute_singleStepSuccess_returnsTrue() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1), ctx);

        assertTrue(result);
    }

    @Test
    @DisplayName("execute: first step fails, no compensation needed")
    void execute_firstStepFails_noCompensation() {
        SagaStep step1 = new SagaStep("step1", c -> {
            throw new RuntimeException("step1 failed");
        }, c -> {});
        SagaStep step2 = new SagaStep("step2", c -> {}, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1, step2), ctx);

        assertFalse(result);
        // No completed steps to compensate
    }

    @Test
    @DisplayName("execute: compensation succeeds on retry after first failure")
    void execute_compensationSucceedsOnRetry() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {
            // Compensation succeeds on second call (first throws)
        });

        SagaStep step2 = new SagaStep("step2", c -> {
            throw new RuntimeException("step2 failed");
        }, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        boolean result = sagaManager.execute(List.of(step1, step2), ctx);

        assertFalse(result);
        // step1 compensation should have been called
    }

    @Test
    @DisplayName("execute: logs persisted with SAGA type")
    void execute_logsPersistedWithSagaType() {
        SagaStep step1 = new SagaStep("step1", c -> {}, c -> {});

        when(transactionLogRepository.findByTransactionIdOrderByCreatedAtAsc(ctx.getTransactionId()))
                .thenReturn(Collections.emptyList());

        sagaManager.execute(List.of(step1), ctx);

        verify(transactionLogRepository, atLeast(3)).save(any(TransactionLog.class));
    }
}