package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.model.FinalityStatus;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ChainFinalityPolicy 测试 — 验证委托调用 FinalityService 和 null 处理逻辑。
 */
class ChainFinalityPolicyTest {

    @Test
    void getNameReturnsChainBft() {
        ChainFinalityPolicy policy = new ChainFinalityPolicy(null);
        assertEquals("chain-bft", policy.getName());
    }

    @Test
    void evaluateFinalityDelegatesToFinalityService() {
        FinalityService mockService = mock(FinalityService.class);
        FinalityService.FinalityInfo expectedInfo = new FinalityService.FinalityInfo(
                FinalityStatus.FINALIZED, 12, 12, "BFT weight based");
        when(mockService.getFinality("0xabc")).thenReturn(expectedInfo);

        ChainFinalityPolicy policy = new ChainFinalityPolicy(mockService);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(expectedInfo, result);
        verify(mockService).getFinality("0xabc");
    }

    @Test
    void evaluateFinalityReturnsUnknownWhenServiceIsNull() {
        ChainFinalityPolicy policy = new ChainFinalityPolicy(null);
        FinalityService.FinalityInfo result = policy.evaluateFinality("0xabc");

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        assertEquals(0, result.confirmations());
        assertEquals(12, result.threshold());
        assertTrue(result.note().contains("not available"));
    }

    @Test
    void getThresholdDelegatesToFinalityService() {
        FinalityService mockService = mock(FinalityService.class);
        when(mockService.getBlocksToFinalize()).thenReturn(12L);

        ChainFinalityPolicy policy = new ChainFinalityPolicy(mockService);
        assertEquals(12, policy.getThreshold());
    }

    @Test
    void getThresholdReturnsDefaultWhenServiceIsNull() {
        ChainFinalityPolicy policy = new ChainFinalityPolicy(null);
        assertEquals(12, policy.getThreshold());
    }

    @Test
    void evaluateFinalityWithNullTxHashDelegatesToService() {
        FinalityService mockService = mock(FinalityService.class);
        FinalityService.FinalityInfo info = new FinalityService.FinalityInfo(
                FinalityStatus.UNKNOWN, 0, 12, "no txHash");
        when(mockService.getFinality(null)).thenReturn(info);

        ChainFinalityPolicy policy = new ChainFinalityPolicy(mockService);
        FinalityService.FinalityInfo result = policy.evaluateFinality(null);

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        verify(mockService).getFinality(null);
    }

    @Test
    void evaluateFinalityWithEmptyTxHashDelegatesToService() {
        FinalityService mockService = mock(FinalityService.class);
        FinalityService.FinalityInfo info = new FinalityService.FinalityInfo(
                FinalityStatus.UNKNOWN, 0, 12, "no txHash");
        when(mockService.getFinality("")).thenReturn(info);

        ChainFinalityPolicy policy = new ChainFinalityPolicy(mockService);
        FinalityService.FinalityInfo result = policy.evaluateFinality("");

        assertEquals(FinalityStatus.UNKNOWN, result.status());
        verify(mockService).getFinality("");
    }

    @Test
    void implementsFinalityPolicyInterface() {
        ChainFinalityPolicy policy = new ChainFinalityPolicy(null);
        assertInstanceOf(FinalityPolicy.class, policy);
    }
}