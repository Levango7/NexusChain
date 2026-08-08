package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link GrpcMpcTransportStub} 单元测试。
 */
public class GrpcMpcTransportStubTest {

    private GrpcMpcTransportStub stub = new GrpcMpcTransportStub();

    @After
    public void tearDown() {
        stub.close();
    }

    private List<MpcParticipant> twoParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"));
    }

    @Test
    public void testDefaultConstructorUsesFallback() {
        assertFalse(stub.isConnected());
        stub.connect(twoParticipants());
        assertTrue(stub.isConnected());
    }

    @Test
    public void testRealGrpcEnabledConstructorStillFallback() {
        GrpcMpcTransportStub s = new GrpcMpcTransportStub(true);
        s.connect(twoParticipants());
        assertTrue(s.isConnected());
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
        assertEquals("payload", received.getPayloadHex());
    }

    @Test
    public void testCloseClearsChannels() {
        stub.connect(twoParticipants());
        assertEquals(2, stub.getChannels().size());
        stub.close();
        assertEquals(0, stub.getChannels().size());
        assertFalse(stub.isConnected());
    }
}