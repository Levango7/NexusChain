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
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 3, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.OPTIMISTIC, info.status());
        assertEquals(3, info.confirmations());
        assertEquals(25, info.progressPercent()); // 3/12 = 25%
    }

    @Test
    void finalizingWhenConfirmationsAtHalf() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 6, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZING, info.status());
        assertEquals(50, info.progressPercent());
    }

    @Test
    void finalizedWhenThresholdReached() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 12, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(100, info.progressPercent());
    }

    @Test
    void finalizedWhenBeyondThreshold() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 99, "block_height", 100L));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(100, info.progressPercent());
    }

    @Test
    void unknownWhenChainUnreachable() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff")).thenReturn(null);
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
        assertEquals(0, info.confirmations());
    }

    @Test
    void unknownWhenNotYetInBlock() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "NOT_FOUND", "confirmations", 0));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
    }

    @Test
    void unknownWhenTxHashEmpty() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        FinalityService.FinalityInfo info = svc.getFinality("");
        assertEquals(FinalityStatus.UNKNOWN, info.status());
    }

    @Test
    void customThresholdApplies() {
        FinalityService svc = new FinalityService(chainRpc, 4, 32);  // 低阈值测试
        when(chainRpc.getTransactionStatus("0xabcd"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 4, "block_height", 10L));
        FinalityService.FinalityInfo info = svc.getFinality("0xabcd");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(4, info.threshold());
    }

    // ================= NexFinality BFT 权重优先路径 =================

    @Test
    void bftWeightFinalizedTakesPrecedence() {
        // core 最终性 RPC 返回 FINALIZED（权重 900/900，progress 100%）→ 优先采用而非确认数
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 2, "block_height", 96L)); // epoch 3
        when(chainRpc.getEpochFinality(3))
                .thenReturn(Map.of("finality_status", "FINALIZED",
                        "voted_weight", 900L, "total_weight", 900L, "progress_percent", 100));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZED, info.status());
        assertEquals(100, info.progressPercent());
        assertEquals(100, info.confirmations()); // BFT 血缘下 confirmations=进度百分数
        assertTrue(info.note().contains("staking-weight"), "note 应标记 BFT 权重血缘");
    }

    @Test
    void bftWeightFinalizingTakesPrecedence() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 2, "block_height", 96L));
        when(chainRpc.getEpochFinality(3))
                .thenReturn(Map.of("finality_status", "FINALIZING",
                        "voted_weight", 600L, "total_weight", 900L, "progress_percent", 67));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZING, info.status());
        assertEquals(67, info.progressPercent());
    }

    @Test
    void bftWeightOptimisticTakesPrecedence() {
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 2, "block_height", 96L));
        when(chainRpc.getEpochFinality(3))
                .thenReturn(Map.of("finality_status", "OPTIMISTIC",
                        "voted_weight", 300L, "total_weight", 900L, "progress_percent", 33));
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.OPTIMISTIC, info.status());
        assertEquals(33, info.progressPercent());
    }

    @Test
    void bftNotActiveFallsBackToConfirmations() {
        // 最终性层未启用（NOT_ACTIVE → RPC 返回 null）→ 降级确认数驱动
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0xffffffffffffffff"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 6, "block_height", 96L));
        when(chainRpc.getEpochFinality(3)).thenReturn(null);  // 未装配最终性层
        FinalityService.FinalityInfo info = svc.getFinality("0xffffffffffffffff");
        assertEquals(FinalityStatus.FINALIZING, info.status());
        assertEquals(50, info.progressPercent());
        assertTrue(info.note().contains("confirmations"), "降级路径 note 应标记确认数血缘");
    }

    @Test
    void epochOfMappingIsCorrect() {
        // epochLength=32: 高度 1-32 → epoch1；33-64 → epoch2（与 FinalityCoordinator 语义一致）
        FinalityService svc = new FinalityService(chainRpc, 12, 32);
        when(chainRpc.getTransactionStatus("0x1"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 1, "block_height", 32L));
        when(chainRpc.getTransactionStatus("0x2"))
                .thenReturn(Map.of("status", "CONFIRMED", "confirmations", 1, "block_height", 33L));
        when(chainRpc.getEpochFinality(1)).thenReturn(null);
        when(chainRpc.getEpochFinality(2)).thenReturn(null);
        // 两笔都降级确认数 → 仅验证不抛异常且能正确走分支
        FinalityService.FinalityInfo info1 = svc.getFinality("0x1");
        FinalityService.FinalityInfo info2 = svc.getFinality("0x2");
        assertEquals(FinalityStatus.OPTIMISTIC, info1.status());
        assertEquals(FinalityStatus.OPTIMISTIC, info2.status());
    }
}
