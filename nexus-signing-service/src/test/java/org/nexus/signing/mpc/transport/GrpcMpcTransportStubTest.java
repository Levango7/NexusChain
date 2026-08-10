package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GrpcMpcTransportStub} 单元测试（P5-T3 真实化）。
 *
 * <h2>测试矩阵</h2>
 * <ul>
 *   <li><b>内存回退模式</b>（realGrpcEnabled=false）：验证 connect/send/receive/close
 *       通过 {@link InMemoryMpcTransport} 回退工作。</li>
 *   <li><b>真实 gRPC 模式</b>（realGrpcEnabled=true）：启动 {@link MpcTransportGrpcServer}
 *       内嵌 server，验证 connect/send/receive 通过真实 gRPC 传输。</li>
 * </ul>
 *
 * <p>JUnit 4（与现有测试一致）。</p>
 */
public class GrpcMpcTransportStubTest {

    /** 测试用 gRPC server 端口（避免与生产端口冲突）。 */
    private static final int TEST_SERVER_PORT = 51090;

    private GrpcMpcTransportStub stub = new GrpcMpcTransportStub();
    private MpcTransportGrpcServer server;
    private GrpcMpcTransportStub realGrpcStub;

    @AfterEach
    public void tearDown() {
        if (stub != null) {
            stub.close();
        }
        if (realGrpcStub != null) {
            realGrpcStub.close();
            realGrpcStub = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private List<MpcParticipant> twoParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"));
    }

    // ==================== 内存回退模式测试 ====================

    @Test
    public void testDefaultConstructorUsesFallback() {
        assertFalse(stub.isConnected());
        stub.connect(twoParticipants());
        assertTrue(stub.isConnected());
    }

    @Test
    public void testRealGrpcDisabledUsesFallback() {
        GrpcMpcTransportStub s = new GrpcMpcTransportStub(false);
        s.connect(twoParticipants());
        assertTrue(s.isConnected());
        assertFalse(s.isRealGrpcEnabled(), "should not be in real gRPC mode");
        s.close();
    }

    @Test
    public void testConnectPopulatesChannels() {
        stub.connect(twoParticipants());
        Map<String, String> channels = stub.getChannels();
        assertEquals(2, channels.size());
        assertTrue(channels.containsKey("p1"));
        assertTrue(channels.get("p1").startsWith("grpc://"));
        assertTrue(channels.get("p2").startsWith("grpc://"));
    }

    @Test
    public void testSendBroadcastViaFallback() {
        stub.connect(twoParticipants());
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        stub.send(msg);
        MpcMessage received = stub.receive("s1", 1, "p1", 100);
        assertEquals(received.getPayloadHex(), "payload");
    }

    @Test
    public void testCloseClearsChannels() {
        stub.connect(twoParticipants());
        assertEquals(2, stub.getChannels().size());
        stub.close();
        assertEquals(0, stub.getChannels().size());
        assertFalse(stub.isConnected());
    }

    // ==================== 真实 gRPC 模式测试 ====================

    /**
     * 真实 gRPC 模式：启动内嵌 server，验证 connect 创建 channel 且 isConnected 返回 true。
     */
    @Test
    public void testRealGrpcConnectCreatesChannels() throws Exception {
        // 启动内嵌 gRPC server
        realGrpcStub = new GrpcMpcTransportStub(true, 5000, true);
        server = new MpcTransportGrpcServer(realGrpcStub, TEST_SERVER_PORT, true);
        server.start();

        // 创建指向本地 server 的 participant
        List<MpcParticipant> participants = List.of(
                new MpcParticipant("local", "localhost:" + TEST_SERVER_PORT, "pk-local"));

        realGrpcStub.connect(participants);
        assertTrue(realGrpcStub.isConnected(), "should be connected");
        assertTrue(realGrpcStub.isRealGrpcEnabled(), "should be in real gRPC mode");
        assertEquals(1, realGrpcStub.getChannels().size());
        assertTrue(realGrpcStub.getChannels().containsKey("local"));
    }

    /**
     * 真实 gRPC 模式：send 通过 gRPC 发送消息到 server，server 投递到本地邮箱，
     * receive 从本地邮箱取出。
     */
    @Test
    public void testRealGrpcSendAndReceive() throws Exception {
        // 启动内嵌 gRPC server
        realGrpcStub = new GrpcMpcTransportStub(true, 5000, true);
        server = new MpcTransportGrpcServer(realGrpcStub, TEST_SERVER_PORT + 1, true);
        server.start();

        // 创建指向本地 server 的 participant（模拟对端）
        List<MpcParticipant> participants = List.of(
                new MpcParticipant("self", "localhost:" + (TEST_SERVER_PORT + 1), "pk-self"),
                new MpcParticipant("peer", "localhost:" + (TEST_SERVER_PORT + 1), "pk-peer"));

        realGrpcStub.connect(participants);

        // 发送点对点消息到 "peer"（实际发到本地 server，server 投递到本地邮箱）
        MpcMessage msg = MpcMessage.create("session-1", 1, MpcMessage.Type.SIGN_ROUND,
                "peer", "self", "partial-sig-payload");
        realGrpcStub.send(msg);

        // 从本地邮箱接收来自 "peer" 的消息
        MpcMessage received = realGrpcStub.receive("session-1", 1, "peer", 2000);
        assertNotNull(received, "should receive message");
        assertEquals("partial-sig-payload", received.getPayloadHex(), "payload should match");
        assertEquals("peer", received.getFromParticipantId(), "from should be peer");
        assertEquals("session-1", received.getSessionId(), "session should match");
    }

    /**
     * 真实 gRPC 模式：广播消息发送给所有其他参与者。
     */
    @Test
    public void testRealGrpcBroadcast() throws Exception {
        realGrpcStub = new GrpcMpcTransportStub(true, 5000, true);
        server = new MpcTransportGrpcServer(realGrpcStub, TEST_SERVER_PORT + 2, true);
        server.start();

        List<MpcParticipant> participants = List.of(
                new MpcParticipant("p1", "localhost:" + (TEST_SERVER_PORT + 2), "pk1"),
                new MpcParticipant("p2", "localhost:" + (TEST_SERVER_PORT + 2), "pk2"),
                new MpcParticipant("p3", "localhost:" + (TEST_SERVER_PORT + 2), "pk3"));

        realGrpcStub.connect(participants);

        // p1 广播消息
        MpcMessage broadcast = MpcMessage.create("session-bc", 1,
                MpcMessage.Type.SIGN_ROUND, "p1", null, "broadcast-payload");
        realGrpcStub.send(broadcast);

        // p2 和 p3 应该都能收到来自 p1 的消息
        MpcMessage recv2 = realGrpcStub.receive("session-bc", 1, "p1", 2000);
        assertNotNull(recv2, "p2 should receive broadcast");
        assertEquals(recv2.getPayloadHex(), "broadcast-payload");
    }

    /**
     * 真实 gRPC 模式：close 关闭所有 channel。
     */
    @Test
    public void testRealGrpcClose() throws Exception {
        realGrpcStub = new GrpcMpcTransportStub(true, 5000, true);
        server = new MpcTransportGrpcServer(realGrpcStub, TEST_SERVER_PORT + 3, true);
        server.start();

        List<MpcParticipant> participants = List.of(
                new MpcParticipant("p1", "localhost:" + (TEST_SERVER_PORT + 3), "pk1"));
        realGrpcStub.connect(participants);
        assertTrue(realGrpcStub.isConnected());
        assertEquals(1, realGrpcStub.getChannels().size());

        realGrpcStub.close();
        assertFalse(realGrpcStub.isConnected(), "should be disconnected after close");
        assertEquals(0, realGrpcStub.getChannels().size());
    }

    /**
     * 真实 gRPC 模式：deliverLocal 投递消息到本地邮箱，receive 取出。
     */
    @Test
    public void testDeliverLocalAndReceive() {
        realGrpcStub = new GrpcMpcTransportStub(true, 5000, true);
        // connect 一个假 participant（gRPC channel 是惰性连接，endpoint 不需要可达）
        List<MpcParticipant> participants = List.of(
                new MpcParticipant("self", "localhost:51999", "pk-self"),
                new MpcParticipant("remote", "localhost:51999", "pk-remote"));
        realGrpcStub.connect(participants);

        MpcMessage msg = MpcMessage.create("session-dl", 2,
                MpcMessage.Type.AGGREGATE_ROUND, "remote", "self", "agg-payload");
        boolean delivered = realGrpcStub.deliverLocal(msg);
        assertTrue(delivered, "first delivery should succeed");

        // 重复投递应被去重
        boolean duplicate = realGrpcStub.deliverLocal(msg);
        assertFalse(duplicate, "duplicate delivery should be ignored");

        MpcMessage received = realGrpcStub.receive("session-dl", 2, "remote", 1000);
        assertNotNull(received);
        assertEquals(received.getPayloadHex(), "agg-payload");
    }
}
