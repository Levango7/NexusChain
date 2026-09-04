package org.nexus.signing.mpc.cggmp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CggmpMpcCryptoEngine} 单元测试（H 批）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>SPI 路径选择（cggmpEnabled 开关）</li>
 *   <li>sign 委托 orchestrator → r/s 拼接到 partialSignature</li>
 *   <li>aggregate 拆 r/s（CGGMP21 路径下是 noop 恢复）</li>
 *   <li>dkg 暂未实现 → success=false 不抛异常（H 批范围外）</li>
 *   <li>healthCheck 默认 true</li>
 *   <li>messageHash 长度校验</li>
 * </ul>
 */
public class CggmpMpcCryptoEngineTest {

    private MpcCggmpOrchestrator orch;
    private CggmpMpcCryptoEngine engine;

    @BeforeEach
    void setUp() {
        orch = mock(MpcCggmpOrchestrator.class);
        engine = new CggmpMpcCryptoEngine(orch);
        ReflectionTestUtils.setField(engine, "cggmpEnabled", true);
    }

    @Test
    @DisplayName("cggmpEnabled 开关正确暴露")
    void testCggmpEnabled() {
        assertTrue(engine.isCggmpEnabled());
        ReflectionTestUtils.setField(engine, "cggmpEnabled", false);
        assertFalse(engine.isCggmpEnabled());
    }

    @Test
    @DisplayName("healthCheck 默认 true")
    void testHealthCheck() {
        assertTrue(engine.healthCheck());
    }

    @Test
    @DisplayName("dkg 暂未实现 → success=false 不抛异常")
    void testDkgNotYetWired() {
        var resp = engine.dkg(new DkgRequest(
                "session-1", 2, 3, 0, "secp256k1", List.of()));
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("not yet implemented"));
    }

    @Test
    @DisplayName("sign：r/s 委托 orchestrator 产出，partialSignature 字段 = r||s 拼接")
    void testSignDelegatesAndConcatRS() {
        when(orch.runSign(any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(
                        java.util.Collections.emptyList(), true,
                        "aa".repeat(32), "bb".repeat(32), true, ""));

        SignRequest req = new SignRequest("s", "pk", "share",
                "00".repeat(32), 0, List.of());
        SignResponse resp = engine.sign(req);

        assertTrue(resp.isSuccess());
        // r||s 拼接 = 64 字节 hex = 128 字符
        assertEquals(128, resp.getPartialSignature().length());
        assertEquals("aa".repeat(32) + "bb".repeat(32), resp.getPartialSignature());

        // 验证 orchestrator 收到的 messageHash 字节
        ArgumentCaptor<byte[]> hashCap = ArgumentCaptor.forClass(byte[].class);
        verify(orch, times(1)).runSign(
                any(), anyInt(), anyInt(), any(), hashCap.capture());
        assertEquals(32, hashCap.getValue().length);
        for (int i = 0; i < 32; i++) {
            assertEquals(0, hashCap.getValue()[i]);
        }
    }

    @Test
    @DisplayName("sign：orchestrator 失败 → success=false 不抛")
    void testSignOrchestratorFailure() {
        when(orch.runSign(any(), anyInt(), anyInt(), any(), any()))
                .thenReturn(CgSignPumpResult.failure("cggmp21 sign aborted"));
        SignRequest req = new SignRequest("s", "pk", "share",
                "00".repeat(32), 0, List.of());
        SignResponse resp = engine.sign(req);
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("aborted"));
    }

    @Test
    @DisplayName("sign：messageHash 长度错误 → success=false")
    void testSignInvalidHashLength() {
        SignRequest req = new SignRequest("s", "pk", "share",
                "00".repeat(16), 0, List.of()); // 16 字节
        SignResponse resp = engine.sign(req);
        assertFalse(resp.isSuccess());
        verify(orch, never()).runSign(any(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("aggregate：从 partialSignatures[0] 拆出 r/s")
    void testAggregate() {
        String concat = "aa".repeat(32) + "bb".repeat(32);
        AggregateRequest req = new AggregateRequest("s", "pk", "00".repeat(32),
                java.util.Collections.singletonList(concat));
        var resp = engine.aggregate(req);
        assertTrue(resp.isSuccess());
        assertEquals(concat, resp.getSignature());
        assertEquals("aa".repeat(32), resp.getR());
        assertEquals("bb".repeat(32), resp.getS());
    }

    @Test
    @DisplayName("aggregate：空 partials → success=false")
    void testAggregateEmpty() {
        AggregateRequest req = new AggregateRequest("s", "pk", "00".repeat(32),
                java.util.Collections.emptyList());
        var resp = engine.aggregate(req);
        assertFalse(resp.isSuccess());
    }

    @Test
    @DisplayName("aggregate：partial 长度非 128 hex → success=false")
    void testAggregateBadLength() {
        AggregateRequest req = new AggregateRequest("s", "pk", "00".repeat(32),
                java.util.Collections.singletonList("aabb"));
        var resp = engine.aggregate(req);
        assertFalse(resp.isSuccess());
    }
}
