package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.client.ConsortiumRpcClient;
import org.nexus.gateway.model.FinalityStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConsortiumFinalityPolicy 测试 — 验证 PoA 即时最终性逻辑和 null/异常处理。
 */
class ConsortiumFinalityPolicyTest {

    @Test
    void getNameReturnsConsortiumPoa() {
        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(null);
        assertEquals("consortium-poa", policy.getName());
    }

    @Test
    void getThresholdReturnsOne() {
        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(null);
        assertEquals(1, policy.getThreshold());
    }

    @Test
    void evaluateFinalityReturnsFinalizedWhenConfirmed() {
        ConsortiumRpcClient mockClient = mock(ConsortiumRpcClient.class);
        when(mockClient.isTransactionConfirmed("0xabc")).thenReturn(true);

        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(mockClient);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.FINALIZED, result.status());
        assertEquals(1, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("PoA instant finality"));
        verify(mockClient).isTransactionConfirmed("0xabc");
    }

    @Test
    void evaluateFinalityReturnsUnknownWhenNotConfirmed() {
        ConsortiumRpcClient mockClient = mock(ConsortiumRpcClient.class);
        when(mockClient.isTransactionConfirmed("0xabc")).thenReturn(false);

        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(mockClient);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        assertEquals(0, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("not yet in a block"));
        verify(mockClient).isTransactionConfirmed("0xabc");
    }

    @Test
    void evaluateFinalityReturnsUnknownWhenClientIsNull() {
        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(null);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        assertEquals(0, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("not available"));
    }

    @Test
    void evaluateFinalityReturnsUnknownOnRuntimeException() {
        ConsortiumRpcClient mockClient = mock(ConsortiumRpcClient.class);
        when(mockClient.isTransactionConfirmed("0xabc"))
                .thenThrow(new RuntimeException("connection refused"));

        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(mockClient);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        assertEquals(0, result.confirmations());
        assertEquals(1, result.threshold());
        assertTrue(result.note().contains("consortium RPC error"));
    }

    @Test
    void evaluateFinalityWithNullTxHashCallsClient() {
        ConsortiumRpcClient mockClient = mock(ConsortiumRpcClient.class);
        when(mockClient.isTransactionConfirmed(null)).thenReturn(false);

        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(mockClient);
        FinalityService.FinalityInfo result = policy.evaluateFinality(null);

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        verify(mockClient).isTransactionConfirmed(null);
    }

    @Test
    void implementsFinalityPolicyInterface() {
        ConsortiumFinalityPolicy policy = new ConsortiumFinalityPolicy(null);
        assertInstanceOf(FinalityPolicy.class, policy);
    }
}