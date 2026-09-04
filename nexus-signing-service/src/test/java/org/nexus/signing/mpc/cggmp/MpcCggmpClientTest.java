package org.nexus.signing.mpc.cggmp;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MpcCggmpClient} 单元测试（G 批）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>11 RPC 调用序列化正确（参数、is_p2p 字段、0-based 索引）</li>
 *   <li>gRPC 异常时返回 {@code success=false} 不抛异常</li>
 *   <li>参数校验：sessionId 格式、partyIndex 范围、messageHash 长度</li>
 *   <li>relay 池的 p2p/广播消歧（F 批关键修正）</li>
 * </ul>
 */
public class MpcCggmpClientTest {

    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub;
    private MpcCggmpClient client;

    @BeforeEach
    void setUp() {
        stub = mock(MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub.class);
        // withDeadlineAfter 自返回（与 GrpcMpcCryptoEngineTest 范式一致）
        when(stub.withDeadlineAfter(anyLong(), any())).thenReturn(stub);
        client = new MpcCggmpClient(stub, 30_000L);
    }

    // ============================================================
    // startKeygen
    // ============================================================

    @Test
    @DisplayName("startKeygen 序列化 0-based myIndex/total/threshold")
    void testStartKeygenSerializesParams() {
        MpcCryptoProto.CgStartKeygenRequest req = MpcCryptoProto.CgStartKeygenRequest.newBuilder()
                .setSessionId("session-1")
                .setCounter(0)
                .setMyIndex(0)
                .setTotalParties(3)
                .setThreshold(2)
                .build();
        MpcCryptoProto.CgPumpResponse resp = MpcCryptoProto.CgPumpResponse.newBuilder()
                .addOutgoing(MpcCryptoProto.CgRelayMessage.newBuilder()
                        .setSessionId("session-1")
                        .setSenderIndex(0)
                        .setReceiverIndex(0)
                        .setPayloadJson("{\"k\":1}")
                        .setIsP2P(false)
                        .build())
                .setFinished(false)
                .setSuccess(true)
                .build();
        when(stub.cgStartKeygen(any(MpcCryptoProto.CgStartKeygenRequest.class))).thenReturn(resp);

        CgPumpResult result = client.startKeygen("session-1", 0, 0, 3, 2);

        assertTrue(result.isSuccess());
        assertFalse(result.isFinished());
        assertEquals(1, result.getOutgoing().size());
        // 验证序列化（capture 入参）
        org.mockito.ArgumentCaptor<MpcCryptoProto.CgStartKeygenRequest> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgStartKeygenRequest.class);
        verify(stub).cgStartKeygen(cap.capture());
        MpcCryptoProto.CgStartKeygenRequest captured = cap.getValue();
        assertEquals("session-1", captured.getSessionId());
        assertEquals(0, captured.getMyIndex());        // 0-based
        assertEquals(3, captured.getTotalParties());
        assertEquals(2, captured.getThreshold());
    }

    @Test
    @DisplayName("startKeygen gRPC 失败 → success=false 不抛异常")
    void testStartKeygenGrpcFailure() {
        when(stub.cgStartKeygen(any(MpcCryptoProto.CgStartKeygenRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        CgPumpResult result = client.startKeygen("session-1", 0, 0, 3, 2);
        assertFalse(result.isSuccess());
        assertTrue(result.getError().contains("UNAVAILABLE"));
    }

    @Test
    @DisplayName("startKeygen 参数校验：sessionId 含非法字符")
    void testStartKeygenInvalidSessionId() {
        MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                () -> client.startKeygen("session/with/slash", 0, 0, 3, 2));
        assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
    }

    @Test
    @DisplayName("startKeygen 参数校验：myIndex 越界")
    void testStartKeygenInvalidPartyIndex() {
        MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                () -> client.startKeygen("session-1", 0, 256, 3, 2));
        assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
    }

    // ============================================================
    // startAux / pumpAux / assembleShare
    // ============================================================

    @Test
    @DisplayName("startAux 序列化")
    void testStartAux() {
        MpcCryptoProto.CgPumpResponse resp = MpcCryptoProto.CgPumpResponse.newBuilder()
                .setFinished(true).setSuccess(true).build();
        when(stub.cgStartAux(any(MpcCryptoProto.CgStartAuxRequest.class))).thenReturn(resp);
        CgPumpResult result = client.startAux("session-1", 0, 1, 3);
        assertTrue(result.isFinished());
        assertTrue(result.isSuccess());
    }

    @Test
    @DisplayName("assembleShare 返回 success")
    void testAssembleShare() {
        MpcCryptoProto.CgAck ack = MpcCryptoProto.CgAck.newBuilder().setSuccess(true).build();
        when(stub.cgAssembleShare(any(MpcCryptoProto.CgSessionOnly.class))).thenReturn(ack);
        assertTrue(client.assembleShare("session-1"));
    }

    @Test
    @DisplayName("assembleShare gRPC 失败 → false")
    void testAssembleShareFailure() {
        when(stub.cgAssembleShare(any(MpcCryptoProto.CgSessionOnly.class)))
                .thenThrow(new StatusRuntimeException(Status.INTERNAL));
        assertFalse(client.assembleShare("session-1"));
    }

    // ============================================================
    // startSign / pumpSign（带 messageHash 字节数组）
    // ============================================================

    @Test
    @DisplayName("startSign 序列化 messageHash 字节与 signersAtKeygen")
    void testStartSignSerializes() {
        byte[] hash = new byte[32];
        for (int i = 0; i < 32; i++) hash[i] = (byte) i;
        int[] signers = new int[]{0, 1};

        MpcCryptoProto.CgSignPumpResponse resp = MpcCryptoProto.CgSignPumpResponse.newBuilder()
                .setFinished(true)
                .setRHex("aa".repeat(32))
                .setSHex("bb".repeat(32))
                .setSuccess(true)
                .build();
        when(stub.cgStartSign(any(MpcCryptoProto.CgStartSignRequest.class))).thenReturn(resp);

        CgSignPumpResult result = client.startSign("session-1", 0, 0, signers, hash);

        assertTrue(result.isFinished());
        assertNotNull(result.getRHex());
        org.mockito.ArgumentCaptor<MpcCryptoProto.CgStartSignRequest> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgStartSignRequest.class);
        verify(stub).cgStartSign(cap.capture());
        MpcCryptoProto.CgStartSignRequest captured = cap.getValue();
        assertArrayEquals(hash, captured.getMessageHash().toByteArray());
        assertEquals(2, captured.getSignersAtKeygenCount());
        assertEquals(0, captured.getSignersAtKeygen(0));
        assertEquals(1, captured.getSignersAtKeygen(1));
        assertEquals(0, captured.getMyIndexInSigners());
    }

    @Test
    @DisplayName("startSign 拒绝非 32 字节 messageHash")
    void testStartSignInvalidHashLength() {
        MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                () -> client.startSign("session-1", 0, 0, new int[]{0}, new byte[16]));
        assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
    }

    // ============================================================
    // pumpKeygen / pumpAux / pumpSign
    // ============================================================

    @Test
    @DisplayName("pumpKeygen 序列化 incoming 列表")
    void testPumpKeygen() {
        MpcCryptoProto.CgPumpResponse resp = MpcCryptoProto.CgPumpResponse.newBuilder()
                .setFinished(true).setSuccess(true).build();
        when(stub.cgPumpKeygen(any(MpcCryptoProto.CgPumpRequest.class))).thenReturn(resp);

        CgRelayMessageDto incoming = new CgRelayMessageDto(
                "session-1", 1, 0, "{\"k\":2}", false);
        CgPumpResult result = client.pumpKeygen("session-1", List.of(incoming));
        assertTrue(result.isFinished());

        org.mockito.ArgumentCaptor<MpcCryptoProto.CgPumpRequest> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgPumpRequest.class);
        verify(stub).cgPumpKeygen(cap.capture());
        assertEquals(1, cap.getValue().getIncomingCount());
        // 验证 is_p2p 字段序列化
        assertEquals(false, cap.getValue().getIncoming(0).getIsP2P());
    }

    @Test
    @DisplayName("pumpAux 序列化 incoming 列表")
    void testPumpAux() {
        MpcCryptoProto.CgPumpResponse resp = MpcCryptoProto.CgPumpResponse.newBuilder()
                .setFinished(true).setSuccess(true).build();
        when(stub.cgPumpAux(any(MpcCryptoProto.CgPumpRequest.class))).thenReturn(resp);
        CgPumpResult result = client.pumpAux("session-1", List.of());
        assertTrue(result.isFinished());
    }

    @Test
    @DisplayName("pumpSign 返回 rHex/sHex")
    void testPumpSign() {
        MpcCryptoProto.CgSignPumpResponse resp = MpcCryptoProto.CgSignPumpResponse.newBuilder()
                .setFinished(true)
                .setRHex("11".repeat(32))
                .setSHex("22".repeat(32))
                .setSuccess(true)
                .build();
        when(stub.cgPumpSign(any(MpcCryptoProto.CgPumpRequest.class))).thenReturn(resp);
        CgSignPumpResult result = client.pumpSign("session-1", List.of());
        assertTrue(result.isFinished());
        assertEquals("11".repeat(32), result.getRHex());
    }

    // ============================================================
    // verifySignature / status
    // ============================================================

    @Test
    @DisplayName("verifySignature 序列化 32 字节 r/s/messageHash")
    void testVerifySignature() {
        MpcCryptoProto.CgVerifyResponse resp = MpcCryptoProto.CgVerifyResponse.newBuilder()
                .setValid(true).setSuccess(true).build();
        when(stub.cgVerifySignature(any(MpcCryptoProto.CgVerifyRequest.class))).thenReturn(resp);

        CgVerifyResult result = client.verifySignature(
                "session-1", new byte[32], new byte[32], new byte[32]);
        assertTrue(result.isValid());
        assertTrue(result.isSuccess());

        org.mockito.ArgumentCaptor<MpcCryptoProto.CgVerifyRequest> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgVerifyRequest.class);
        verify(stub).cgVerifySignature(cap.capture());
        assertEquals(32, cap.getValue().getSignatureR().size());
        assertEquals(32, cap.getValue().getSignatureS().size());
    }

    @Test
    @DisplayName("status 反映引擎状态")
    void testStatus() {
        MpcCryptoProto.CgStatusResponse resp = MpcCryptoProto.CgStatusResponse.newBuilder()
                .setHasKeygenState(true)
                .setHasAuxState(false)
                .setHasSignState(false)
                .setHasCoreShare(true)
                .setHasAuxInfo(false)
                .setHasKeyShare(true)
                .setSuccess(true)
                .build();
        when(stub.cgStatus(any(MpcCryptoProto.CgSessionOnly.class))).thenReturn(resp);

        CgStatus status = client.status("session-1");
        assertTrue(status.isHasKeygenState());
        assertTrue(status.isHasCoreShare());
        assertFalse(status.isHasAuxState());
    }

    // ============================================================
    // relay 池（关键：is_p2p 消歧）
    // ============================================================

    @Test
    @DisplayName("publishRelay 序列化 is_p2p=true（p2p 目标方 0 不被当广播）")
    void testPublishRelayIsP2P() {
        MpcCryptoProto.CgRelayAck ack = MpcCryptoProto.CgRelayAck.newBuilder().setSuccess(true).build();
        when(stub.cgRelayPublish(any(MpcCryptoProto.CgRelayMessage.class))).thenReturn(ack);

        CgRelayMessageDto p2pToZero = new CgRelayMessageDto(
                "session-1", 0, 0, "{\"direct\":\"to-zero\"}", true);
        assertTrue(client.publishRelay(p2pToZero));

        org.mockito.ArgumentCaptor<MpcCryptoProto.CgRelayMessage> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgRelayMessage.class);
        verify(stub).cgRelayPublish(cap.capture());
        MpcCryptoProto.CgRelayMessage captured = cap.getValue();
        assertEquals(true, captured.getIsP2P(), "F 批关键：p2p 目标方 0 必须保持 is_p2p=true");
        assertEquals(0, captured.getReceiverIndex());
        assertEquals(0, captured.getSenderIndex());
    }

    @Test
    @DisplayName("publishRelay 广播消息 is_p2p=false")
    void testPublishRelayBroadcast() {
        MpcCryptoProto.CgRelayAck ack = MpcCryptoProto.CgRelayAck.newBuilder().setSuccess(true).build();
        when(stub.cgRelayPublish(any(MpcCryptoProto.CgRelayMessage.class))).thenReturn(ack);

        CgRelayMessageDto broadcast = new CgRelayMessageDto(
                "session-1", 1, 0, "{\"broadcast\":true}", false);
        assertTrue(client.publishRelay(broadcast));

        org.mockito.ArgumentCaptor<MpcCryptoProto.CgRelayMessage> cap =
                org.mockito.ArgumentCaptor.forClass(MpcCryptoProto.CgRelayMessage.class);
        verify(stub).cgRelayPublish(cap.capture());
        assertEquals(false, cap.getValue().getIsP2P());
    }

    @Test
    @DisplayName("pullRelay 反序列化 is_p2p 字段")
    void testPullRelayDeserializesIsP2P() {
        MpcCryptoProto.CgRelayMessage m1 = MpcCryptoProto.CgRelayMessage.newBuilder()
                .setSessionId("session-1").setSenderIndex(1).setReceiverIndex(0)
                .setPayloadJson("{\"k\":1}").setIsP2P(true).build();
        MpcCryptoProto.CgRelayMessage m2 = MpcCryptoProto.CgRelayMessage.newBuilder()
                .setSessionId("session-1").setSenderIndex(2).setReceiverIndex(0)
                .setPayloadJson("{\"k\":2}").setIsP2P(false).build();
        MpcCryptoProto.CgRelayPullResponse resp = MpcCryptoProto.CgRelayPullResponse.newBuilder()
                .addMessages(m1).addMessages(m2).setSuccess(true).build();
        when(stub.cgRelayPull(any(MpcCryptoProto.CgRelayPullRequest.class))).thenReturn(resp);

        List<CgRelayMessageDto> msgs = client.pullRelay("session-1", 0);
        assertEquals(2, msgs.size());
        assertTrue(msgs.get(0).isP2P());
        assertFalse(msgs.get(1).isP2P());
        assertEquals(1, msgs.get(0).getSenderIndex());
    }

    @Test
    @DisplayName("publishRelay gRPC 失败 → false 不抛")
    void testPublishRelayFailure() {
        when(stub.cgRelayPublish(any(MpcCryptoProto.CgRelayMessage.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        CgRelayMessageDto m = new CgRelayMessageDto("s", 0, 0, "{}", false);
        assertFalse(client.publishRelay(m));
    }

    @Test
    @DisplayName("pullRelay gRPC 失败 → 空列表不抛")
    void testPullRelayFailure() {
        when(stub.cgRelayPull(any(MpcCryptoProto.CgRelayPullRequest.class)))
                .thenThrow(new StatusRuntimeException(Status.UNAVAILABLE));
        assertEquals(0, client.pullRelay("session-1", 0).size());
    }
}
