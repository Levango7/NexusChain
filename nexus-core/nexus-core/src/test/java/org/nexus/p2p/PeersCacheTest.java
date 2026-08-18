package org.nexus.p2p;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PeersCache} 单元测试。
 * <p>
 * 覆盖 K-bucket 分桶、MAX_PEERS 上限、half 衰减、block/remove/pend 等核心路径。
 */
class PeersCacheTest {

    private Peer self;
    private PeersCache cache;

    @BeforeEach
    void setUp() throws Exception {
        // self peerID 全 0，便于按 byteIdx 构造对端 subTree
        self = PeerTestFixture.newZeroPeer("127.0.0.1", 9000);
        cache = new PeersCache(self.toString(), "", "", true);
    }

    @Test
    void constructorWithEmptyBootstrapsAndTrusted() throws Exception {
        PeersCache c = new PeersCache(self.toString(), "", "", false);
        assertEquals(0, c.size());
        assertEquals(0, c.getBootstraps().size());
        assertEquals(0, c.getTrusted().size());
        assertEquals(0, c.getUnresolved().size());
        assertEquals(self, c.getSelf());
    }

    @Test
    void initBootstrapsParsesValidPeer() throws Exception {
        Peer bootstrap = PeerTestFixture.newPeerWithByte(0, (byte) 0x80, "1.2.3.4", 9001);
        PeersCache c = new PeersCache(self.toString(), bootstrap.toString(), "", true);
        List<Peer> bs = c.getBootstraps();
        assertEquals(1, bs.size());
        assertEquals(bootstrap, bs.get(0));
    }

    @Test
    void initBootstrapsHandlesNullGracefully() throws Exception {
        PeersCache c = new PeersCache(self.toString(), null, null, true);
        assertEquals(0, c.getBootstraps().size());
        assertEquals(0, c.getTrusted().size());
    }

    @Test
    void initTrustedRejectsSelfAsTrusted() {
        assertThrows(Exception.class,
                () -> new PeersCache(self.toString(), "", self.toString(), true));
    }

    @Test
    void initTrustedParsesValidPeer() throws Exception {
        Peer trusted = PeerTestFixture.newPeerWithByte(0, (byte) 0x40, "5.6.7.8", 9002);
        PeersCache c = new PeersCache(self.toString(), "", trusted.toString(), true);
        assertEquals(1, c.getTrusted().size());
        assertEquals(trusted, c.getTrusted().get(0));
        // trusted 计入 size
        assertEquals(1, c.size());
    }

    @Test
    void hasPeerReturnsTrueForTrusted() throws Exception {
        Peer trusted = PeerTestFixture.newPeerWithByte(0, (byte) 0x40, "5.6.7.8", 9002);
        PeersCache c = new PeersCache(self.toString(), "", trusted.toString(), true);
        assertTrue(c.hasPeer(trusted));
    }

    @Test
    void hasPeerReturnsFalseForUnknownPeer() {
        Peer unknown = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "9.9.9.9", 9999);
        assertFalse(cache.hasPeer(unknown));
    }

    @Test
    void pendIgnoresLocalhost() {
        Peer local = PeerTestFixture.newZeroPeer("localhost", 1234);
        cache.pend(local);
        assertEquals(0, cache.getPended().size());
    }

    @Test
    void pendIgnoresLoopback127() {
        Peer local = PeerTestFixture.newZeroPeer("127.0.0.1", 1234);
        cache.pend(local);
        assertEquals(0, cache.getPended().size());
    }

    @Test
    void pendIgnoresSelf() {
        cache.pend(self);
        assertEquals(0, cache.getPended().size());
    }

    @Test
    void pendAddsValidPeer() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.pend(p);
        List<Peer> pended = cache.getPended();
        assertEquals(1, pended.size());
        assertEquals(p, pended.get(0));
    }

    @Test
    void pendDoesNotDuplicate() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.pend(p);
        cache.pend(p);
        assertEquals(1, cache.getPended().size());
    }

    @Test
    void popPendedClearsPended() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.pend(p);
        List<Peer> popped = cache.popPended();
        assertEquals(1, popped.size());
        assertEquals(0, cache.popPended().size());
    }

    @Test
    void keepPeerAddsNewPeerWhenNotFull() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        assertTrue(cache.hasPeer(p));
        assertEquals(1, cache.size());
    }

    @Test
    void keepPeerIgnoresSelf() {
        cache.keepPeer(self);
        assertEquals(0, cache.size());
    }

    @Test
    void keepPeerAccumulatesScoreForExistingPeer() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        int scoreAfterFirst = p.score;
        cache.keepPeer(p);
        assertTrue(p.score > scoreAfterFirst);
    }

    @Test
    void removePeerRemovesKnownPeer() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        assertTrue(cache.hasPeer(p));
        cache.removePeer(p);
        assertFalse(cache.hasPeer(p));
    }

    @Test
    void removePeerNoOpForUnknownPeer() {
        Peer unknown = PeerTestFixture.newPeerWithByte(0, (byte) 0x02, "10.0.0.2", 9002);
        cache.removePeer(unknown);
        assertEquals(0, cache.size());
    }

    @Test
    void removePeerNoOpWhenDiscoveryDisabled() throws Exception {
        PeersCache c = new PeersCache(self.toString(), "", "", false);
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        c.removePeer(p);
        assertEquals(0, c.size());
    }

    @Test
    void blockPeerAddsToBlockedAndRemovesFromCache() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        cache.blockPeer(p);
        List<Peer> blocked = cache.getBlocked();
        assertEquals(1, blocked.size());
        assertEquals(p, blocked.get(0));
        assertFalse(cache.hasPeer(p));
        assertTrue(p.score < 0);
    }

    @Test
    void halfPeerHalvesScore() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        int before = p.score;
        cache.half(p);
        assertEquals(before / 2, p.score);
    }

    @Test
    void halfPeerRemovesPeerWhenScoreReachesZero() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        cache.keepPeer(p);
        // 反复衰减直至 0
        for (int i = 0; i < 10; i++) {
            cache.half(p);
        }
        assertFalse(cache.hasPeer(p));
    }

    @Test
    void halfAllDecaysAllPeerScores() {
        Peer p1 = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        Peer p2 = PeerTestFixture.newPeerWithByte(0, (byte) 0x02, "10.0.0.2", 9002);
        cache.keepPeer(p1);
        cache.keepPeer(p2);
        int s1 = p1.score;
        int s2 = p2.score;
        cache.half();
        assertEquals(s1 / 2, p1.score);
        assertEquals(s2 / 2, p2.score);
    }

    @Test
    void getPeersReturnsTrustedAndBootstrapsWhenDiscoveryDisabled() throws Exception {
        Peer bootstrap = PeerTestFixture.newPeerWithByte(0, (byte) 0x80, "1.2.3.4", 9001);
        Peer trusted = PeerTestFixture.newPeerWithByte(0, (byte) 0x40, "5.6.7.8", 9002);
        PeersCache c = new PeersCache(self.toString(), bootstrap.toString(), trusted.toString(), false);
        List<Peer> peers = c.getPeers();
        assertEquals(2, peers.size());
        assertTrue(peers.contains(bootstrap));
        assertTrue(peers.contains(trusted));
    }

    @Test
    void getPeersWithLimitTruncatesResult() {
        for (int i = 0; i < 5; i++) {
            Peer p = PeerTestFixture.newPeerWithByte(0, (byte) (i + 1), "10.0.0." + i, 9000 + i);
            cache.keepPeer(p);
        }
        List<Peer> limited = cache.getPeers(2);
        assertTrue(limited.size() <= 2);
    }

    @Test
    void isFullReturnsFalseWhenBelowMaxPeers() {
        assertFalse(cache.isFull());
    }

    @Test
    void getSelfReturnsSelfPeer() {
        assertEquals(self, cache.getSelf());
    }
}