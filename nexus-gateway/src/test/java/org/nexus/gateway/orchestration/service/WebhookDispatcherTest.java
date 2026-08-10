package org.nexus.gateway.orchestration.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OrchestrationWebhookDispatcher}: de-duplication,
 * retry with exponential backoff, and dead-letter after exhausting retries.
 *
 * <p>The {@code dispatch} method is {@code @Async}; when invoked on a plain
 * (non-Spring-proxied) instance it runs synchronously, so no async executor is
 * needed here. Retry delays are overridden with a fast array to keep the suite
 * fast.</p>
 */
class WebhookDispatcherTest {

    private static final long[] FAST_DELAYS = {1, 1, 1};

    private OrchestratedPayment samplePayment(String id, OrchPaymentStatus status) {
        OrchestratedPayment p = new OrchestratedPayment();
        p.setId(id);
        p.setStatus(status);
        p.setAmount(1000L);
        p.setCurrency("NEX");
        p.setConnectorId("chain");
        p.setTransactionHash("0xabc");
        p.setNotifyUrl("https://merchant.example/webhook");
        p.setCreatedAt(Instant.now());
        p.setConfirmedAt(Instant.now());
        return p;
    }

    @Test
    @DisplayName("dispatch: successful 2xx delivery leaves no dead-letter")
    void successfulDelivery_noDeadLetter() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        OrchestrationWebhookDispatcher d = new OrchestrationWebhookDispatcher(rt, FAST_DELAYS);
        d.dispatch(samplePayment("pay_1", OrchPaymentStatus.SUCCEEDED));

        assertEquals(0, d.getDeadLetterCount());
        verify(rt, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
    }

    @Test
    @DisplayName("dispatch: repeated failures exhaust retries and park a dead-letter")
    void repeatedFailures_parkDeadLetter() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenThrow(new RuntimeException("down"));

        OrchestrationWebhookDispatcher d = new OrchestrationWebhookDispatcher(rt, FAST_DELAYS);
        d.dispatch(samplePayment("pay_2", OrchPaymentStatus.SUCCEEDED));

        assertEquals(1, d.getDeadLetterCount());
        var dead = d.drainDeadLetters();
        assertEquals(1, dead.size());
        assertEquals("pay_2", dead.get(0).getPaymentId());
        assertEquals("SUCCEEDED", dead.get(0).getStatus());
    }

    @Test
    @DisplayName("dispatch: duplicate (same id+status) is de-duplicated, no second HTTP call")
    void duplicateDispatch_isDeduplicated() {
        RestTemplate rt = mock(RestTemplate.class);
        when(rt.exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class)))
                .thenReturn(new ResponseEntity<>("{}", HttpStatus.OK));

        OrchestrationWebhookDispatcher d = new OrchestrationWebhookDispatcher(rt, FAST_DELAYS);
        OrchestratedPayment p = samplePayment("pay_3", OrchPaymentStatus.SUCCEEDED);
        d.dispatch(p);
        d.dispatch(p);

        verify(rt, times(1)).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
        assertEquals(0, d.getDeadLetterCount());
    }

    @Test
    @DisplayName("dispatch: missing notifyUrl returns immediately without HTTP call")
    void missingNotifyUrl_noCall() {
        RestTemplate rt = mock(RestTemplate.class);
        OrchestrationWebhookDispatcher d = new OrchestrationWebhookDispatcher(rt, FAST_DELAYS);

        OrchestratedPayment p = samplePayment("pay_4", OrchPaymentStatus.SUCCEEDED);
        p.setNotifyUrl(null);
        d.dispatch(p);

        verify(rt, never()).exchange(anyString(), any(HttpMethod.class), any(HttpEntity.class), any(Class.class));
        assertEquals(0, d.getDeadLetterCount());
    }
}
