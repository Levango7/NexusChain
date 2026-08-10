package org.nexus.signing.mpc;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.signing.controller.NodeController;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link ColdWalletMultiSigService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
public class ColdWalletMultiSigServiceTest {

    @Mock
    private MpcSigner signer;
    @Mock
    private MpcSignatureAggregator aggregator;
    @Mock
    private NodeController nodeController;

    private MpcApprovalPolicy approvalPolicy;
    private ColdWalletMultiSigService service;

    @BeforeEach
    public void setUp() {
        approvalPolicy = new MpcApprovalPolicy();
        approvalPolicy.addToWhitelist("whitelisted-addr");
        service = new ColdWalletMultiSigService(
                signer, aggregator, approvalPolicy, nodeController);
    }

    private MpcWallet newWallet(String walletId, int threshold) {
        MpcWallet wallet = new MpcWallet();
        wallet.setWalletId(walletId);
        wallet.setThreshold(threshold);
        wallet.setPublicKey("joint-pk-" + walletId);
        wallet.setParticipants(List.of("p1", "p2", "p3", "p4", "p5"));
        return wallet;
    }

    private List<MpcParticipant> threeOnlineParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"),
                new MpcParticipant("p3", "h3", "pk3"));
    }

    // ==================== initMultiSigTransfer ====================

    @Test
    public void testInitMultiSigTransferSuccess() {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from-addr", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req-1",
                threeOnlineParticipants());
        assertNotNull(sessionId);
        assertEquals(ColdWalletMultiSigService.TransferStatus.PENDING,
                service.getSessionStatus(sessionId));
    }

    @Test
    public void testInitMultiSigTransferUnknownWalletThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.initMultiSigTransfer(
                "unknown", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());
        });
    }

    @Test
    public void testInitMultiSigTransferQuorumNotReachedThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.registerWallet(newWallet("w1", 3));
        // 仅 2 在线，不够 3
        List<MpcParticipant> twoOnline = List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"));
        service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req", twoOnline);
        });
    }

    @Test
    public void testInitMultiSigTransferAddressNotWhitelistedThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.registerWallet(newWallet("w1", 3));
        service.initMultiSigTransfer(
                "w1", "from", "non-whitelisted",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());
        });
    }

    @Test
    public void testInitMultiSigTransferNullWalletIdThrows() { assertThrows(NullPointerException.class, () -> {
        service.initMultiSigTransfer(
                null, "from", "to", BigDecimal.ONE, "NEX", "req",
                threeOnlineParticipants());
        });
    }

    @Test
    public void testInitMultiSigTransferNullAmountThrows() { assertThrows(NullPointerException.class, () -> {
        service.initMultiSigTransfer(
                "w1", "from", "to", null, "NEX", "req",
                threeOnlineParticipants());
        });
    }

    @Test
    public void testInitMultiSigTransferNullParticipantsThrows() { assertThrows(NullPointerException.class, () -> {
        service.initMultiSigTransfer(
                "w1", "from", "to", BigDecimal.ONE, "NEX", "req", null);
        });
    }

    @Test
    public void testInitMultiSigTransferWarmWalletSkipsQuorum() {
        // 暖钱包金额（< 50000）→ canSign 返回 true，不检查 quorum
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("100"), "NEX", "req",
                List.of()); // 空参与者列表
        assertNotNull(sessionId);
    }

    // ==================== participantSign ====================

    @Test
    public void testParticipantSignUnknownSessionThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.participantSign("unknown-session");
        });
    }

    @Test
    public void testParticipantSignHappyPath() {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());
        service.participantSign(sessionId);
        // signer 是 mock，runSigningRounds 是 no-op，session 仍处于 PENDING/CREATED
        // 不抛异常即视为成功
        assertNotNull(service.getSessionStatus(sessionId));
    }

    // ==================== aggregateAndBroadcast ====================

    @Test
    public void testAggregateAndBroadcastUnknownSessionThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.aggregateAndBroadcast("unknown");
        });
    }

    @Test
    public void testAggregateAndBroadcastSuccess() {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());

        when(aggregator.aggregate(any(), anyString())).thenReturn("final-sig-hex");
        JsonObject broadcastResp = new JsonObject();
        broadcastResp.addProperty("code", 2000);
        broadcastResp.addProperty("data", "0xtxhash");
        when(nodeController.sendTransaction(anyString())).thenReturn(broadcastResp);

        String txHash = service.aggregateAndBroadcast(sessionId);
        assertEquals(txHash, "0xtxhash");
        // 验证 chainTxHash 已记录
        assertEquals(service.getChainTxHash(sessionId), "0xtxhash");
        // 注：aggregateAndBroadcast 不更新 session status（production code 行为），
        // session 仍处于 CREATED → PENDING
        assertEquals(ColdWalletMultiSigService.TransferStatus.PENDING,
                service.getSessionStatus(sessionId));
    }

    @Test
    public void testAggregateAndBroadcastBroadcastFailureThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());

        when(aggregator.aggregate(any(), anyString())).thenReturn("final-sig");
        JsonObject errResp = new JsonObject();
        errResp.addProperty("code", 5000);
        errResp.addProperty("message", "node error");
        when(nodeController.sendTransaction(anyString())).thenReturn(errResp);

        service.aggregateAndBroadcast(sessionId);
        });
    }

    @Test
    public void testAggregateAndBroadcastNullResponseThrows() { assertThrows(MpcProtocolException.class, () -> {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());

        when(aggregator.aggregate(any(), anyString())).thenReturn("final-sig");
        when(nodeController.sendTransaction(anyString())).thenReturn(null);

        service.aggregateAndBroadcast(sessionId);
        });
    }

    // ==================== getSessionStatus / getChainTxHash / getFailureReason ====================

    @Test
    public void testGetSessionStatusUnknownReturnsNull() {
        assertNull(service.getSessionStatus("unknown"));
    }

    @Test
    public void testGetChainTxHashUnknownReturnsNull() {
        assertNull(service.getChainTxHash("unknown"));
    }

    @Test
    public void testGetFailureReasonUnknownReturnsNull() {
        assertNull(service.getFailureReason("unknown"));
    }

    @Test
    public void testGetChainTxHashAfterBroadcast() {
        service.registerWallet(newWallet("w1", 3));
        String sessionId = service.initMultiSigTransfer(
                "w1", "from", "whitelisted-addr",
                new BigDecimal("50000"), "NEX", "req",
                threeOnlineParticipants());
        when(aggregator.aggregate(any(), anyString())).thenReturn("sig");
        JsonObject ok = new JsonObject();
        ok.addProperty("code", 2000);
        ok.addProperty("data", "0xabc");
        when(nodeController.sendTransaction(anyString())).thenReturn(ok);

        service.aggregateAndBroadcast(sessionId);
        assertEquals(service.getChainTxHash(sessionId), "0xabc");
    }

    // ==================== registerWallet / registerKeyShares ====================

    @Test
    public void testRegisterNullWalletThrows() { assertThrows(NullPointerException.class, () -> {
        service.registerWallet(null);
        });
    }

    @Test
    public void testRegisterKeySharesNullWalletIdThrows() { assertThrows(NullPointerException.class, () -> {
        service.registerKeyShares(null, List.of());
        });
    }

    @Test
    public void testRegisterKeySharesNullSharesThrows() { assertThrows(NullPointerException.class, () -> {
        service.registerKeyShares("w1", null);
        });
    }

    @Test
    public void testRegisterKeySharesSuccess() {
        service.registerKeyShares("w1", List.of(
                new MpcKeyShare("p1", "priv", "pub", null)));
        // 不抛异常即视为成功
    }

    // ==================== 构造函数空检查 ====================

    @Test
    public void testConstructNullSignerThrows() { assertThrows(NullPointerException.class, () -> {
        new ColdWalletMultiSigService(null, aggregator, approvalPolicy, nodeController);
        });
    }

    @Test
    public void testConstructNullAggregatorThrows() { assertThrows(NullPointerException.class, () -> {
        new ColdWalletMultiSigService(signer, null, approvalPolicy, nodeController);
        });
    }

    @Test
    public void testConstructNullPolicyThrows() { assertThrows(NullPointerException.class, () -> {
        new ColdWalletMultiSigService(signer, aggregator, null, nodeController);
        });
    }

    @Test
    public void testConstructNullNodeControllerThrows() { assertThrows(NullPointerException.class, () -> {
        new ColdWalletMultiSigService(signer, aggregator, approvalPolicy, null);
        });
    }
}