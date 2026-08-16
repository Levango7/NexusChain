package org.nexus.consortium;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PeerServerProperties 单元测试。
 * 覆盖 Properties 继承行为。
 */
public class PeerServerPropertiesTest {

    @Test
    public void testInheritance() {
        PeerServerProperties props = new PeerServerProperties();
        assertTrue(props instanceof Properties);
    }

    @Test
    public void testSetAndGet() {
        PeerServerProperties props = new PeerServerProperties();
        props.setProperty("max-peers", "50");
        assertEquals("50", props.getProperty("max-peers"));
    }

    @Test
    public void testEmptyProperties() {
        PeerServerProperties props = new PeerServerProperties();
        assertTrue(props.isEmpty());
    }

    @Test
    public void testPutAndGet() {
        PeerServerProperties props = new PeerServerProperties();
        props.put("address", "node://localhost:9000");
        assertEquals("node://localhost:9000", props.get("address"));
    }

    @Test
    public void testMultipleProperties() {
        PeerServerProperties props = new PeerServerProperties();
        props.setProperty("max-peers", "32");
        props.setProperty("max-ttl", "8");
        props.setProperty("enable-discovery", "true");
        assertEquals(3, props.size());
        assertEquals("32", props.getProperty("max-peers"));
        assertEquals("8", props.getProperty("max-ttl"));
        assertEquals("true", props.getProperty("enable-discovery"));
    }
}