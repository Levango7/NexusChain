package org.nexus.signing.mpc.cggmp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * {@link CggmpMpcCryptoEngine} 单元测试（P0-1 更新：集群驱动接线）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>SPI 路径选择（cggmpEnabled 开关）与驱动缺失时的失败语义</li>
 *   <li>dkg 委托驱动跑集群仪式 → 聚合公钥；份额不出引擎（keyShare=null）</li>
 *   <li>sign 委托驱动（签名方集合来自配置）→ r/s 拼接到 partialSignature</li>
 *   <li>healthCheck 真实探测（status RPC）</li>
 *   <li>walletSessionId 派生（确定性 / 字符集合规）</li>
 *   <li>aggregate 拆 r/s（noop 恢复）</li>
 * </ul>
 */
public class CggmpMpcCryptoEngineTest {

    private static final String AGG_PK = "02" + "ab".repeat(32);
    private static final String R_HEX = "aa".repeat(32);
    private static final String S_HEX = "bb".repeat(32);

    private CggmpClusterSessionDriver driver;
    private CggmpMpcCryptoEngine engine;

    @BeforeEach
    void setUp() {
        driver = mock(CggmpClusterSessionDriver.class);
        engine = new CggmpMpcCryptoEngine(providerOf(driver));
        ReflectionTestUtils.setField(engine, "cggmpEnabled", true);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<CggmpClusterSessionDriver> providerOf(
            CggmpClusterSessionDriver driverOrNull) {
        ObjectProvider<CggmpClusterSessionDriver> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(driverOrNull);
        return provider;
    }

    @Test
    @DisplayName("cggmpEnabled 开关正确暴露")
    void testCggmpEnabled() {
        assertTrue(engine.isCggmpEnabled());
        ReflectionTestUtils.setField(engine, "cggmpEnabled", false);
        assertFalse(engine.isCggmpEnabled());
    }

    @Test
    @DisplayName("walletSessionId：确定性派生 + 字符集合规 + 不同钱包不同 ID")
    void testWalletSessionIdDeterministic() {
        String id1 = CggmpMpcCryptoEngine.walletSessionId("wallet-abc");
        String id2 = CggmpMpcCryptoEngine.walletSessionId("wallet-abc");
        assertEquals(id1, id2);
        assertTrue(id1.startsWith("cw-"));
        assertTrue(id1.matches("[0-9a-zA-Z-]{1,128}"),
                "engine session_id charset violated: " + id1);
        assertNotEquals(id1, CggmpMpcCryptoEngine.walletSessionId("wallet-xyz"));
    }

    // ============================================================
    // dkg
    // ============================================================

    @Test
    @DisplayName("dkg：委托驱动跑集群仪式 → 聚合公钥；份额不出引擎（keyShare=null）")
    void testDkgDelegatesToDriver() {
        String sid = CggmpMpcCryptoEngine.walletSessionId("wallet-1");
        when(driver.runKeygenAuxAssemble(sid, 0, 3, 2))
                .thenReturn(CggmpClusterSessionDriver.SetupOutcome.success(AGG_PK));

        DkgResponse resp = engine.dkg(new DkgRequest(sid, 2, 3, 0, "secp256k1", List.of()));

        assertTrue(resp.isSuccess());
        assertEquals(AGG_PK, resp.getPublicKey());
        assertNull(resp.getKeyShare(), "CGGMP21 份额必须驻留引擎进程，不得经 SPI 返回");
        assertNull(resp.getProof());
        verify(driver, times(1)).runKeygenAuxAssemble(sid, 0, 3, 2);
    }

    @Test
    @DisplayName("dkg：驱动失败 → success=false 不抛")
    void testDkgDriverFailure() {
        when(driver.runKeygenAuxAssemble(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CggmpClusterSessionDriver.SetupOutcome.failure("aux timeout"));

        DkgResponse resp = engine.dkg(
                new DkgRequest("cw-x", 2, 3, 0, "secp256k1", List.of()));

        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("aux timeout"));
    }

    @Test
    @DisplayName("dkg：驱动缺失（非分布式集群）→ success=false 不抛")
    void testDkgWithoutDriver() {
        CggmpMpcCryptoEngine noDriverEngine = new CggmpMpcCryptoEngine(providerOf(null));
        DkgResponse resp = noDriverEngine.dkg(
                new DkgRequest("cw-x", 2, 3, 0, "secp256k1", List.of()));
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("cluster driver unavailable"));
    }

    // ============================================================
    // sign
    // ============================================================

    @Test
    @DisplayName("sign：委托驱动（默认全体签名方）→ r/s 拼接 = partialSignature")
    void testSignDelegatesAndConcatRS() {
        when(driver.parties()).thenReturn(3);
        when(driver.runSign(any(), anyInt(), any(), any()))
                .thenReturn(CggmpClusterSessionDriver.SignOutcome.success(R_HEX, S_HEX));

        SignRequest req = new SignRequest("cw-s", "pk", "share",
                "00".repeat(32), 0, List.of());
        SignResponse resp = engine.sign(req);

        assertTrue(resp.isSuccess());
        // r||s 拼接 = 64 字节 hex = 128 字符
        assertEquals(128, resp.getPartialSignature().length());
        assertEquals(R_HEX + S_HEX, resp.getPartialSignature());

        ArgumentCaptor<int[]> signersCap = ArgumentCaptor.forClass(int[].class);
        ArgumentCaptor<byte[]> hashCap = ArgumentCaptor.forClass(byte[].class);
        verify(driver, times(1)).runSign(
                eq("cw-s"), anyInt(), signersCap.capture(), hashCap.capture());
        assertArrayEquals(new int[]{0, 1, 2}, signersCap.getValue(),
                "空配置 = 全体参与方");
        assertEquals(32, hashCap.getValue().length);
        for (int i = 0; i < 32; i++) {
            assertEquals(0, hashCap.getValue()[i]);
        }
    }

    @Test
    @DisplayName("sign：signers 配置生效（2-of-3 → [0,1]）")
    void testSignUsesConfiguredSigners() {
        ReflectionTestUtils.setField(engine, "signersConfig", "0,1");
        when(driver.parties()).thenReturn(3);
        when(driver.runSign(any(), anyInt(), any(), any()))
                .thenReturn(CggmpClusterSessionDriver.SignOutcome.success(R_HEX, S_HEX));

        SignResponse resp = engine.sign(new SignRequest("cw-s", "pk", "share",
                "00".repeat(32), 0, List.of()));

        assertTrue(resp.isSuccess());
        ArgumentCaptor<int[]> signersCap = ArgumentCaptor.forClass(int[].class);
        verify(driver, times(1)).runSign(any(), anyInt(), signersCap.capture(), any());
        assertArrayEquals(new int[]{0, 1}, signersCap.getValue());
    }

    @Test
    @DisplayName("sign：eid counter 每次执行递增（ExecutionId 唯一性契约）")
    void testSignCounterMonotonic() {
        when(driver.parties()).thenReturn(3);
        when(driver.runSign(any(), anyInt(), any(), any()))
                .thenReturn(CggmpClusterSessionDriver.SignOutcome.success(R_HEX, S_HEX));

        engine.sign(new SignRequest("cw-s", "pk", "share", "00".repeat(32), 0, List.of()));
        engine.sign(new SignRequest("cw-s", "pk", "share", "11".repeat(32), 0, List.of()));

        ArgumentCaptor<Integer> counterCap = ArgumentCaptor.forClass(Integer.class);
        verify(driver, times(2)).runSign(any(), counterCap.capture(), any(), any());
        var counters = counterCap.getAllValues();
        assertNotEquals(counters.get(0), counters.get(1),
                "同 session 的两次签名不得复用 eid 序号（cggmp21 ExecutionId 唯一性）");
    }

    @Test
    @DisplayName("sign：signers 配置越界 → MpcProtocolException（fail-closed）")
    void testSignInvalidSignersConfig() {
        ReflectionTestUtils.setField(engine, "signersConfig", "0,5");
        when(driver.parties()).thenReturn(3);

        assertThrows(MpcProtocolException.class, () -> engine.sign(
                new SignRequest("cw-s", "pk", "share", "00".repeat(32), 0, List.of())));
        verify(driver, never()).runSign(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("sign：驱动失败 → success=false 不抛")
    void testSignDriverFailure() {
        when(driver.parties()).thenReturn(3);
        when(driver.runSign(any(), anyInt(), any(), any()))
                .thenReturn(CggmpClusterSessionDriver.SignOutcome.failure("cggmp21 sign aborted"));

        SignResponse resp = engine.sign(new SignRequest("cw-s", "pk", "share",
                "00".repeat(32), 0, List.of()));

        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("aborted"));
    }

    @Test
    @DisplayName("sign：messageHash 长度错误 → success=false，不触达驱动")
    void testSignInvalidHashLength() {
        SignRequest req = new SignRequest("cw-s", "pk", "share",
                "00".repeat(16), 0, List.of()); // 16 字节
        SignResponse resp = engine.sign(req);

        assertFalse(resp.isSuccess());
        verify(driver, never()).runSign(any(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("sign：驱动缺失 → success=false 不抛")
    void testSignWithoutDriver() {
        CggmpMpcCryptoEngine noDriverEngine = new CggmpMpcCryptoEngine(providerOf(null));
        SignResponse resp = noDriverEngine.sign(new SignRequest(
                "cw-s", "pk", "share", "00".repeat(32), 0, List.of()));
        assertFalse(resp.isSuccess());
        assertTrue(resp.getError().contains("cluster driver unavailable"));
    }

    // ============================================================
    // healthCheck
    // ============================================================

    @Test
    @DisplayName("healthCheck：驱动存活（status success）→ true")
    void testHealthCheckProbesDriver() {
        when(driver.status(anyString())).thenReturn(
                new CgStatus(false, false, false, false, false, false, true, ""));
        assertTrue(engine.healthCheck());
    }

    @Test
    @DisplayName("healthCheck：status 失败 → false")
    void testHealthCheckDriverFailure() {
        when(driver.status(anyString())).thenReturn(
                new CgStatus(false, false, false, false, false, false, false, "unreachable"));
        assertFalse(engine.healthCheck());
    }

    @Test
    @DisplayName("healthCheck：驱动缺失 → false（编排层回退 GG20）")
    void testHealthCheckWithoutDriver() {
        CggmpMpcCryptoEngine noDriverEngine = new CggmpMpcCryptoEngine(providerOf(null));
        assertFalse(noDriverEngine.healthCheck());
    }

    // ============================================================
    // aggregate（noop 恢复）
    // ============================================================

    @Test
    @DisplayName("aggregate：从 partialSignatures[0] 拆出 r/s")
    void testAggregate() {
        String concat = R_HEX + S_HEX;
        AggregateRequest req = new AggregateRequest("s", "pk", "00".repeat(32),
                java.util.Collections.singletonList(concat));
        var resp = engine.aggregate(req);
        assertTrue(resp.isSuccess());
        assertEquals(concat, resp.getSignature());
        assertEquals(R_HEX, resp.getR());
        assertEquals(S_HEX, resp.getS());
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
