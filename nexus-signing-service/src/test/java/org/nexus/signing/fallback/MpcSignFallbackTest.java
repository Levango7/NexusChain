package org.nexus.signing.fallback;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import org.junit.jupiter.api.Test;
import org.nexus.signing.mpc.MpcKeyShare;
import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.MpcSigningSession;
import org.nexus.signing.mpc.ThresholdPolicy;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MpcSignFallback} 单元测试。
 *
 * <p>覆盖 {@link MpcSignFallback#runSigningRoundsFallback}：
 * <ul>
 *   <li>FlowException → 标记 session FAILED + 抛出 MpcProtocolException</li>
 *   <li>DegradeException → 标记 session FAILED + 抛出 MpcProtocolException</li>
 *   <li>未知 BlockException → 标记 session FAILED + 抛出 MpcProtocolException</li>
 *   <li>session 为 null → 不抛标记异常，但仍抛 MpcProtocolException</li>
 * </ul></p>
 */
public class MpcSignFallbackTest {

    private MpcSigningSession newSession() {
        return new MpcSigningSession(
                "s1", "w1", "tx-hex",
                new ThresholdPolicy(2, 3),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2"),
                        new MpcParticipant("p3", "h3", "pk3")));
    }

    private List<MpcKeyShare> newShares() {
        return List.of(
                new MpcKeyShare("p1", "share1", "pubshare1", "paillier1"),
                new MpcKeyShare("p2", "share2", "pubshare2", "paillier2"));
    }

    @Test
    public void testRunSigningRoundsFallback_flowException_throws() { assertThrows(MpcProtocolException.class, () -> {
        MpcSigningSession session = newSession();
        MpcSignFallback.runSigningRoundsFallback(session, newShares(), new FlowException("flow"));
        });
    }

    @Test
    public void testRunSigningRoundsFallback_degradeException_throws() { assertThrows(MpcProtocolException.class, () -> {
        MpcSigningSession session = newSession();
        MpcSignFallback.runSigningRoundsFallback(session, newShares(), new DegradeException("degrade"));
        });
    }

    @Test
    public void testRunSigningRoundsFallback_unknownException_throws() { assertThrows(MpcProtocolException.class, () -> {
        BlockException unknown = new BlockException("unknown") {};
        MpcSigningSession session = newSession();
        MpcSignFallback.runSigningRoundsFallback(session, newShares(), unknown);
        });
    }

    @Test
    public void testRunSigningRoundsFallback_nullSession_throws() { assertThrows(MpcProtocolException.class, () -> {
        MpcSignFallback.runSigningRoundsFallback(null, newShares(), new FlowException("flow"));
        });
    }

    @Test
    public void testRunSigningRoundsFallback_nullShares_throws() {
        MpcSigningSession session = newSession();
        try {
            MpcSignFallback.runSigningRoundsFallback(session, null, new FlowException("flow"));
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            assertEquals(MpcProtocolException.Reason.ILLEGAL_STATE, e.getReason());
        }
    }

    @Test
    public void testRunSigningRoundsFallback_flowException_sessionMarkedFailed() {
        MpcSigningSession session = newSession();
        try {
            MpcSignFallback.runSigningRoundsFallback(session, newShares(), new FlowException("flow"));
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            assertEquals(MpcProtocolException.Reason.ILLEGAL_STATE, e.getReason());
        }
        // session 应被标记为 FAILED
        assertEquals(MpcSigningSession.SessionStatus.FAILED, session.getStatus());
    }

    @Test
    public void testRunSigningRoundsFallback_degradeException_sessionMarkedFailed() {
        MpcSigningSession session = newSession();
        try {
            MpcSignFallback.runSigningRoundsFallback(session, newShares(), new DegradeException("degrade"));
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            assertEquals(MpcProtocolException.Reason.ILLEGAL_STATE, e.getReason());
        }
        assertEquals(MpcSigningSession.SessionStatus.FAILED, session.getStatus());
    }

    @Test
    public void testRunSigningRoundsFallback_nullSession_exceptionContainsReason() {
        try {
            MpcSignFallback.runSigningRoundsFallback(null, newShares(), new FlowException("flow"));
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            assertEquals(MpcProtocolException.Reason.ILLEGAL_STATE, e.getReason());
            // 异常消息包含 FLOW_LIMIT
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("FLOW_LIMIT"));
        }
    }

    @Test
    public void testRunSigningRoundsFallback_degradeException_exceptionContainsReason() {
        try {
            MpcSignFallback.runSigningRoundsFallback(null, newShares(), new DegradeException("degrade"));
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            // DegradeException → SLOW_CALL_CIRCUIT
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("SLOW_CALL_CIRCUIT"));
        }
    }

    @Test
    public void testRunSigningRoundsFallback_unknownException_exceptionContainsUnknown() {
        BlockException unknown = new BlockException("unknown") {};
        try {
            MpcSignFallback.runSigningRoundsFallback(null, newShares(), unknown);
            fail("Expected MpcProtocolException");
        } catch (MpcProtocolException e) {
            org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("UNKNOWN"));
        }
    }
}