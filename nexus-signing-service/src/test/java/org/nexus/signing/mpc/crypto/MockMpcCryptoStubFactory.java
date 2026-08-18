package org.nexus.signing.mpc.crypto;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 构造 {@link MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub} 的 Mockito 工厂
 * （coverage-plan-p2p-grpc.md §4.3）。
 *
 * <p>预置 withDeadlineAfter 自返回，简化各测试用例的 stub 桩代码。
 * 所有 dkg/sign/aggregate/healthCheck 方法默认未打桩，由调用方按需 when().thenReturn()。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub =
 *     MockMpcCryptoStubFactory.createStub();
 * when(stub.dkg(any())).thenReturn(MockMpcCryptoStubFactory.buildDkgResponseOk(...));
 * </pre>
 */
public final class MockMpcCryptoStubFactory {

    private MockMpcCryptoStubFactory() {
    }

    /**
     * 创建一个 blocking stub mock，预置 {@code withDeadlineAfter} 自返回。
     *
     * @return mocked blocking stub
     */
    public static MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub createStub() {
        MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub =
                mock(MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub.class);
        // GrpcMpcCryptoEngine 在每次调用前会 withDeadlineAfter(...)，需返回桩自身
        when(stub.withDeadlineAfter(anyLong(), any(TimeUnit.class))).thenReturn(stub);
        return stub;
    }

    /**
     * 构造 DKG 成功响应（含 publicKey / keyShare / proof）。
     */
    public static MpcCryptoProto.DkgResponse buildDkgResponseOk(
            String publicKey, String keyShareHex, String proof) {
        return MpcCryptoProto.DkgResponse.newBuilder()
                .setPublicKey(publicKey)
                .setKeyShare(keyShareHex)
                .setProof(proof)
                .setSuccess(true)
                .setError("")
                .build();
    }

    /**
     * 构造 DKG 失败响应（success=false，error 非空）。
     */
    public static MpcCryptoProto.DkgResponse buildDkgResponseFail(String error) {
        return MpcCryptoProto.DkgResponse.newBuilder()
                .setSuccess(false)
                .setError(error)
                .build();
    }

    /**
     * 构造 Sign 成功响应。
     */
    public static MpcCryptoProto.SignResponse buildSignResponseOk(
            String partialSignatureHex, String proof) {
        return MpcCryptoProto.SignResponse.newBuilder()
                .setPartialSignature(partialSignatureHex)
                .setProof(proof)
                .setSuccess(true)
                .setError("")
                .build();
    }

    /**
     * 构造 Sign 失败响应。
     */
    public static MpcCryptoProto.SignResponse buildSignResponseFail(String error) {
        return MpcCryptoProto.SignResponse.newBuilder()
                .setSuccess(false)
                .setError(error)
                .build();
    }

    /**
     * 构造 Aggregate 成功响应。
     */
    public static MpcCryptoProto.AggregateResponse buildAggregateResponseOk(
            String signatureHex, String rHex, String sHex, int recoveryId) {
        return MpcCryptoProto.AggregateResponse.newBuilder()
                .setSignature(signatureHex)
                .setR(rHex)
                .setS(sHex)
                .setRecoveryId(recoveryId)
                .setSuccess(true)
                .setError("")
                .build();
    }

    /**
     * 构造 Aggregate 失败响应。
     */
    public static MpcCryptoProto.AggregateResponse buildAggregateResponseFail(String error) {
        return MpcCryptoProto.AggregateResponse.newBuilder()
                .setSuccess(false)
                .setError(error)
                .build();
    }

    /**
     * 构造 HealthCheck 响应。
     */
    public static MpcCryptoProto.HealthCheckResponse buildHealthCheckResponse(boolean healthy) {
        return MpcCryptoProto.HealthCheckResponse.newBuilder()
                .setHealthy(healthy)
                .build();
    }

    /**
     * 构造一个 gRPC StatusRuntimeException（UNAVAILABLE，模拟引擎不可达）。
     */
    public static StatusRuntimeException statusUnavailable() {
        return new StatusRuntimeException(Status.UNAVAILABLE.withDescription("engine unreachable"));
    }

    /**
     * 构造一个 gRPC StatusRuntimeException（DEADLINE_EXCEEDED，模拟超时）。
     */
    public static StatusRuntimeException statusDeadlineExceeded() {
        return new StatusRuntimeException(Status.DEADLINE_EXCEEDED.withDescription("rpc timeout"));
    }
}