package org.nexus.consortium;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConsensusProperties 单元测试。
 * 覆盖 Properties 继承行为。
 */
public class ConsensusPropertiesTest {

    @Test
    public void testInheritance() {
        ConsensusProperties props = new ConsensusProperties();
        assertTrue(props instanceof Properties);
    }

    @Test
    public void testSetAndGet() {
        ConsensusProperties props = new ConsensusProperties();
        props.setProperty("name", "poa");
        assertEquals("poa", props.getProperty("name"));
    }

    @Test
    public void testGetPropertyDefault() {
        ConsensusProperties props = new ConsensusProperties();
        assertEquals("default", props.getProperty("nonexistent", "default"));
    }

    @Test
    public void testEmptyProperties() {
        ConsensusProperties props = new ConsensusProperties();
        assertTrue(props.isEmpty());
    }

    @Test
    public void testPutAndGet() {
        ConsensusProperties props = new ConsensusProperties();
        props.put("key1", "value1");
        assertEquals("value1", props.get("key1"));
    }
}