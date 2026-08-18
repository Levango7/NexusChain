package org.nexus.p2p;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PeersCacheWrapper} 单元测试。
 * <p>
 * 验证读写锁委托：所有公共方法在加锁状态下正确委托给 PeersCache。
 */
class PeersCacheWrapperTest {

    private Peer self;
    private PeersCacheWrapper wrapper;

    @BeforeEach
    void setUp() throws Exception {
        self = PeerTestFixture.newZeroPeer("127.0.0.1", 9000);
        wrapper = new PeersCacheWrapper(self.toString(), "", "", true);
    }

    @Test
    void constructorInitializesWithSelf() {
        assertEquals(self, wrapper.getSelf());
    }

    @Test
    void sizeDelegatesToSuper() {
        assertEquals(0, wrapper.size());
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        assertEquals(1, wrapper.size());
    }

    @Test
    void hasPeerDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        assertFalse(wrapper.hasPeer(p));
        wrapper.keepPeer(p);
        assertTrue(wrapper.hasPeer(p));
    }

    @Test
    void pendDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.pend(p);
        assertEquals(1, wrapper.getPended().size());
    }

    @Test
    void keepPeerDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        assertTrue(wrapper.hasPeer(p));
    }

    @Test
    void removePeerDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        wrapper.removePeer(p);
        assertFalse(wrapper.hasPeer(p));
    }

    @Test
    void blockPeerDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        wrapper.blockPeer(p);
        assertEquals(1, wrapper.getBlocked().size());
    }

    @Test
    void halfPeerDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        int before = p.score;
        wrapper.half(p);
        assertEquals(before / 2, p.score);
    }

    @Test
    void halfAllDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        int before = p.score;
        wrapper.half();
        assertEquals(before / 2, p.score);
    }

    @Test
    void getPeersDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.keepPeer(p);
        List<Peer> peers = wrapper.getPeers();
        assertEquals(1, peers.size());
    }

    @Test
    void getPeersWithLimitDelegatesToSuper() {
        for (int i = 0; i < 3; i++) {
            Peer p = PeerTestFixture.newPeerWithByte(0, (byte) (i + 1), "10.0.0." + i, 9000 + i);
            wrapper.keepPeer(p);
        }
        List<Peer> limited = wrapper.getPeers(2);
        assertTrue(limited.size() <= 2);
    }

    @Test
    void popPendedDelegatesToSuper() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        wrapper.pend(p);
        List<Peer> popped = wrapper.popPended();
        assertEquals(1, popped.size());
        assertEquals(0, wrapper.popPended().size());
    }

    @Test
    void isFullDelegatesToSuper() {
        assertFalse(wrapper.isFull());
    }

    @Test
    void getBootstrapsDelegatesToSuper() throws Exception {
        Peer bs = PeerTestFixture.newPeerWithByte(0, (byte) 0x80, "1.2.3.4", 9001);
        PeersCacheWrapper w = new PeersCacheWrapper(self.toString(), bs.toString(), "", true);
        List<Peer> bootstraps = w.getBootstraps();
        assertEquals(1, bootstraps.size());
        assertEquals(bs, bootstraps.get(0));
    }

    @Test
    void getUnresolvedDelegatesToSuper() {
        List<HostPort> unresolved = wrapper.getUnresolved();
        assertNotNull(unresolved);
        assertEquals(0, unresolved.size());
    }

    @Test
    void concurrentAccessDoesNotDeadlock() throws Exception {
        // 简单并发测试：多线程同时读写不应死锁
        final Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        Thread t1 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                wrapper.keepPeer(p);
                wrapper.hasPeer(p);
            }
        });
        Thread t2 = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                wrapper.getPeers();
                wrapper.size();
            }
        });
        t1.start();
        t2.start();
        t1.join(5000);
        t2.join(5000);
        assertFalse(t1.isAlive(), "t1 should not deadlock");
        assertFalse(t2.isAlive(), "t2 should not deadlock");
    }
}