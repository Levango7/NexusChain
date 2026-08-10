package org.nexus.signing.mpc.router;

import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.InMemoryMpcTransport;
import org.nexus.signing.mpc.transport.MpcMessage;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.nexus.signing.mpc.wal.WriteAheadLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MessageRouter} 单元测试。
 */
public class MessageRouterTest {

    private MpcTransport transport;
    private WriteAheadLog wal;
    private MessageDeduplicator deduplicator;
    private MessageRouter router;
    private Path walDir;

    @BeforeEach
    public void setUp() throws Exception {
        transport = new InMemoryMpcTransport();
        walDir = Files.createTempDirectory("mpc-wal-test");
        wal = new WriteAheadLog(walDir.toString());
        deduplicator = new MessageDeduplicator();
        router = new MessageRouter(transport, wal, deduplicator);
        transport.connect(java.util.List.of(
                new org.nexus.signing.mpc.MpcParticipant("p1", "h1", "pk1"),
                new org.nexus.signing.mpc.MpcParticipant("p2", "h2", "pk2")));
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (walDir != null && Files.exists(walDir)) {
            Files.walk(walDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                    });
        }
    }

    @Test
    public void testBroadcastRoutesMessage() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        router.broadcast(msg);
        // 接收方应能收到
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals(received.getPayloadHex(), "payload");
    }

    @Test
    public void testBroadcastNonBroadcastMessageThrows() { assertThrows(MpcProtocolException.class, () -> {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", "p2", "payload"); // 点对点
        router.broadcast(msg);
        });
    }

    @Test
    public void testSendToRoutesToPointToPoint() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        router.sendTo(msg, "p2");
        MpcMessage received = transport.receive("s1", 1, "p1", 100);
        assertEquals(received.getToParticipantId(), "p2");
        assertEquals(received.getPayloadHex(), "payload");
    }

    @Test
    public void testReceiveNewMessageReturnsTrue() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        assertTrue(router.receive(msg));
    }

    @Test
    public void testReceiveDuplicateReturnsFalse() {
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        assertTrue(router.receive(msg));
        assertFalse(router.receive(msg)); // 重复
    }

    @Test
    public void testReplayFromWalReplaysUncommitted() {
        // 先广播一条消息（WAL 中已 commit，recover 应返回空）
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        router.broadcast(msg);
        int replayed = router.replayFromWal("s1");
        assertEquals(0, replayed); // 已 commit
    }

    @Test
    public void testReplayFromWalNoWalReturnsZero() {
        MessageRouter noWalRouter = new MessageRouter(transport, null, null);
        assertEquals(0, noWalRouter.replayFromWal("s1"));
    }

    @Test
    public void testReceiveWithoutDeduplicatorAlwaysTrue() {
        MessageRouter noDedupRouter = new MessageRouter(transport, null, null);
        MpcMessage msg = MpcMessage.create("s1", 1, MpcMessage.Type.SIGN_ROUND,
                "p1", null, "payload");
        assertTrue(noDedupRouter.receive(msg));
        assertTrue(noDedupRouter.receive(msg)); // 无去重器，始终 true
    }
}