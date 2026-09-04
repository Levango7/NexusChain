package org.nexus.signing.mpc.cggmp;

import io.grpc.StatusRuntimeException;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * CGGMP21 分散式门限签名生命周期客户端（G 批）。
 *
 * <p>封装 mpc-engine CGGMP21 RPC 面的 11 个调用：</p>
 * <ul>
 *   <li>启动：{@link #startKeygen} / {@link #startAux} / {@link #startSign} /
 *       {@link #assembleShare}</li>
 *   <li>泵动：{@link #pumpKeygen} / {@link #pumpAux} / {@link #pumpSign}</li>
 *   <li>辅助：{@link #verifySignature} / {@link #status}</li>
 *   <li>relay 池：{@link #publishRelay} / {@link #pullRelay}</li>
 * </ul>
 *
 * <h2>使用范式</h2>
 * <p>每个参与方进程的 CGGMP 协议循环：</p>
 * <pre>
 *   1. startKeygen → outgoing[]
 *   2. publish all outgoing to coordinator
 *   3. pull incoming from coordinator
 *   4. pumpKeygen(incoming) → outgoing[] (重复 2-4 直到 finished)
 *   5. aux / sign 阶段重复
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>依赖底层的 gRPC blocking stub 线程安全；本门面不做额外共享状态。
 * 一个实例可服务多线程并发调用。</p>
 *
 * <h2>参数校验</h2>
 * <p>遵循与 GG20 路径一致的 MPC-P2-04/05 校验（sessionId 格式、
 * partyIndex 范围），不通过抛 {@link MpcProtocolException}。</p>
 *
 * <h2>异常处理</h2>
 * <p>gRPC 传输层失败（{@link StatusRuntimeException}）一律转为
 * {@code success=false} 的 DTO（不抛异常），与 {@code GrpcMpcCryptoEngine}
 * 风格一致——编排层据此做熔断 / 重试。</p>
 */
public final class MpcCggmpClient {

    private static final Logger log = LoggerFactory.getLogger(MpcCggmpClient.class);

    /** MPC 协议支持的最大参与方数（与 GrpcMpcCryptoEngine 对齐）。 */
    private static final int MAX_PARTY_INDEX = 255;

    /** session_id 最大长度（与 GrpcMpcCryptoEngine 对齐）。 */
    private static final int MAX_SESSION_ID_LENGTH = 128;

    private final MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub;
    private final long deadlineTimeoutMillis;

    public MpcCggmpClient(
            MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub,
            long deadlineTimeoutMillis) {
        this.stub = Objects.requireNonNull(stub, "stub");
        this.deadlineTimeoutMillis = deadlineTimeoutMillis;
    }

    // ============================================================
    // 阶段启动
    // ============================================================

    /**
     * 启动 keygen 协议。
     *
     * @param sessionId      会话 ID
     * @param counter        eid 防重放序号（首次 0，重试单调递增）
     * @param myIndex        本方 0-based 索引
     * @param totalParties   n
     * @param threshold      t
     * @return 首次泵结果
     */
    public CgPumpResult startKeygen(
            String sessionId, int counter, int myIndex, int totalParties, int threshold) {
        validateSessionId(sessionId);
        validatePartyIndex(myIndex, "CgStartKeygen");

        MpcCryptoProto.CgStartKeygenRequest req = MpcCryptoProto.CgStartKeygenRequest.newBuilder()
                .setSessionId(sessionId)
                .setCounter(counter)
                .setMyIndex(myIndex)
                .setTotalParties(totalParties)
                .setThreshold(threshold)
                .build();
        try {
            MpcCryptoProto.CgPumpResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgStartKeygen(req);
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("CgStartKeygen gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return CgPumpResult.failure("gRPC CgStartKeygen failed: " + e.getStatus());
        }
    }

    /**
     * 启动 aux（辅助信息）协议。
     */
    public CgPumpResult startAux(
            String sessionId, int counter, int myIndex, int totalParties) {
        validateSessionId(sessionId);
        validatePartyIndex(myIndex, "CgStartAux");

        MpcCryptoProto.CgStartAuxRequest req = MpcCryptoProto.CgStartAuxRequest.newBuilder()
                .setSessionId(sessionId)
                .setCounter(counter)
                .setMyIndex(myIndex)
                .setTotalParties(totalParties)
                .build();
        try {
            MpcCryptoProto.CgPumpResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgStartAux(req);
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("CgStartAux gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return CgPumpResult.failure("gRPC CgStartAux failed: " + e.getStatus());
        }
    }

    /**
     * 组装核心份额（keygen 完成后调用，从 keygen 状态合成 core share）。
     */
    public boolean assembleShare(String sessionId) {
        validateSessionId(sessionId);
        MpcCryptoProto.CgSessionOnly req = MpcCryptoProto.CgSessionOnly.newBuilder()
                .setSessionId(sessionId)
                .build();
        try {
            MpcCryptoProto.CgAck resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgAssembleShare(req);
            return resp.getSuccess();
        } catch (StatusRuntimeException e) {
            log.error("CgAssembleShare gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return false;
        }
    }

    /**
     * 启动 sign 协议。
     *
     * @param signersAtKeygen 本批签名方在 keygen 时的原始 0-based 索引（恰好 t 个）
     * @param messageHash     32 字节消息哈希（原始字节）
     */
    public CgSignPumpResult startSign(
            String sessionId,
            int counter,
            int myIndexInSigners,
            int[] signersAtKeygen,
            byte[] messageHash) {
        validateSessionId(sessionId);
        Objects.requireNonNull(messageHash, "messageHash");
        if (messageHash.length != 32) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "messageHash must be 32 bytes, got " + messageHash.length);
        }
        MpcCryptoProto.CgStartSignRequest.Builder b = MpcCryptoProto.CgStartSignRequest.newBuilder()
                .setSessionId(sessionId)
                .setCounter(counter)
                .setMyIndexInSigners(myIndexInSigners)
                .setMessageHash(com.google.protobuf.ByteString.copyFrom(messageHash));
        if (signersAtKeygen != null) {
            for (int idx : signersAtKeygen) {
                b.addSignersAtKeygen(idx);
            }
        }
        try {
            MpcCryptoProto.CgSignPumpResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgStartSign(b.build());
            return fromSignProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("CgStartSign gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return CgSignPumpResult.failure("gRPC CgStartSign failed: " + e.getStatus());
        }
    }

    // ============================================================
    // 阶段泵动
    // ============================================================

    public CgPumpResult pumpKeygen(String sessionId, List<CgRelayMessageDto> incoming) {
        return pumpGeneric(sessionId, incoming, "CgPumpKeygen", true);
    }

    public CgPumpResult pumpAux(String sessionId, List<CgRelayMessageDto> incoming) {
        return pumpGeneric(sessionId, incoming, "CgPumpAux", false);
    }

    public CgSignPumpResult pumpSign(String sessionId, List<CgRelayMessageDto> incoming) {
        validateSessionId(sessionId);
        MpcCryptoProto.CgPumpRequest req = MpcCryptoProto.CgPumpRequest.newBuilder()
                .setSessionId(sessionId)
                .addAllIncoming(toProto(incoming))
                .build();
        try {
            MpcCryptoProto.CgSignPumpResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgPumpSign(req);
            return fromSignProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("CgPumpSign gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return CgSignPumpResult.failure("gRPC CgPumpSign failed: " + e.getStatus());
        }
    }

    private CgPumpResult pumpGeneric(
            String sessionId,
            List<CgRelayMessageDto> incoming,
            String opName,
            boolean isKeygen) {
        validateSessionId(sessionId);
        MpcCryptoProto.CgPumpRequest req = MpcCryptoProto.CgPumpRequest.newBuilder()
                .setSessionId(sessionId)
                .addAllIncoming(toProto(incoming))
                .build();
        try {
            MpcCryptoProto.CgPumpResponse resp;
            if (isKeygen) {
                resp = stub
                        .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                        .cgPumpKeygen(req);
            } else {
                resp = stub
                        .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                        .cgPumpAux(req);
            }
            return fromProto(resp);
        } catch (StatusRuntimeException e) {
            log.error("{} gRPC failed: session={}, status={}", opName, sessionId, e.getStatus(), e);
            return CgPumpResult.failure("gRPC " + opName + " failed: " + e.getStatus());
        }
    }

    // ============================================================
    // 辅助
    // ============================================================

    public CgVerifyResult verifySignature(
            String sessionId, byte[] r, byte[] s, byte[] messageHash) {
        validateSessionId(sessionId);
        Objects.requireNonNull(r, "r");
        Objects.requireNonNull(s, "s");
        Objects.requireNonNull(messageHash, "messageHash");
        if (r.length != 32 || s.length != 32 || messageHash.length != 32) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "r/s/messageHash must each be 32 bytes, got r=" + r.length
                            + " s=" + s.length + " msg=" + messageHash.length);
        }
        MpcCryptoProto.CgVerifyRequest req = MpcCryptoProto.CgVerifyRequest.newBuilder()
                .setSessionId(sessionId)
                .setSignatureR(com.google.protobuf.ByteString.copyFrom(r))
                .setSignatureS(com.google.protobuf.ByteString.copyFrom(s))
                .setMessageHash(com.google.protobuf.ByteString.copyFrom(messageHash))
                .build();
        try {
            MpcCryptoProto.CgVerifyResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgVerifySignature(req);
            return new CgVerifyResult(resp.getValid(), resp.getSuccess(), resp.getError());
        } catch (StatusRuntimeException e) {
            log.error("CgVerifySignature gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return new CgVerifyResult(false, false, "gRPC CgVerifySignature failed: " + e.getStatus());
        }
    }

    public CgStatus status(String sessionId) {
        validateSessionId(sessionId);
        MpcCryptoProto.CgSessionOnly req = MpcCryptoProto.CgSessionOnly.newBuilder()
                .setSessionId(sessionId)
                .build();
        try {
            MpcCryptoProto.CgStatusResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgStatus(req);
            return new CgStatus(
                    resp.getHasKeygenState(),
                    resp.getHasAuxState(),
                    resp.getHasSignState(),
                    resp.getHasCoreShare(),
                    resp.getHasAuxInfo(),
                    resp.getHasKeyShare(),
                    resp.getSuccess(),
                    resp.getError());
        } catch (StatusRuntimeException e) {
            log.error("CgStatus gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return new CgStatus(false, false, false, false, false, false, false,
                    "gRPC CgStatus failed: " + e.getStatus());
        }
    }

    // ============================================================
    // Relay 池
    // ============================================================

    public boolean publishRelay(CgRelayMessageDto message) {
        Objects.requireNonNull(message, "message");
        validateSessionId(message.getSessionId());
        MpcCryptoProto.CgRelayMessage req = toProto(message);
        try {
            MpcCryptoProto.CgRelayAck resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgRelayPublish(req);
            if (!resp.getSuccess()) {
                log.warn("CgRelayPublish returned success=false: session={}, err={}",
                        message.getSessionId(), resp.getError());
            }
            return resp.getSuccess();
        } catch (StatusRuntimeException e) {
            log.error("CgRelayPublish gRPC failed: session={}, status={}",
                    message.getSessionId(), e.getStatus(), e);
            return false;
        }
    }

    public List<CgRelayMessageDto> pullRelay(String sessionId, int myIndex) {
        validateSessionId(sessionId);
        validatePartyIndex(myIndex, "CgRelayPull");
        MpcCryptoProto.CgRelayPullRequest req = MpcCryptoProto.CgRelayPullRequest.newBuilder()
                .setSessionId(sessionId)
                .setMyIndex(myIndex)
                .build();
        try {
            MpcCryptoProto.CgRelayPullResponse resp = stub
                    .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS)
                    .cgRelayPull(req);
            if (!resp.getSuccess()) {
                log.warn("CgRelayPull returned success=false: session={}, err={}",
                        sessionId, resp.getError());
            }
            return fromProtoList(resp.getMessagesList());
        } catch (StatusRuntimeException e) {
            log.error("CgRelayPull gRPC failed: session={}, status={}", sessionId, e.getStatus(), e);
            return new ArrayList<>();
        }
    }

    // ============================================================
    // DTO <-> proto 转换
    // ============================================================

    private CgPumpResult fromProto(MpcCryptoProto.CgPumpResponse resp) {
        return new CgPumpResult(
                fromProtoList(resp.getOutgoingList()),
                resp.getFinished(),
                resp.getAggregatePublicKey(),
                resp.getSuccess(),
                resp.getError());
    }

    private CgSignPumpResult fromSignProto(MpcCryptoProto.CgSignPumpResponse resp) {
        return new CgSignPumpResult(
                fromProtoList(resp.getOutgoingList()),
                resp.getFinished(),
                resp.getRHex(),
                resp.getSHex(),
                resp.getSuccess(),
                resp.getError());
    }

    private List<CgRelayMessageDto> fromProtoList(List<MpcCryptoProto.CgRelayMessage> protos) {
        if (protos == null || protos.isEmpty()) {
            return new ArrayList<>();
        }
        List<CgRelayMessageDto> out = new ArrayList<>(protos.size());
        for (MpcCryptoProto.CgRelayMessage p : protos) {
            out.add(new CgRelayMessageDto(
                    p.getSessionId(),
                    p.getSenderIndex(),
                    p.getReceiverIndex(),
                    p.getPayloadJson(),
                    p.getIsP2P()));
        }
        return out;
    }

    private List<MpcCryptoProto.CgRelayMessage> toProto(List<CgRelayMessageDto> dtos) {
        List<MpcCryptoProto.CgRelayMessage> out = new ArrayList<>();
        if (dtos == null) {
            return out;
        }
        for (CgRelayMessageDto d : dtos) {
            out.add(toProto(d));
        }
        return out;
    }

    private MpcCryptoProto.CgRelayMessage toProto(CgRelayMessageDto d) {
        return MpcCryptoProto.CgRelayMessage.newBuilder()
                .setSessionId(d.getSessionId())
                .setSenderIndex(d.getSenderIndex())
                .setReceiverIndex(d.getReceiverIndex())
                .setPayloadJson(d.getPayloadJson())
                .setIsP2P(d.isP2P())
                .build();
    }

    // ============================================================
    // 参数校验（与 GrpcMpcCryptoEngine 一致）
    // ============================================================

    private static void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid session_id format: null or empty");
        }
        if (sessionId.length() > MAX_SESSION_ID_LENGTH) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid session_id format: length " + sessionId.length()
                            + " exceeds max " + MAX_SESSION_ID_LENGTH);
        }
        for (int i = 0; i < sessionId.length(); i++) {
            char c = sessionId.charAt(i);
            boolean ok = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || c == '-';
            if (!ok) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "Invalid session_id format: contains illegal character '"
                                + c + "' at index " + i);
            }
        }
    }

    private static void validatePartyIndex(int partyIndex, String operation) {
        if (partyIndex < 0 || partyIndex > MAX_PARTY_INDEX) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "Invalid party_index for " + operation + ": " + partyIndex
                            + " (must be in [0, " + MAX_PARTY_INDEX + "], MPC max 256 parties)");
        }
    }
}
