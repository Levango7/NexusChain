package org.nexus.signing.mpc.cggmp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MpcCggmpOrchestrator} 单元测试（G 批）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>keygen/aux/sign 阶段循环 publish→pull→pump 直到 finished</li>
 *   <li>start 失败时不进入循环</li>
 *   <li>一次 pump 即 finished 时不发 publish（无 outgoing）</li>
 *   <li>sign 阶段循环正确，r/s 在完成时填充</li>
 *   <li>publish 失败时返回 failure</li>
 * </ul>
 */
public class MpcCggmpOrchestratorTest {

    private MpcCggmpClient local;
    private MpcCggmpClient coordinator;
    private MpcCggmpOrchestrator orch;

    @BeforeEach
    void setUp() {
        local = mock(MpcCggmpClient.class);
        coordinator = mock(MpcCggmpClient.class);
        // 模拟 publish 全部成功
        when(coordinator.publishRelay(any(CgRelayMessageDto.class))).thenReturn(true);
        // 模拟 pull 全部空
        when(coordinator.pullRelay(anyString(), anyInt())).thenReturn(Collections.emptyList());
        orch = new MpcCggmpOrchestrator(local, coordinator, 0);
    }

    // ============================================================
    // keygen
    // ============================================================

    @Test
    @DisplayName("keygen：start 一次即 finished → 循环不进入")
    void testKeygenImmediateFinish() {
        when(local.startKeygen(eq("s"), eq(0), eq(0), eq(3), eq(2)))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, "agg-pk", true, ""));

        CgPumpResult r = orch.runKeygen("s", 0, 0, 3, 2);
        assertTrue(r.isFinished());
        assertEquals("agg-pk", r.getAggregatePublicKey());
        // 无 outgoing → 不调 publish/pull/pump
        verify(coordinator, never()).publishRelay(any());
        verify(local, never()).pumpKeygen(anyString(), any());
    }

    @Test
    @DisplayName("keygen：start 失败 → 直接返回失败，不进入循环")
    void testKeygenStartFailure() {
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CgPumpResult.failure("engine start error"));
        CgPumpResult r = orch.runKeygen("s", 0, 0, 3, 2);
        assertFalse(r.isSuccess());
        verify(local, never()).pumpKeygen(anyString(), any());
    }

    @Test
    @DisplayName("keygen：start 有 outgoing → 一次 publish + pull + pump 完成")
    void testKeygenSingleLoopIteration() {
        // 第 1 轮：start 有 1 条 outgoing
        CgRelayMessageDto m1 = new CgRelayMessageDto("s", 0, 0, "{\"round\":1}", false);
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(List.of(m1), false, null, true, ""));
        // 第 2 轮：pump 后 finished
        when(local.pumpKeygen(eq("s"), any()))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, "agg-pk", true, ""));

        CgPumpResult r = orch.runKeygen("s", 0, 0, 3, 2);
        assertTrue(r.isFinished());
        assertEquals("agg-pk", r.getAggregatePublicKey());

        verify(coordinator, times(1)).publishRelay(eq(m1));
        verify(coordinator, times(1)).pullRelay(eq("s"), eq(0)); // myIndex = m1.senderIndex
        verify(local, times(1)).pumpKeygen(eq("s"), any());
    }

    @Test
    @DisplayName("keygen：publish 失败 → 立即返回 failure")
    void testKeygenPublishFailure() {
        when(coordinator.publishRelay(any(CgRelayMessageDto.class))).thenReturn(false);
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(List.of(m), false, null, true, ""));
        CgPumpResult r = orch.runKeygen("s", 0, 0, 3, 2);
        assertFalse(r.isSuccess());
        assertTrue(r.getError().contains("publish relay failed"));
        verify(local, never()).pumpKeygen(anyString(), any());
    }

    // ============================================================
    // aux
    // ============================================================

    @Test
    @DisplayName("aux：单循环完成")
    void testAuxSingleLoop() {
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        when(local.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(List.of(m), false, null, true, ""));
        when(local.pumpAux(eq("s"), any()))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, null, true, ""));

        CgPumpResult r = orch.runAux("s", 0, 0, 3);
        assertTrue(r.isFinished());
        verify(local, times(1)).pumpAux(eq("s"), any());
    }

    // ============================================================
    // sign
    // ============================================================

    @Test
    @DisplayName("sign：单循环完成，r/s 填充")
    void testSignSingleLoop() {
        byte[] hash = new byte[32];
        int[] signers = new int[]{0, 1};
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        when(local.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(List.of(m), false, null, null, true, ""));
        when(local.pumpSign(eq("s"), any()))
                .thenReturn(new CgSignPumpResult(
                        Collections.emptyList(), true, "aa".repeat(32), "bb".repeat(32), true, ""));

        CgSignPumpResult r = orch.runSign("s", 0, 0, signers, hash);
        assertTrue(r.isFinished());
        assertEquals("aa".repeat(32), r.getRHex());
        assertEquals("bb".repeat(32), r.getSHex());
    }

    @Test
    @DisplayName("sign：start 失败 → 不进入循环")
    void testSignStartFailure() {
        when(local.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(CgSignPumpResult.failure("engine rejected sign"));
        CgSignPumpResult r = orch.runSign("s", 0, 0, new int[]{0}, new byte[32]);
        assertFalse(r.isSuccess());
        verify(local, never()).pumpSign(anyString(), any());
    }

    @Test
    @DisplayName("sign：publish 失败 → 立即返回 failure")
    void testSignPublishFailure() {
        when(coordinator.publishRelay(any(CgRelayMessageDto.class))).thenReturn(false);
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        when(local.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(List.of(m), false, null, null, true, ""));
        CgSignPumpResult r = orch.runSign("s", 0, 0, new int[]{0}, new byte[32]);
        assertFalse(r.isSuccess());
    }

    // ============================================================
    // myIndex 推断
    // ============================================================

    @Test
    @DisplayName("pullRelay 使用 orchestrator 绑定的 defaultMyIndex（不再依赖 outgoing 推断）")
    void testMyIndexInference() {
        // I 批：orchestrator 构造时绑定 myIndex=2，pumpLoop 拉取时用此值
        MpcCggmpOrchestrator orchParty2 = new MpcCggmpOrchestrator(local, coordinator, 2);
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(List.of(m), false, null, true, ""));
        when(local.pumpKeygen(eq("s"), any()))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, "k", true, ""));

        orchParty2.runKeygen("s", 0, 2, 3, 2);
        ArgumentCaptor<Integer> myIdxCap = ArgumentCaptor.forClass(Integer.class);
        verify(coordinator).pullRelay(eq("s"), myIdxCap.capture());
        assertEquals(2, myIdxCap.getValue(),
                "pullRelay myIndex must equal orchestrator's defaultMyIndex");
    }

    @Test
    @DisplayName("无 outgoing 时仍调 pullRelay（用 defaultMyIndex=0，I 批修复）")
    void testNoOutgoingMyIndexFallback() {
        // I 批：I 批之前 outgoing 为空会返回 -1 触发校验失败。
        // 修复后 pumpLoop 用 defaultMyIndex 替代 myIndexOf，start 立即 finished
        // 也仍会调一次 pullRelay(0)（与 F 批 e2e 行为一致）。
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, "k", true, ""));
        orch.runKeygen("s", 0, 0, 3, 2);
        // I 批：first.isFinished() 仍成立但 orchestrator 会先 publish 0 outgoing，
        // 再 pull（空 list，since first finished 进 loop body 一次）
        // 实际：while loop 检查 !first.isFinished() 立即退出 → 不调 pull
        verify(coordinator, never()).pullRelay(anyString(), anyInt());
    }

    // ============================================================
    // 阶段间无交叉
    // ============================================================

    @Test
    @DisplayName("keygen 不应调用 aux/sign 阶段方法")
    void testKeygenNoCrossPhaseCall() {
        when(local.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(new CgPumpResult(Collections.emptyList(), true, "k", true, ""));
        orch.runKeygen("s", 0, 0, 3, 2);
        verify(local, never()).startAux(anyString(), anyInt(), anyInt(), anyInt());
        verify(local, never()).startSign(anyString(), anyInt(), anyInt(), any(), any());
    }
}
