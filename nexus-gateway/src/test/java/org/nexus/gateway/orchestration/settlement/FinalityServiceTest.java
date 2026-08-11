package org.nexus.gateway.orchestration.settlement;

import org.junit.jupiter.api.Test;
import org.nexus.gateway.client.ChainRpcClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * FinalityService 三层最终化推导逻辑测试。
 */
class FinalityServiceTest {

    private final ChainRpcClient chainRpc = mock(ChainRpcClient.class);

    @Test
    void optimisticWhenConfirmationsBelowHalf() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 3, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.OPTIMISTIC, info.status());
        assertEquals(3, info.confirmations());
        assertEquals(25, info.progressPercent()); // 3/12 = 25%
    }

    @Test
    void finalizingWhenConfirmationsAtHalf() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 6, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZING, info.status());
        assertEquals(50, info.progressPercent());
    }

    @Test
    void finalizedWhenThresholdReached() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 12, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(100, info.progressPercent());
    }

    @Test
    void finalizedWhenBeyondThreshold() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 99, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(100, info.progressPercent());
    }

    @Test
    void unknownWhenChainUnreachable() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff")).thenReturn(null);
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
        assertEquals(0, info.confirmations());
    }

    @Test
    void unknownWhenNotYetInBlock() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "NOT_FOUND", "confirmations", 0));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
    }

    @Test
    void unknownWhenTxHashEmpty() {
        FinalityService svc = new FinalityService(chainRpc, 12);
        FinalityService.FinalityInfo info = svc.getFinality("");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
    }

    @Test
    void customThresholdApplies() {
        FinalityService svc = new FinalityService(chainRpc, 4);  // 低阈值测试
        when(chainRpc.getTransactionStatus("0xabcd"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 4, "block_height", 10L));
        FinalityService.FinalityInfo info = svc.getFinality("0xabcd");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(4, info.threshold());
    }
}
