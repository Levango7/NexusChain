package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link InMemoryMpcTransport} 单元测试。
 */
public class InMemoryMpcTransportTest {

    private InMemoryMpcTransport transport = new InMemoryMpcTransport();

    @AfterEach
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

    @Test
    public void testConnectEmptyParticipantsThrows() { assertThrows(MpcProtocolException.class, () -> {
        transport.connect(List.of());
        });
    }

    @Test
    public void testConnectNullParticipantsThrows() { assertThrows(MpcProtocolException.class, () -> {
        transport.connect(null);
        });
    }

    @Test
    public void testSendBroadcastDeliversToAllOthers() {
        transport.connect(twoParticipants());
        MpcMessage broadcast = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        transport.send(broadcast);
        // p2 应能收到
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals(received.getPayloadHex(), "payload");
    }

    @Test
    public void testSendPointToPoint() {
        transport.connect(twoParticipants());
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "p2", "payload-p2p");
        transport.send(msg);
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals(received.getPayloadHex(), "payload-p2p");
    }

    @Test
    public void testSendToUnknownParticipantThrows() { assertThrows(MpcProtocolException.class, () -> {
        transport.connect(twoParticipants());
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "unknown", "payload");
        transport.send(msg);
        });
    }

    @Test
    public void testSendBeforeConnectThrows() { assertThrows(MpcProtocolException.class, () -> {
        InMemoryMpcTransport t = new InMemoryMpcTransport();
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        t.send(msg);
        });
    }

    @Test
    public void testReceiveTimeoutThrows() { assertThrows(MpcProtocolException.class, () -> {
        transport.connect(twoParticipants());
        transport.receive("s1", 1, "p1", 50); // 无消息 → 超时
        });
    }

    @Test
    public void testReceiveBeforeConnectThrows() { assertThrows(MpcProtocolException.class, () -> {
        InMemoryMpcTransport t = new InMemoryMpcTransport();
        t.receive("s1", 1, "p1", 50);
        });
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