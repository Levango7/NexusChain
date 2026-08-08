package org.nexus.p2p;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link HostPort} 单元测试。
 */
class HostPortTest {

    @Test
    void constructorSetsFields() {
        HostPort hp = new HostPort("localhost", 8080);
        assertEquals("localhost", hp.getHost());
        assertEquals(8080, hp.getPort());
    }

    @Test
    void settersUpdateFields() {
        HostPort hp = new HostPort("a", 1);
        hp.setHost("b");
        hp.setPort(2);
        assertEquals("b", hp.getHost());
        assertEquals(2, hp.getPort());
    }

    @Test
    void equalsSameHostPortReturnsTrue() {
        HostPort a = new HostPort("h", 80);
        HostPort b = new HostPort("h", 80);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalsDifferentHostReturnsFalse() {
        HostPort a = new HostPort("h1", 80);
        HostPort b = new HostPort("h2", 80);
        assertNotEquals(a, b);
    }

    @Test
    void equalsDifferentPortReturnsFalse() {
        HostPort a = new HostPort("h", 80);
        HostPort b = new HostPort("h", 81);
        assertNotEquals(a, b);
    }

    @Test
    void equalsReflexiveAndNull() {
        HostPort a = new HostPort("h", 80);
        assertEquals(a, a);
        assertNotEquals(a, null);
        assertNotEquals(a, "string");
    }
}