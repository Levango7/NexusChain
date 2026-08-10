package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.gateway.ratelimit.IdempotencyStore;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrchestrationIdempotencyStore}: duplicate detection and
 * recording, including blank-request-id guards.
 */
class IdempotencyStoreTest {

    @Test
    @DisplayName("record stores the request_id -> payment_id mapping")
    void record_storesMapping() {
        IdempotencyStore backing = mock(IdempotencyStore.class);
        OrchestrationIdempotencyStore store = new OrchestrationIdempotencyStore(backing);

        store.record("req_1", "pay_1");

        verify(backing).put("req_1", "pay_1");
    }

    @Test
    @DisplayName("checkDuplicate returns the stored payment id on hit")
    void checkDuplicate_hit_returnsPaymentId() {
        IdempotencyStore backing = mock(IdempotencyStore.class);
        when(backing.get("req_1")).thenReturn("pay_1");
        OrchestrationIdempotencyStore store = new OrchestrationIdempotencyStore(backing);

        assertEquals("pay_1", store.checkDuplicate("req_1"));
    }

    @Test
    @DisplayName("checkDuplicate returns null on miss")
    void checkDuplicate_miss_returnsNull() {
        IdempotencyStore backing = mock(IdempotencyStore.class);
        when(backing.get("req_new")).thenReturn(null);
        OrchestrationIdempotencyStore store = new OrchestrationIdempotencyStore(backing);

        assertNull(store.checkDuplicate("req_new"));
    }

    @Test
    @DisplayName("blank request id is ignored by record and checkDuplicate")
    void blankRequestId_isIgnored() {
        IdempotencyStore backing = mock(IdempotencyStore.class);
        OrchestrationIdempotencyStore store = new OrchestrationIdempotencyStore(backing);

        assertNull(store.checkDuplicate(""));
        assertNull(store.checkDuplicate("   "));
        store.record("", "pay_x");

        verify(backing, never()).put(any(), any());
    }
}
