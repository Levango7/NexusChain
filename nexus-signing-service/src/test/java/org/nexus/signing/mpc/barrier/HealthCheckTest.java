package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HealthCheck} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
public class HealthCheckTest {

    @Mock
    private MpcTransport transport;

    private HealthCheck healthCheck;

    @AfterEach
    public void tearDown() {
        if (healthCheck != null) {
            healthCheck.stop();
        }
    }

    private List<MpcParticipant> threeParticipants() {
        return List.of(
                new MpcParticipant("p1", "h1", "pk1"),
                new MpcParticipant("p2", "h2", "pk2"),
                new MpcParticipant("p3", "h3", "pk3"));
    }

    @Test
    public void testInitiallyAllAlive() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 120);
        assertTrue(healthCheck.isAlive("p1"));
        assertTrue(healthCheck.isAlive("p2"));
        assertTrue(healthCheck.isAlive("p3"));
        assertEquals(3, healthCheck.getAliveCount());
    }

    @Test
    public void testIsAliveUnknownReturnsFalse() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 120);
        assertFalse(healthCheck.isAlive("unknown"));
    }

    @Test
    public void testRecordHeartbeatUpdatesLastSeen() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 1);
        // 等待超过 1 秒超时窗口
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        assertFalse(healthCheck.isAlive("p2"));
        // 记录心跳后应再次 alive
        healthCheck.recordHeartbeat("p2");
        assertTrue(healthCheck.isAlive("p2"));
    }

    @Test
    public void testMarkReconnectResetsHeartbeat() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 1);
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        assertFalse(healthCheck.isAlive("p2"));
        healthCheck.markReconnect("p2");
        assertTrue(healthCheck.isAlive("p2"));
    }

    @Test
    public void testStartAndStopIdempotent() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 120);
        healthCheck.start();
        healthCheck.start(); // 重复 start 应无效果
        healthCheck.stop();
        // stop 后再 stop 不抛异常
    }

    @Test
    public void testGetAliveCountAfterTimeout() {
        healthCheck = new HealthCheck(transport, threeParticipants(), "p1", 60, 1);
        try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
        // 全部超时
        assertEquals(0, healthCheck.getAliveCount());
    }
}