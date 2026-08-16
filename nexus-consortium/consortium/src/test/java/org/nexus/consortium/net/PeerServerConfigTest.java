package org.nexus.consortium.net;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PeerServerConfig 单元测试。
 * 覆盖构造器、builder、createDefault、常量。
 */
public class PeerServerConfigTest {

    @Test
    public void testDefaultConstructor() {
        PeerServerConfig config = new PeerServerConfig();
        assertNotNull(config);
        assertNull(config.getName());
        assertEquals(0, config.getMaxPeers());
        assertEquals(0L, config.getMaxTTL());
        assertFalse(config.isEnableDiscovery());
        assertNull(config.getAddress());
        assertNull(config.getBootstraps());
        assertNull(config.getTrusted());
    }

    @Test
    public void testBuilder() {
        PeerServerConfig config = PeerServerConfig.builder()
                .name("node1")
                .maxPeers(50)
                .maxTTL(10L)
                .enableDiscovery(true)
                .address("node://localhost:9000")
                .bootstraps(Collections.emptyList())
                .trusted(Collections.emptyList())
                .build();
        assertEquals("node1", config.getName());
        assertEquals(50, config.getMaxPeers());
        assertEquals(10L, config.getMaxTTL());
        assertTrue(config.isEnableDiscovery());
        assertEquals("node://localhost:9000", config.getAddress());
        assertNotNull(config.getBootstraps());
        assertNotNull(config.getTrusted());
    }

    @Test
    public void testCreateDefault() {
        PeerServerConfig config = PeerServerConfig.createDefault();
        assertNotNull(config);
        assertEquals(PeerServerConfig.DEFAULT_MAX_PEERS, config.getMaxPeers());
        assertEquals(PeerServerConfig.DEFAULT_MAX_TTL, config.getMaxTTL());
    }

    @Test
    public void testDefaultPortConstant() {
        assertTrue(PeerServerConfig.DEFAULT_PORT != 0);
    }

    @Test
    public void testDefaultProtocolConstant() {
        assertEquals("node", PeerServerConfig.DEFAULT_PROTOCOL);
    }

    @Test
    public void testDefaultMaxTtlConstant() {
        assertEquals(8L, PeerServerConfig.DEFAULT_MAX_TTL);
    }

    @Test
    public void testDefaultMaxPeersConstant() {
        assertEquals(32, PeerServerConfig.DEFAULT_MAX_PEERS);
    }

    @Test
    public void testSetters() {
        PeerServerConfig config = new PeerServerConfig();
        config.setName("test");
        config.setMaxPeers(100);
        config.setMaxTTL(20L);
        config.setEnableDiscovery(true);
        config.setAddress("node://host:8080");
        List<URI> bootstraps = Arrays.asList(URI.create("node://peer1:9000"));
        config.setBootstraps(bootstraps);

        assertEquals("test", config.getName());
        assertEquals(100, config.getMaxPeers());
        assertEquals(20L, config.getMaxTTL());
        assertTrue(config.isEnableDiscovery());
        assertEquals("node://host:8080", config.getAddress());
        assertEquals(1, config.getBootstraps().size());
    }

    @Test
    public void testAllArgsConstructor() {
        PeerServerConfig config = new PeerServerConfig(
                "node", 50, 10L, true, "addr", null, null);
        assertEquals("node", config.getName());
        assertEquals(50, config.getMaxPeers());
        assertEquals(10L, config.getMaxTTL());
        assertTrue(config.isEnableDiscovery());
        assertEquals("addr", config.getAddress());
    }
}