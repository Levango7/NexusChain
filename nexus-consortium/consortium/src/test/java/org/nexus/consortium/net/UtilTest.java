package org.nexus.consortium.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Util 单元测试。
 * 覆盖 ping 等网络工具方法。
 */
public class UtilTest {

    @Test
    public void testPingUnreachable() {
        boolean result = Util.ping("192.0.2.1", 12345);
        assertFalse(result);
    }

    @Test
    public void testPingInvalidHost() {
        boolean result = Util.ping("nonexistent.invalid.domain.test", 80);
        assertFalse(result);
    }

    @Test
    public void testPingLocalhostHighPort() {
        boolean result = Util.ping("127.0.0.1", 65530);
        assertFalse(result);
    }
}