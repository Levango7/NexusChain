package org.nexus.p2p;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Peer} 单元测试。
 */
class PeerTest {

    @Test
    void emptyCreatesPeerWithDefaults() {
        Peer p = Peer.empty();
        assertEquals("localhost", p.host);
        assertTrue(p.port > 0);
        assertNotNull(p.peerID);
        assertEquals(32, p.peerID.length);
    }

    @Test
    void hostPortReturnsHostColonPort() {
        Peer p = Peer.empty();
        p.host = "1.2.3.4";
        p.port = 9999;
        assertEquals("1.2.3.4:9999", p.hostPort());
    }

    @Test
    void keyReturnsHexOfPeerId() {
        Peer p = Peer.empty();
        String key = p.key();
        assertNotNull(key);
        // 32 bytes → 64 hex chars
        assertEquals(64, key.length());
    }

    @Test
    void toStringContainsProtocolAndHost() {
        Peer p = Peer.empty();
        p.host = "example.com";
        p.port = 1234;
        String s = p.toString();
        assertTrue(s.contains("nexus://"));
        assertTrue(s.contains("example.com:1234"));
    }

    @Test
    void equalsByPeerId() {
        Peer a = Peer.empty();
        Peer b = Peer.empty();
        b.peerID = Arrays.copyOf(a.peerID, a.peerID.length);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void notEqualsByDifferentPeerId() {
        Peer a = Peer.empty();
        Peer b = Peer.empty();
        b.peerID = new byte[32];
        b.peerID[0] = (byte) 0xff;
        assertNotEquals(a, b);
    }

    @Test
    void distanceZeroForSamePeerId() {
        Peer a = Peer.empty();
        Peer b = Peer.empty();
        b.peerID = Arrays.copyOf(a.peerID, a.peerID.length);
        assertEquals(0, a.distance(b));
    }

    @Test
    void distancePositiveForDifferentPeerId() {
        Peer a = Peer.empty();
        Peer b = Peer.empty();
        b.peerID = new byte[32];
        b.peerID[0] = (byte) 0xff;
        assertTrue(a.distance(b) > 0);
    }

    @Test
    void subTreeZeroForSamePeerId() {
        Peer a = Peer.empty();
        Peer b = Peer.empty();
        b.peerID = Arrays.copyOf(a.peerID, a.peerID.length);
        assertEquals(0, a.subTree(b));
    }

    @Test
    void subTreePositiveForDifferentPeerId() {
        Peer a = Peer.empty(); // peerID = all zeros
        Peer b = Peer.empty();
        b.peerID = new byte[32];
        b.peerID[0] = (byte) 0x80; // high bit set
        assertTrue(a.subTree(b) >= 0);
    }

    @Test
    void protocolNameIsNexus() {
        assertEquals("nexus", Peer.PROTOCOL_NAME);
    }
}