package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.junit.After;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link InMemoryMpcTransport} 单元测试。
 */
public class InMemoryMpcTransportTest {

    private InMemoryMpcTransport transport = new InMemoryMpcTransport();

    @After
    public void tearDown() {
        transport.close();
    }

    private List<MpcParticipant> twoParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"));
    }

    @Test
    public void testConnectAndIsConnected() {
        assertFalse(transport.isConnected());
        transport.connect(twoParticipants());
        assertTrue(transport.isConnected());
    }

    @Test(expected = MpcProtocolException.class)
    public void testConnectEmptyParticipantsThrows() {
        transport.connect(List.of());
    }

    @Test(expected = MpcProtocolException.class)
    public void testConnectNullParticipantsThrows() {
        transport.connect(null);
    }

    @Test
    public void testSendBroadcastDeliversToAllOthers() {
        transport.connect(twoParticipants());
        MpcMessage broadcast = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        transport.send(broadcast);
        // p2 应能收到
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals("payload", received.getPayloadHex());
    }

    @Test
    public void testSendPointToPoint() {
        transport.connect(twoParticipants());
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "p2", "payload-p2p");
        transport.send(msg);
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals("payload-p2p", received.getPayloadHex());
    }

    @Test(expected = MpcProtocolException.class)
    public void testSendToUnknownParticipantThrows() {
        transport.connect(twoParticipants());
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "unknown", "payload");
        transport.send(msg);
    }

    @Test(expected = MpcProtocolException.class)
    public void testSendBeforeConnectThrows() {
        InMemoryMpcTransport t = new InMemoryMpcTransport();
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        t.send(msg);
    }

    @Test(expected = MpcProtocolException.class)
    public void testReceiveTimeoutThrows() {
        transport.connect(twoParticipants());
        transport.receive("s1", 1, "p1", 50); // 无消息 → 超时
    }

    @Test(expected = MpcProtocolException.class)
    public void testReceiveBeforeConnectThrows() {
        InMemoryMpcTransport t = new InMemoryMpcTransport();
        t.receive("s1", 1, "p1", 50);
    }

    @Test
    public void testCloseClearsState() {
        transport.connect(twoParticipants());
        assertTrue(transport.isConnected());
        transport.close();
        assertFalse(transport.isConnected());
    }

    @Test
    public void testReconnectAfterClose() {
        transport.connect(twoParticipants());
        transport.close();
        assertFalse(transport.isConnected());
        transport.connect(twoParticipants());
        assertTrue(transport.isConnected());
    }
}