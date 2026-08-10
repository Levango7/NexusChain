package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.MpcTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * {@link ReconnectStrategy} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
public class ReconnectStrategyTest {

    @Mock
    private MpcTransport transport;

    private final List<MpcParticipant> participants = List.of(
            new MpcParticipant("p1", "h1", "pk1"),
            new MpcParticipant("p2", "h2", "pk2"));

    @Test
    public void testReconnectSuccessOnFirstAttempt() {
        doNothing().when(transport).connect(participants);
        doNothing().when(transport).close();

        ReconnectStrategy strategy = new ReconnectStrategy(transport, null,
                1, 100, 3);
        boolean result = strategy.reconnect(participants);
        assertTrue(result);
        assertEquals(1, strategy.getAttemptCount());
    }

    @Test
    public void testReconnectSuccessWithHealthCheck() {
        doNothing().when(transport).connect(participants);
        doNothing().when(transport).close();

        HealthCheck hc = new HealthCheck(transport, participants, "p1", 60, 120);
        ReconnectStrategy strategy = new ReconnectStrategy(transport, hc,
                1, 100, 3);
        boolean result = strategy.reconnect(participants);
        assertTrue(result);
    }

    @Test
    public void testReconnectAllAttemptsFailThrows() { assertThrows(MpcProtocolException.class, () -> {
        doThrow(new RuntimeException("conn fail")).when(transport).connect(participants);
        doNothing().when(transport).close();

        ReconnectStrategy strategy = new ReconnectStrategy(transport, null,
                1, 10, 2);
        strategy.reconnect(participants);
        });
    }

    @Test
    public void testReconnectSucceedsOnSecondAttempt() {
        // 第一次失败，第二次成功
        doThrow(new RuntimeException("fail first"))
                .doNothing()
                .when(transport).connect(participants);
        doNothing().when(transport).close();

        ReconnectStrategy strategy = new ReconnectStrategy(transport, null,
                1, 10, 3);
        boolean result = strategy.reconnect(participants);
        assertTrue(result);
        assertEquals(2, strategy.getAttemptCount());
    }

    @Test
    public void testGetAttemptCountInitiallyZero() {
        ReconnectStrategy strategy = new ReconnectStrategy(transport, null,
                1, 100, 3);
        assertEquals(0, strategy.getAttemptCount());
    }
}