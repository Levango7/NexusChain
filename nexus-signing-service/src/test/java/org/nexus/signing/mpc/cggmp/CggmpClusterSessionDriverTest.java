package org.nexus.signing.mpc.cggmp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.MpcProtocolException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 * {@link CggmpClusterSessionDriver} 单元测试（P0-1 覆盖率补线）。
 *
 * <p>生产路径的端到端行为由 {@code CggmpMpcE2EClusterTest#cggmpE2EProductionPath}
 * 覆盖（需真实引擎集群，CI 中由独立 job 运行）；本类用 mock client 覆盖
 * 编排逻辑本身：参数校验、keygen/aux/sign 三阶段循环、传输抖动重试、
 * 失败短路与默认协调器——使覆盖率门禁在常规 {@code check} 中可复现。</p>
 */
class CggmpClusterSessionDriverTest {

    private static final String SID = "cw-unit-test";
    private static final String R_HEX = "ab".repeat(32);
    private static final String S_HEX = "cd".repeat(32);
    private static final byte[] HASH = new byte[32];

    private MpcCggmpClient c0;
    private MpcCggmpClient c1;
    private MpcCggmpClient c2;
    private CggmpClusterSessionDriver driver;

    @BeforeEach
    void setUp() {
        c0 = mock(MpcCggmpClient.class);
        c1 = mock(MpcCggmpClient.class);
        c2 = mock(MpcCggmpClient.class);
        driver = new CggmpClusterSessionDriver(List.of(c0, c1, c2));
    }

    private static CgPumpResult finished(String aggPk) {
        return new CgPumpResult(Collections.emptyList(), true, aggPk, true, "");
    }

    private static CgPumpResult unfinished(List<CgRelayMessageDto> outgoing) {
        return new CgPumpResult(outgoing, false, null, true, "");
    }

    private static CgSignPumpResult signFinished(String r, String s) {
        return new CgSignPumpResult(Collections.emptyList(), true, r, s, true, "");
    }

    private void stubKeygenImmediate(String aggPk) {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished(aggPk));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished(aggPk));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished(aggPk));
    }

    private void stubAuxImmediate() {
        when(c0.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c1.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c2.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
    }

    private void stubAssembleOk() {
        when(c0.assembleShare(anyString())).thenReturn(true);
        when(c1.assembleShare(anyString())).thenReturn(true);
        when(c2.assembleShare(anyString())).thenReturn(true);
    }

    // ============================================================
    // 构造与参数校验
    // ============================================================

    @Test
    @DisplayName("构造：少于 2 方 fail-closed 抛 MpcProtocolException")
    void rejectsLessThanTwoClients() {
        List<MpcCggmpClient> one = List.of(c0);
        assertThrows(MpcProtocolException.class, () -> new CggmpClusterSessionDriver(one));
    }

    @Test
    @DisplayName("构造：null 元素拒绝")
    void rejectsNullElement() {
        List<MpcCggmpClient> withNull = Arrays.asList(c0, null);
        assertThrows(NullPointerException.class, () -> new CggmpClusterSessionDriver(withNull));
    }

    @Test
    @DisplayName("parties() = client 数（n）")
    void partiesEqualsClientCount() {
        assertEquals(3, driver.parties());
    }

    @Test
    @DisplayName("setup：totalParties 与驱动方数不一致 → fail-closed")
    void setupRejectsPartyMismatch() {
        assertThrows(MpcProtocolException.class,
                () -> driver.runKeygenAuxAssemble(SID, 0, 2, 2));
        assertThrows(MpcProtocolException.class,
                () -> driver.runKeygenAuxAssemble(SID, 0, 4, 2));
    }

    @Test
    @DisplayName("setup：threshold 越界 → fail-closed")
    void setupRejectsThresholdOutOfRange() {
        assertThrows(MpcProtocolException.class,
                () -> driver.runKeygenAuxAssemble(SID, 0, 3, 0));
        assertThrows(MpcProtocolException.class,
                () -> driver.runKeygenAuxAssemble(SID, 0, 3, 4));
    }

    // ============================================================
    // keygen → aux → assemble（setup 全流水线）
    // ============================================================

    @Test
    @DisplayName("setup：三阶段直通（无 relay 消息）→ success + aggPk，assemble 每方调用")
    void setupHappyPath() {
        stubKeygenImmediate("AGG");
        stubAuxImmediate();
        stubAssembleOk();

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertTrue(outcome.isSuccess(), outcome.getError());
        assertEquals("AGG", outcome.getAggregatePublicKeyHex());
        verify(c0).assembleShare(SID);
        verify(c1).assembleShare(SID);
        verify(c2).assembleShare(SID);
        verify(c0, never()).pumpKeygen(anyString(), any());
    }

    @Test
    @DisplayName("setup：keygen start 失败 → 短路返回，不进入 aux")
    void setupKeygenStartFailureShortCircuits() {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CgPumpResult.failure("engine boom"));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("party 1"), outcome.getError());
        verify(c0, never()).startAux(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("setup：keygen 传输抖动（HTTP status code）重试一次成功")
    void setupKeygenTransportGlitchRetriedOnce() {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CgPumpResult.failure("gRPC CgStartKeygen failed: HTTP status code 200"),
                        finished("AGG"));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        stubAuxImmediate();
        stubAssembleOk();

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c0, times(2)).startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt());
        verify(c1, times(1)).startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("setup：keygen 聚合公钥跨方不一致 → failure")
    void setupKeygenAggPkMismatch() {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("OTHER"));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("mismatch"), outcome.getError());
    }

    @Test
    @DisplayName("setup：keygen 聚合公钥为空 → failure")
    void setupKeygenEmptyAggPk() {
        stubKeygenImmediate("");
        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("empty aggregate public key"), outcome.getError());
    }

    @Test
    @DisplayName("setup：keygen 泵循环 publish→pull→pump 直至 finished")
    void setupKeygenPumpLoop() {
        CgRelayMessageDto m = new CgRelayMessageDto(SID, 0, 1, "{}", false);
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(List.of(m)));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c0.publishRelay(m)).thenReturn(true);
        when(c0.pumpKeygen(eq(SID), any())).thenReturn(finished("AGG"));
        stubAuxImmediate();
        stubAssembleOk();

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c0).publishRelay(eq(m));
        verify(c0, times(1)).pumpKeygen(eq(SID), any());
        // 已完成方不参与 pull/pump
        verify(c1, never()).pumpKeygen(anyString(), any());
    }

    @Test
    @DisplayName("setup：relay publish 失败 → failure 短路")
    void setupKeygenPublishFailure() {
        CgRelayMessageDto m = new CgRelayMessageDto(SID, 0, 1, "{}", false);
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(List.of(m)));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c0.publishRelay(m)).thenReturn(false);

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("publish failed sender=0"), outcome.getError());
    }

    @Test
    @DisplayName("setup：keygen pump 失败 → failure 短路")
    void setupKeygenPumpFailure() {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c0.pumpKeygen(eq(SID), any())).thenReturn(CgPumpResult.failure("pump boom"));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("pump party 0 failed"), outcome.getError());
    }

    @Test
    @DisplayName("setup：keygen 停滞 200 轮 → failure 含诊断信息")
    void setupKeygenStuckAfterMaxRounds() {
        when(c0.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c1.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c2.startKeygen(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("AGG"));
        when(c0.pumpKeygen(eq(SID), any())).thenReturn(unfinished(Collections.emptyList()));
        when(c1.pumpKeygen(eq(SID), any())).thenReturn(unfinished(Collections.emptyList()));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("stuck after 200 rounds"), outcome.getError());
        assertTrue(outcome.getError().contains("finished=false"), outcome.getError());
    }

    @Test
    @DisplayName("setup：aux 并发 start 失败 → failure")
    void setupAuxStartFailure() {
        stubKeygenImmediate("AGG");
        when(c0.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c1.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CgPumpResult.failure("aux boom"));
        when(c2.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("aux start party 1 failed"), outcome.getError());
    }

    @Test
    @DisplayName("setup：aux 传输抖动重试一次成功")
    void setupAuxTransportGlitchRetriedOnce() {
        stubKeygenImmediate("AGG");
        when(c0.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(CgPumpResult.failure("gRPC CgStartAux failed: HTTP status code 200"),
                        finished("aux-ok"));
        when(c1.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c2.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        stubAssembleOk();

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c0, times(2)).startAux(anyString(), anyInt(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("setup：aux pump 失败 → failure")
    void setupAuxPumpFailure() {
        stubKeygenImmediate("AGG");
        when(c0.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c1.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c2.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(finished("aux-ok"));
        when(c0.pumpAux(eq(SID), any())).thenReturn(CgPumpResult.failure("aux pump boom"));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("aux: pump party 0 failed"), outcome.getError());
    }

    @Test
    @DisplayName("setup：aux 后某方未 finished → failure")
    void setupAuxNotFinished() {
        // aux 阶段 startAux 返回未完成且 pump 恒未完成 → 走停滞分支（覆盖 aux 相位循环）
        stubKeygenImmediate("AGG");
        when(c0.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c1.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c2.startAux(anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(unfinished(Collections.emptyList()));
        when(c0.pumpAux(eq(SID), any())).thenReturn(unfinished(Collections.emptyList()));
        when(c1.pumpAux(eq(SID), any())).thenReturn(unfinished(Collections.emptyList()));
        when(c2.pumpAux(eq(SID), any())).thenReturn(unfinished(Collections.emptyList()));

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("aux stuck after 200 rounds"), outcome.getError());
    }

    @Test
    @DisplayName("setup：assembleShare 失败 → failure 指名方")
    void setupAssembleFailure() {
        stubKeygenImmediate("AGG");
        stubAuxImmediate();
        when(c0.assembleShare(anyString())).thenReturn(true);
        when(c1.assembleShare(anyString())).thenReturn(true);
        when(c2.assembleShare(anyString())).thenReturn(false);

        CggmpClusterSessionDriver.SetupOutcome outcome =
                driver.runKeygenAuxAssemble(SID, 0, 3, 2);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("assembleShare failed for party 2"),
                outcome.getError());
    }

    // ============================================================
    // sign
    // ============================================================

    @Test
    @DisplayName("sign：signers 为空 / 越界 / 重复 → fail-closed")
    void signRejectsIllegalSigners() {
        assertThrows(MpcProtocolException.class,
                () -> driver.runSign(SID, 0, new int[0], HASH));
        assertThrows(MpcProtocolException.class,
                () -> driver.runSign(SID, 0, new int[] {3}, HASH));
        assertThrows(MpcProtocolException.class,
                () -> driver.runSign(SID, 0, new int[] {0, 0}, HASH));
    }

    @Test
    @DisplayName("sign：2-of-3 直通（startSign 即 finished）→ r/s 一致返回")
    void signHappyPath() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertTrue(outcome.isSuccess(), outcome.getError());
        assertEquals(R_HEX, outcome.getRHex());
        assertEquals(S_HEX, outcome.getSHex());
        // indexInSigners 与 keygen 原始索引的映射：b=0→party0，b=1→party1
        verify(c0).startSign(eq(SID), eq(0), eq(0), eq(new int[] {0, 1}), eq(HASH));
        verify(c1).startSign(eq(SID), eq(0), eq(1), eq(new int[] {0, 1}), eq(HASH));
        verify(c0, never()).pumpSign(anyString(), any());
    }

    @Test
    @DisplayName("sign：signer 索引非连续（0,2）→ 映射到对应 party client")
    void signNonContiguousSigners() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c2.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 2}, HASH);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c2).startSign(eq(SID), eq(0), eq(1), eq(new int[] {0, 2}), eq(HASH));
        verify(c1, never()).startSign(anyString(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("sign：r/s 长度非 64 hex → failure")
    void signMalformedSignature() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished("ab".repeat(31), S_HEX));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished("ab".repeat(31), S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("malformed signature"), outcome.getError());
    }

    @Test
    @DisplayName("sign：跨签名方 r/s 不一致 → failure")
    void signMismatchAcrossSigners() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished("ef".repeat(32), S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("signature mismatch"), outcome.getError());
    }

    @Test
    @DisplayName("sign：startSign 失败 → failure 指名 signer")
    void signStartFailure() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(CgSignPumpResult.failure("sign start boom"));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("sign start signer 1"), outcome.getError());
    }

    @Test
    @DisplayName("sign：startSign 传输抖动重试一次成功")
    void signStartTransportGlitchRetriedOnce() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(CgSignPumpResult.failure("gRPC CgStartSign failed: HTTP status code"),
                        signFinished(R_HEX, S_HEX));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c0, times(2)).startSign(anyString(), anyInt(), anyInt(), any(), any());
    }

    @Test
    @DisplayName("sign：泵循环 publish→pull→pump 直至 finished")
    void signPumpLoop() {
        CgRelayMessageDto m = new CgRelayMessageDto(SID, 0, 1, "{}", false);
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(List.of(m), false, null, null, true, ""));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));
        when(c0.publishRelay(m)).thenReturn(true);
        when(c0.pumpSign(eq(SID), any())).thenReturn(signFinished(R_HEX, S_HEX));
        when(c1.pumpSign(eq(SID), any())).thenReturn(signFinished(R_HEX, S_HEX));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertTrue(outcome.isSuccess(), outcome.getError());
        verify(c0).publishRelay(eq(m));
        verify(c0, times(1)).pumpSign(eq(SID), any());
        verify(c1, times(1)).pumpSign(eq(SID), any());
    }

    @Test
    @DisplayName("sign：publish 失败 → failure 短路")
    void signPublishFailure() {
        CgRelayMessageDto m = new CgRelayMessageDto(SID, 0, 1, "{}", false);
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(List.of(m), false, null, null, true, ""));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c0.publishRelay(m)).thenReturn(false);

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("sign: publish failed"), outcome.getError());
    }

    @Test
    @DisplayName("sign：pump 失败 → failure 短路")
    void signPumpFailure() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(signFinished(R_HEX, S_HEX));
        when(c0.pumpSign(eq(SID), any())).thenReturn(CgSignPumpResult.failure("pump boom"));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("pump signer 0 (party 0) failed"),
                outcome.getError());
    }

    @Test
    @DisplayName("sign：停滞 200 轮 → failure")
    void signStuckAfterMaxRounds() {
        when(c0.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));
        when(c1.startSign(anyString(), anyInt(), anyInt(), any(), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));
        when(c0.pumpSign(eq(SID), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));
        when(c1.pumpSign(eq(SID), any()))
                .thenReturn(new CgSignPumpResult(Collections.emptyList(), false, null, null, true, ""));

        CggmpClusterSessionDriver.SignOutcome outcome =
                driver.runSign(SID, 0, new int[] {0, 1}, HASH);

        assertFalse(outcome.isSuccess());
        assertTrue(outcome.getError().contains("sign stuck after 200 rounds"), outcome.getError());
    }

    // ============================================================
    // verify / status / 默认协调器
    // ============================================================

    @Test
    @DisplayName("verify/status：默认协调器 = endpoint 0（partyClients[0]）")
    void defaultCoordinatorIsFirstClient() {
        CgVerifyResult vr = new CgVerifyResult(true, true, "");
        when(c0.verifySignature(anyString(), any(), any(), any())).thenReturn(vr);
        CgStatus st = new CgStatus(false, false, false, false, false, true, true, "");
        when(c0.status(SID)).thenReturn(st);

        assertSame(vr, driver.verify(SID, HASH, HASH, HASH));
        assertSame(st, driver.status(SID));
        verify(c1, never()).verifySignature(anyString(), any(), any(), any());
        verify(c2, never()).status(anyString());
    }

    @Test
    @DisplayName("verify/status：显式协调器 client 生效")
    void explicitCoordinatorUsed() {
        MpcCggmpClient coord = mock(MpcCggmpClient.class);
        CggmpClusterSessionDriver withCoord =
                new CggmpClusterSessionDriver(List.of(c0, c1), coord);
        CgStatus st = new CgStatus(false, false, false, false, false, true, true, "");
        when(coord.status(SID)).thenReturn(st);

        assertSame(st, withCoord.status(SID));
        verify(c0, never()).status(anyString());
    }
}
