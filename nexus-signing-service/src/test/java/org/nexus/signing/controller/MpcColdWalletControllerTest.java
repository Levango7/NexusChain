package org.nexus.signing.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.signing.mpc.ColdWalletMultiSigService;
import org.nexus.signing.mpc.DefaultMpcService;
import org.nexus.signing.mpc.MpcEngineParticipants;
import org.nexus.signing.mpc.MpcKeyGeneration;
import org.nexus.signing.mpc.MpcWallet;
import org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine;
import org.nexus.signing.mpc.persistence.MpcWalletRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MpcColdWalletController} 单元测试（2026-10-09 入口层配套）。
 *
 * <p>覆盖入口层的行为契约，全部用 mock 协作者（无 Spring 上下文、无引擎）：
 * 钱包初始化（DKG 调用 + 幂等 + 阈值默认/校验）、转账受理（集群不可用 → 503
 * fail-closed；正常受理）、签名/广播/状态查询的透传与 404。
 * 「真引擎 + 真签名」的端到端由 {@code CggmpMpcE2EClusterTest#coldWalletBusinessChainE2E}
 * 覆盖（集群 E2E 门禁）。</p>
 */
@ExtendWith(MockitoExtension.class)
class MpcColdWalletControllerTest {

    private static final String WALLET_ID = "wallet-ctrl-001";
    private static final String AGG_PK = "02" + "ab".repeat(32);

    @Mock
    private DefaultMpcService mpcService;
    @Mock
    private ColdWalletMultiSigService coldWalletService;
    @Mock
    private MpcWalletRepository walletRepository;
    @Mock
    private ObjectProvider<CggmpMpcCryptoEngine> cggmpEngineProvider;

    private MpcEngineParticipants engineParticipants;
    private MpcColdWalletController controller;

    @BeforeEach
    void setUp() {
        engineParticipants = new MpcEngineParticipants();
        ReflectionTestUtils.setField(engineParticipants, "endpoints",
                "engine-0:50051,engine-1:50051,engine-2:50051");
        controller = new MpcColdWalletController(mpcService, coldWalletService,
                walletRepository, engineParticipants, cggmpEngineProvider);
    }

    // ==================== 钱包初始化（DKG） ====================

    @Test
    @DisplayName("初始化钱包：走 DKG 编排、登记钱包、会话 ID 与钱包派生值一致")
    void initWalletHappyPath() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());
        CggmpMpcCryptoEngine engine = mock(CggmpMpcCryptoEngine.class);
        when(engine.isCggmpEnabled()).thenReturn(true);
        when(engine.healthCheck()).thenReturn(true);
        when(cggmpEngineProvider.getIfAvailable()).thenReturn(engine);
        when(mpcService.generateKeyShare(anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString(), anyList()))
                .thenReturn(new MpcKeyGeneration.DkgResult(AGG_PK, List.of(), LocalDateTime.now()));

        Map<String, Object> resp = controller.initWallet(
                new MpcColdWalletController.InitWalletRequest(WALLET_ID, "label-a", null));

        assertEquals(2000, resp.get("statusCode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertEquals(WALLET_ID, data.get("walletId"));
        assertEquals(AGG_PK, data.get("publicKey"));
        assertEquals(2, data.get("threshold"), "3 引擎默认阈值应为 n-1 = 2");
        assertFalse((Boolean) data.get("alreadyInitialized"));
        assertEquals(CggmpMpcCryptoEngine.walletSessionId(WALLET_ID), data.get("engineSessionId"),
                "DKG 会话必须与签名会话同源（钱包维度稳定 ID）");
        assertEquals(0, data.get("localShares"), "CGGMP21 不在 Java 侧持有份额");

        verify(mpcService, times(1)).generateKeyShare(
                eq(CggmpMpcCryptoEngine.walletSessionId(WALLET_ID)), eq(2), eq(3), eq(0),
                eq("party-0"), eq("secp256k1"), anyList());
        verify(walletRepository, times(1)).save(any(MpcWallet.class));
        verify(coldWalletService, times(1)).registerWallet(any(MpcWallet.class));
    }

    @Test
    @DisplayName("初始化钱包幂等：已存在则不重跑 DKG，直接返回既有记录")
    void initWalletIdempotent() {
        MpcWallet existing = wallet(WALLET_ID, 2);
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(existing));

        Map<String, Object> resp = controller.initWallet(
                new MpcColdWalletController.InitWalletRequest(WALLET_ID, null, null));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.get("data");
        assertTrue((Boolean) data.get("alreadyInitialized"));
        assertEquals(AGG_PK, data.get("publicKey"));
        verify(mpcService, never()).generateKeyShare(anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString(), anyList());
        verify(walletRepository, never()).save(any(MpcWallet.class));
    }

    @Test
    @DisplayName("初始化钱包：集群不可用 → ILLEGAL_STATE（→409，fail-closed 不占坑）")
    void initWalletClusterUnavailable() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());
        when(cggmpEngineProvider.getIfAvailable()).thenReturn(null);
        org.nexus.signing.mpc.MpcProtocolException ex =
                assertThrows(org.nexus.signing.mpc.MpcProtocolException.class,
                        () -> controller.initWallet(new MpcColdWalletController.InitWalletRequest(
                                WALLET_ID, null, null)));
        assertEquals(org.nexus.signing.mpc.MpcProtocolException.Reason.ILLEGAL_STATE, ex.getReason());
        verify(mpcService, never()).generateKeyShare(anyString(), anyInt(), anyInt(), anyInt(),
                anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("初始化钱包：walletId 缺失 / 阈值越界 → IllegalArgumentException（→400）")
    void initWalletValidation() {
        assertThrows(IllegalArgumentException.class, () -> controller.initWallet(
                new MpcColdWalletController.InitWalletRequest("  ", null, null)));
        assertThrows(IllegalArgumentException.class, () -> controller.initWallet(null));
        assertThrows(IllegalArgumentException.class, () -> controller.initWallet(
                new MpcColdWalletController.InitWalletRequest(WALLET_ID, null, 5)),
                "阈值 5 > 引擎数 3 应拒绝");
    }

    // ==================== 转账受理 ====================

    @Test
    @DisplayName("受理转账：集群不可用 → 503（fail-closed，不退化成 skeleton 记账）")
    void initTransferClusterUnavailable() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet(WALLET_ID, 2)));
        when(cggmpEngineProvider.getIfAvailable()).thenReturn(null); // 引擎 bean 缺失

        ResponseEntity<Map<String, Object>> resp = controller.initTransfer(transferRequest());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals(5030, resp.getBody().get("statusCode"));
        verify(coldWalletService, never()).initMultiSigTransfer(
                anyString(), anyString(), anyString(), any(), anyString(), anyString(), anyList());
    }

    @Test
    @DisplayName("受理转账：正常路径 → 200 + 会话 ID + 状态")
    void initTransferHappyPath() {
        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet(WALLET_ID, 2)));
        CggmpMpcCryptoEngine engine = mock(CggmpMpcCryptoEngine.class);
        when(engine.isCggmpEnabled()).thenReturn(true);
        when(engine.healthCheck()).thenReturn(true);
        when(cggmpEngineProvider.getIfAvailable()).thenReturn(engine);
        when(coldWalletService.initMultiSigTransfer(eq(WALLET_ID), anyString(), anyString(),
                any(BigDecimal.class), eq("USDT"), eq("req-1"), anyList())).thenReturn("tr-1");
        when(coldWalletService.getSessionStatus("tr-1"))
                .thenReturn(ColdWalletMultiSigService.TransferStatus.PENDING);

        ResponseEntity<Map<String, Object>> resp = controller.initTransfer(transferRequest());

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertEquals("tr-1", data.get("sessionId"));
        assertEquals("PENDING", data.get("status"));
    }

    @Test
    @DisplayName("受理转账：未知钱包 / 金额非正 / 字段缺失 → IllegalArgumentException（→400）")
    void initTransferValidation() {
        when(walletRepository.findById(anyString())).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> controller.initTransfer(transferRequest()),
                "未初始化钱包应拒绝");

        assertThrows(IllegalArgumentException.class, () -> controller.initTransfer(
                new MpcColdWalletController.InitTransferRequest(WALLET_ID, "0xA", "0xB",
                        new BigDecimal("0"), "USDT", "req-1")), "金额为 0 应拒绝");
        assertThrows(IllegalArgumentException.class, () -> controller.initTransfer(
                new MpcColdWalletController.InitTransferRequest(WALLET_ID, "0xA", "0xB",
                        new BigDecimal("1"), "  ", "req-1")), "asset 缺失应拒绝");
        assertThrows(IllegalArgumentException.class, () -> controller.initTransfer(null));
    }

    // ==================== 签名 / 广播 / 状态 ====================

    @Test
    @DisplayName("签名 → 广播 → 状态查询：透传编排服务结果")
    void signBroadcastStatus() {
        when(coldWalletService.getSessionStatus("tr-1"))
                .thenReturn(ColdWalletMultiSigService.TransferStatus.SIGNING);
        Map<String, Object> signResp = controller.signTransfer("tr-1");
        assertEquals(2000, signResp.get("statusCode"));
        verify(coldWalletService).participantSign("tr-1");

        when(coldWalletService.aggregateAndBroadcast("tr-1")).thenReturn("0xDEAD");
        when(coldWalletService.getSessionStatus("tr-1"))
                .thenReturn(ColdWalletMultiSigService.TransferStatus.COMPLETED);
        Map<String, Object> broadcastResp = controller.broadcastTransfer("tr-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) broadcastResp.get("data");
        assertEquals("0xDEAD", data.get("txHash"));
        assertEquals("COMPLETED", data.get("status"));

        when(coldWalletService.getChainTxHash("tr-1")).thenReturn("0xDEAD");
        ResponseEntity<Map<String, Object>> statusResp = controller.getTransfer("tr-1");
        assertEquals(HttpStatus.OK, statusResp.getStatusCode());
        assertNull(((Map<?, ?>) statusResp.getBody().get("data")).get("failureReason"));
    }

    @Test
    @DisplayName("状态查询：未知会话 → 404")
    void getTransferUnknown() {
        when(coldWalletService.getSessionStatus("nope")).thenReturn(null);
        ResponseEntity<Map<String, Object>> resp = controller.getTransfer("nope");
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    @DisplayName("钱包查询：未知 → 404；存在 → 200")
    void getWallet() {
        when(walletRepository.findById("nope")).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.getWallet("nope").getStatusCode());

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(wallet(WALLET_ID, 2)));
        ResponseEntity<Map<String, Object>> ok = controller.getWallet(WALLET_ID);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
    }

    @Test
    @DisplayName("启动回灌：仓库中的钱包全部注册进编排服务（重启后可继续受理）")
    void rehydrateWallets() {
        when(walletRepository.findAll()).thenReturn(List.of(wallet("w1", 2), wallet("w2", 2)));
        controller.rehydrateWallets();
        verify(coldWalletService, times(2)).registerWallet(any(MpcWallet.class));
    }

    // ==================== 夹具 ====================

    private static MpcWallet wallet(String walletId, int threshold) {
        MpcWallet w = new MpcWallet();
        w.setWalletId(walletId);
        w.setThreshold(threshold);
        w.setParticipants(List.of("party-0", "party-1", "party-2"));
        w.setPublicKey(AGG_PK);
        w.setCreatedAt(LocalDateTime.now());
        return w;
    }

    private static MpcColdWalletController.InitTransferRequest transferRequest() {
        return new MpcColdWalletController.InitTransferRequest(WALLET_ID, "0xFrom", "0xTo",
                new BigDecimal("1.5"), "USDT", "req-1");
    }
}
