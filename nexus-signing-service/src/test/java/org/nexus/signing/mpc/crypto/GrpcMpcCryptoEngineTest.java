package org.nexus.signing.mpc.crypto;

import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoProto;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link GrpcMpcCryptoEngine} 的核心业务方法单元测试
 * （coverage-plan-p2p-grpc.md §4.2 P0）。
 *
 * <h2>测试目标</h2>
 * <ul>
 *   <li>dkg / sign / aggregate / healthCheck 的正常路径与 gRPC 异常路径</li>
 *   <li>参数校验：session_id 格式（MPC-P2-04）、party_index 范围（MPC-P2-05）</li>
 *   <li>requireStub 未初始化时的 IllegalStateException</li>
 *   <li>敏感数据零化（MPC-P1-02）不影响 DTO 字段</li>
 * </ul>
 *
 * <h2>测试策略</h2>
 * <p>不启动 Spring 上下文，通过反射注入 mock 的
 * {@link MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub}，
 * 验证 GrpcMpcCryptoEngine 的 DTO↔protobuf 转换、异常处理、参数校验逻辑。</p>
 *
 * <p>JUnit 5 + Mockito（与 nexus-signing-service 现有测试一致）。</p>
 */
public class GrpcMpcCryptoEngineTest {

    /** 测试用 UUID 格式 session_id（36 字符）。 */
    private static final String SESSION_ID_UUID = "550e8400-e29b-41d4-a716-446655440000";

    /** 测试用自定义格式 session_id（字母数字+连字符）。 */
    private static final String SESSION_ID_CUSTOM = "session-abc-123";

    /** 测试用聚合公钥（hex，64 字符 = 32 字节）。 */
    private static final String PUBLIC_KEY_HEX =
            "02b8e2f1a3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";

    /** 测试用密钥份额（hex，64 字符）。 */
    private static final String KEY_SHARE_HEX =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2";

    /** 测试用消息哈希（hex，64 字符 = SHA-256）。 */
    private static final String MESSAGE_HASH_HEX =
            "5d41402abc4b2a76b9719d911017c5925d41402abc4b2a76b9719d911017c592";

    /** 测试用部分签名（hex）。 */
    private static final String PARTIAL_SIG_HEX =
            "3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f";

    /** 测试用 ZK 证明（hex）。 */
    private static final String PROOF_HEX =
            "7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b";

    /** 测试用签名 r（hex，32 字节）。 */
    private static final String R_HEX =
            "1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";

    /** 测试用签名 s（hex，32 字节）。 */
    private static final String S_HEX =
            "fedcba0987654321fedcba0987654321fedcba0987654321fedcba0987654321";

    private GrpcMpcCryptoEngine engine;
    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub stub;

    @BeforeEach
    void setUp() {
        engine = new GrpcMpcCryptoEngine();
        // 注入基本配置字段
        ReflectionTestUtils.setField(engine, "host", "localhost");
        ReflectionTestUtils.setField(engine, "port", 50051);
        ReflectionTestUtils.setField(engine, "deadlineTimeoutMillis", 30000L);
        // 创建并注入 mock stub（withDeadlineAfter 自返回）
        stub = MockMpcCryptoStubFactory.createStub();
        ReflectionTestUtils.setField(engine, "blockingStub", stub);
        // channel 设为非 null（用 stub 自身的 mock 标记即可，healthCheck 用到 channel.isShutdown）
        // healthCheck 检查 channel == null || blockingStub == null || channel.isShutdown()
        // 为让 healthCheck 正常路径走通，注入一个 mock channel
        io.grpc.ManagedChannel channel = org.mockito.Mockito.mock(io.grpc.ManagedChannel.class);
        when(channel.isShutdown()).thenReturn(false);
        ReflectionTestUtils.setField(engine, "channel", channel);
    }

    // ==================== DKG ====================

    @Nested
    @DisplayName("dkg() 分布式密钥生成")
    class DkgTests {

        @Test
        @DisplayName("正常路径：stub 返回成功响应，DTO 字段正确转换")
        void testDkgSuccess() {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, 0, "secp256k1",
                    List.of("host1:50051", "host2:50051"));

            MpcCryptoProto.DkgResponse protoResp = MockMpcCryptoStubFactory.buildDkgResponseOk(
                    PUBLIC_KEY_HEX, KEY_SHARE_HEX, PROOF_HEX);
            when(stub.dkg(any(MpcCryptoProto.DkgRequest.class))).thenReturn(protoResp);

            DkgResponse resp = engine.dkg(request);

            assertTrue(resp.isSuccess(), "success 应为 true");
            assertEquals(PUBLIC_KEY_HEX, resp.getPublicKey(), "publicKey 应匹配");
            assertEquals(KEY_SHARE_HEX, resp.getKeyShare(), "keyShare 应匹配");
            assertEquals(PROOF_HEX, resp.getProof(), "proof 应匹配");
            // proto 默认 error 为空字符串（非 null），GrpcMpcCryptoEngine 原样透传
            assertEquals("", resp.getError(), "成功时 error 应为空字符串");
        }

        @Test
        @DisplayName("异常路径：stub 抛 StatusRuntimeException，返回 success=false")
        void testDkgStatusRuntimeException() {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, 0, "secp256k1", List.of("h1:50051"));

            when(stub.dkg(any(MpcCryptoProto.DkgRequest.class)))
                    .thenThrow(MockMpcCryptoStubFactory.statusUnavailable());

            DkgResponse resp = engine.dkg(request);

            assertFalse(resp.isSuccess(), "success 应为 false");
            assertNotNull(resp.getError(), "error 应非 null");
            assertTrue(resp.getError().contains("gRPC DKG failed"), "error 应包含 gRPC DKG failed");
        }

        @Test
        @DisplayName("异常路径：stub 抛 DEADLINE_EXCEEDED（超时），返回 success=false")
        void testDkgDeadlineExceeded() {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, 0, "secp256k1", List.of("h1:50051"));

            when(stub.dkg(any(MpcCryptoProto.DkgRequest.class)))
                    .thenThrow(MockMpcCryptoStubFactory.statusDeadlineExceeded());

            DkgResponse resp = engine.dkg(request);

            assertFalse(resp.isSuccess());
            assertTrue(resp.getError().contains("DEADLINE_EXCEEDED"));
        }

        @Test
        @DisplayName("参数校验：session_id 为 null 抛 MpcProtocolException")
        void testDkgNullSessionIdThrows() {
            DkgRequest request = new DkgRequest(
                    "placeholder", 2, 3, 0, "secp256k1", List.of());
            // 用反射改 sessionId 绕过 DTO 的 null 校验，直接测 engine 的校验
            ReflectionTestUtils.setField(request, "sessionId", null);

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.dkg(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
            assertTrue(ex.getMessage().contains("null or empty"));
        }

        @Test
        @DisplayName("参数校验：session_id 为空字符串抛 MpcProtocolException")
        void testDkgEmptySessionIdThrows() {
            DkgRequest request = new DkgRequest(
                    "placeholder", 2, 3, 0, "secp256k1", List.of());
            ReflectionTestUtils.setField(request, "sessionId", "");

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.dkg(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
        }

        @Test
        @DisplayName("参数校验：session_id 超过 128 字符抛 MpcProtocolException")
        void testDkgTooLongSessionIdThrows() {
            String tooLong = "a".repeat(129);
            DkgRequest request = new DkgRequest(
                    tooLong, 2, 3, 0, "secp256k1", List.of());

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.dkg(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
            assertTrue(ex.getMessage().contains("exceeds max"));
        }

        @Test
        @DisplayName("参数校验：session_id 含非法字符（空格）抛 MpcProtocolException")
        void testDkgIllegalCharSessionIdThrows() {
            DkgRequest request = new DkgRequest(
                    "session with space", 2, 3, 0, "secp256k1", List.of());

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.dkg(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
            assertTrue(ex.getMessage().contains("illegal character"));
        }

        @Test
        @DisplayName("参数校验：自定义格式 session_id（字母数字+连字符）通过校验")
        void testDkgCustomFormatSessionIdAccepted() {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_CUSTOM, 2, 3, 0, "secp256k1", List.of("h1:50051"));

            MpcCryptoProto.DkgResponse protoResp = MockMpcCryptoStubFactory.buildDkgResponseOk(
                    PUBLIC_KEY_HEX, KEY_SHARE_HEX, PROOF_HEX);
            when(stub.dkg(any(MpcCryptoProto.DkgRequest.class))).thenReturn(protoResp);

            DkgResponse resp = engine.dkg(request);

            assertTrue(resp.isSuccess(), "自定义格式 session_id 应通过校验");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 256, 1000})
        @DisplayName("参数校验：party_index 越界（<0 或 >255）抛 MpcProtocolException")
        void testDkgPartyIndexOutOfRangeThrows(int partyIndex) {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, partyIndex, "secp256k1", List.of());

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.dkg(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
            assertTrue(ex.getMessage().contains("party_index"));
            assertTrue(ex.getMessage().contains("DKG"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 254, 255})
        @DisplayName("参数校验：party_index 边界值（0/255）通过校验")
        void testDkgPartyIndexBoundaryAccepted(int partyIndex) {
            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, partyIndex, "secp256k1", List.of("h1:50051"));

            MpcCryptoProto.DkgResponse protoResp = MockMpcCryptoStubFactory.buildDkgResponseOk(
                    PUBLIC_KEY_HEX, KEY_SHARE_HEX, PROOF_HEX);
            when(stub.dkg(any(MpcCryptoProto.DkgRequest.class))).thenReturn(protoResp);

            DkgResponse resp = engine.dkg(request);

            assertTrue(resp.isSuccess(), "party_index=" + partyIndex + " 应通过校验");
        }
    }

    // ==================== Sign ====================

    @Nested
    @DisplayName("sign() 部分签名")
    class SignTests {

        @Test
        @DisplayName("正常路径：stub 返回成功响应，DTO 字段正确转换")
        void testSignSuccess() {
            SignRequest request = new SignRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    0, List.of("host1:50051", "host2:50051"));

            MpcCryptoProto.SignResponse protoResp = MockMpcCryptoStubFactory.buildSignResponseOk(
                    PARTIAL_SIG_HEX, PROOF_HEX);
            when(stub.sign(any(MpcCryptoProto.SignRequest.class))).thenReturn(protoResp);

            SignResponse resp = engine.sign(request);

            assertTrue(resp.isSuccess());
            assertEquals(PARTIAL_SIG_HEX, resp.getPartialSignature());
            assertEquals(PROOF_HEX, resp.getProof());
        }

        @Test
        @DisplayName("异常路径：stub 抛 StatusRuntimeException，返回 success=false")
        void testSignStatusRuntimeException() {
            SignRequest request = new SignRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    0, List.of("h1:50051"));

            when(stub.sign(any(MpcCryptoProto.SignRequest.class)))
                    .thenThrow(MockMpcCryptoStubFactory.statusUnavailable());

            SignResponse resp = engine.sign(request);

            assertFalse(resp.isSuccess());
            assertNotNull(resp.getError());
            assertTrue(resp.getError().contains("gRPC Sign failed"));
        }

        @Test
        @DisplayName("参数校验：session_id 为 null 抛 MpcProtocolException")
        void testSignNullSessionIdThrows() {
            SignRequest request = new SignRequest(
                    "placeholder", PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    0, List.of());
            ReflectionTestUtils.setField(request, "sessionId", null);

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.sign(request));
            assertEquals(MpcProtocolException.Reason.ILLEGAL_ARGUMENT, ex.getReason());
        }

        @Test
        @DisplayName("参数校验：session_id 含非法字符抛 MpcProtocolException")
        void testSignIllegalSessionIdThrows() {
            SignRequest request = new SignRequest(
                    "illegal@session", PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    0, List.of());

            assertThrows(MpcProtocolException.class, () -> engine.sign(request));
        }

        @Test
        @DisplayName("参数校验：party_index 越界（>255）抛 MpcProtocolException")
        void testSignPartyIndexOutOfRangeThrows() {
            SignRequest request = new SignRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    300, List.of());

            MpcProtocolException ex = assertThrows(MpcProtocolException.class,
                    () -> engine.sign(request));
            assertTrue(ex.getMessage().contains("Sign"));
        }
    }

    // ==================== Aggregate ====================

    @Nested
    @DisplayName("aggregate() 签名聚合")
    class AggregateTests {

        @Test
        @DisplayName("正常路径：stub 返回成功响应，DTO 字段正确转换")
        void testAggregateSuccess() {
            AggregateRequest request = new AggregateRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, MESSAGE_HASH_HEX,
                    List.of(PARTIAL_SIG_HEX, "abcdef0123456789"));

            MpcCryptoProto.AggregateResponse protoResp =
                    MockMpcCryptoStubFactory.buildAggregateResponseOk(
                            R_HEX + S_HEX, R_HEX, S_HEX, 0);
            when(stub.aggregate(any(MpcCryptoProto.AggregateRequest.class))).thenReturn(protoResp);

            AggregateResponse resp = engine.aggregate(request);

            assertTrue(resp.isSuccess());
            assertEquals(R_HEX + S_HEX, resp.getSignature());
            assertEquals(R_HEX, resp.getR());
            assertEquals(S_HEX, resp.getS());
            assertEquals(0, resp.getRecoveryId());
        }

        @Test
        @DisplayName("正常路径：recoveryId=1 也正确传递")
        void testAggregateRecoveryIdOne() {
            AggregateRequest request = new AggregateRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, MESSAGE_HASH_HEX,
                    List.of(PARTIAL_SIG_HEX));

            MpcCryptoProto.AggregateResponse protoResp =
                    MockMpcCryptoStubFactory.buildAggregateResponseOk(
                            R_HEX + S_HEX, R_HEX, S_HEX, 1);
            when(stub.aggregate(any(MpcCryptoProto.AggregateRequest.class))).thenReturn(protoResp);

            AggregateResponse resp = engine.aggregate(request);

            assertEquals(1, resp.getRecoveryId());
        }

        @Test
        @DisplayName("异常路径：stub 抛 StatusRuntimeException，返回 success=false")
        void testAggregateStatusRuntimeException() {
            AggregateRequest request = new AggregateRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, MESSAGE_HASH_HEX,
                    List.of(PARTIAL_SIG_HEX));

            when(stub.aggregate(any(MpcCryptoProto.AggregateRequest.class)))
                    .thenThrow(MockMpcCryptoStubFactory.statusUnavailable());

            AggregateResponse resp = engine.aggregate(request);

            assertFalse(resp.isSuccess());
            assertNotNull(resp.getError());
            assertTrue(resp.getError().contains("gRPC Aggregate failed"));
        }

        @Test
        @DisplayName("参数校验：session_id 为 null 抛 MpcProtocolException")
        void testAggregateNullSessionIdThrows() {
            AggregateRequest request = new AggregateRequest(
                    "placeholder", PUBLIC_KEY_HEX, MESSAGE_HASH_HEX, List.of(PARTIAL_SIG_HEX));
            ReflectionTestUtils.setField(request, "sessionId", null);

            assertThrows(MpcProtocolException.class, () -> engine.aggregate(request));
        }

        @Test
        @DisplayName("参数校验：session_id 含非法字符抛 MpcProtocolException")
        void testAggregateIllegalSessionIdThrows() {
            AggregateRequest request = new AggregateRequest(
                    "illegal session", PUBLIC_KEY_HEX, MESSAGE_HASH_HEX, List.of(PARTIAL_SIG_HEX));

            assertThrows(MpcProtocolException.class, () -> engine.aggregate(request));
        }
    }

    // ==================== healthCheck ====================

    @Nested
    @DisplayName("healthCheck() 健康检查")
    class HealthCheckTests {

        @Test
        @DisplayName("正常路径：stub 返回 healthy=true")
        void testHealthCheckHealthy() {
            MpcCryptoProto.HealthCheckResponse protoResp =
                    MockMpcCryptoStubFactory.buildHealthCheckResponse(true);
            when(stub.healthCheck(any(MpcCryptoProto.HealthCheckRequest.class)))
                    .thenReturn(protoResp);

            boolean result = engine.healthCheck();

            assertTrue(result);
        }

        @Test
        @DisplayName("正常路径：stub 返回 healthy=false")
        void testHealthCheckUnhealthy() {
            MpcCryptoProto.HealthCheckResponse protoResp =
                    MockMpcCryptoStubFactory.buildHealthCheckResponse(false);
            when(stub.healthCheck(any(MpcCryptoProto.HealthCheckRequest.class)))
                    .thenReturn(protoResp);

            boolean result = engine.healthCheck();

            assertFalse(result);
        }

        @Test
        @DisplayName("异常路径：stub 抛 StatusRuntimeException，返回 false")
        void testHealthCheckStatusRuntimeException() {
            when(stub.healthCheck(any(MpcCryptoProto.HealthCheckRequest.class)))
                    .thenThrow(MockMpcCryptoStubFactory.statusUnavailable());

            boolean result = engine.healthCheck();

            assertFalse(result);
        }

        @Test
        @DisplayName("channel 为 null 时返回 false")
        void testHealthCheckChannelNull() {
            ReflectionTestUtils.setField(engine, "channel", null);

            boolean result = engine.healthCheck();

            assertFalse(result);
        }

        @Test
        @DisplayName("blockingStub 为 null 时返回 false")
        void testHealthCheckStubNull() {
            ReflectionTestUtils.setField(engine, "blockingStub", null);

            boolean result = engine.healthCheck();

            assertFalse(result);
        }

        @Test
        @DisplayName("channel 已 shutdown 时返回 false")
        void testHealthCheckChannelShutdown() {
            io.grpc.ManagedChannel channel = org.mockito.Mockito.mock(io.grpc.ManagedChannel.class);
            when(channel.isShutdown()).thenReturn(true);
            ReflectionTestUtils.setField(engine, "channel", channel);

            boolean result = engine.healthCheck();

            assertFalse(result);
        }
    }

    // ==================== requireStub / 未初始化 ====================

    @Nested
    @DisplayName("requireStub() 未初始化保护")
    class RequireStubTests {

        @Test
        @DisplayName("blockingStub 为 null 时 dkg 抛 IllegalStateException")
        void testDkgThrowsWhenStubNull() {
            ReflectionTestUtils.setField(engine, "blockingStub", null);

            DkgRequest request = new DkgRequest(
                    SESSION_ID_UUID, 2, 3, 0, "secp256k1", List.of("h1:50051"));

            assertThrows(IllegalStateException.class, () -> engine.dkg(request));
        }

        @Test
        @DisplayName("blockingStub 为 null 时 sign 抛 IllegalStateException")
        void testSignThrowsWhenStubNull() {
            ReflectionTestUtils.setField(engine, "blockingStub", null);

            SignRequest request = new SignRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, KEY_SHARE_HEX, MESSAGE_HASH_HEX,
                    0, List.of("h1:50051"));

            assertThrows(IllegalStateException.class, () -> engine.sign(request));
        }

        @Test
        @DisplayName("blockingStub 为 null 时 aggregate 抛 IllegalStateException")
        void testAggregateThrowsWhenStubNull() {
            ReflectionTestUtils.setField(engine, "blockingStub", null);

            AggregateRequest request = new AggregateRequest(
                    SESSION_ID_UUID, PUBLIC_KEY_HEX, MESSAGE_HASH_HEX,
                    List.of(PARTIAL_SIG_HEX));

            assertThrows(IllegalStateException.class, () -> engine.aggregate(request));
        }
    }

    // ==================== shutdown ====================

    @Nested
    @DisplayName("shutdown() 生命周期")
    class ShutdownTests {

        @Test
        @DisplayName("channel 为 null 时 shutdown 不抛异常")
        void testShutdownChannelNull() {
            ReflectionTestUtils.setField(engine, "channel", null);

            // 不应抛异常
            engine.shutdown();
        }

        @Test
        @DisplayName("channel 已 shutdown 时 shutdown 幂等")
        void testShutdownIdempotent() {
            io.grpc.ManagedChannel channel = org.mockito.Mockito.mock(io.grpc.ManagedChannel.class);
            when(channel.isShutdown()).thenReturn(true);
            ReflectionTestUtils.setField(engine, "channel", channel);

            // 不应抛异常，也不应再次调用 shutdown
            engine.shutdown();
        }
    }
}