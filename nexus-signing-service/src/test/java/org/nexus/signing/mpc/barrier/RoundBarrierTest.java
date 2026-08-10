package org.nexus.signing.mpc.barrier;

import org.nexus.signing.mpc.MpcProtocolException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RoundBarrier} 单元测试。
 */
public class RoundBarrierTest {

    @Test
    public void testArriveAndAwaitImmediatelyWhenQuorumMet() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        barrier.arrive(1, "p1");
        // 阈值 1，已到达 → await 立即返回
        barrier.awaitRound(1, 1000);
        assertEquals(1, barrier.getArrivedCount(1));
    }

    @Test
    public void testArriveMultipleParticipantsThenAwait() {
        RoundBarrier barrier = new RoundBarrier("s1", 3);
        barrier.arrive(1, "p1");
        barrier.arrive(1, "p2");
        barrier.arrive(1, "p3");
        barrier.awaitRound(1, 100);
        assertEquals(3, barrier.getArrivedCount(1));
    }

    @Test
    public void testArriveIdempotentForSameParticipant() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        barrier.arrive(1, "p1");
        barrier.arrive(1, "p1"); // 重复，不应增加计数
        assertEquals(1, barrier.getArrivedCount(1));
    }

    @Test
    public void testAwaitTimeoutThrows() { assertThrows(MpcProtocolException.class, () -> {
        RoundBarrier barrier = new RoundBarrier("s1", 2);
        barrier.arrive(1, "p1");
        // 仅 1 个到达，阈值 2 → 超时
        barrier.awaitRound(1, 50);
        });
    }

    @Test
    public void testGetArrivedCountForUnknownRound() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        assertEquals(0, barrier.getArrivedCount(99));
    }

    @Test
    public void testResetClearsState() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        barrier.arrive(1, "p1");
        assertEquals(1, barrier.getArrivedCount(1));
        barrier.reset();
        assertEquals(0, barrier.getArrivedCount(1));
    }

    @Test
    public void testCrossThreadArriveBeforeAwait() throws InterruptedException {
        RoundBarrier barrier = new RoundBarrier("s1", 2);
        barrier.arrive(1, "p1");
        Thread t = new Thread(() -> {
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
            barrier.arrive(1, "p2");
        });
        t.start();
        barrier.awaitRound(1, 1000);
        t.join();
        assertEquals(2, barrier.getArrivedCount(1));
    }

    @Test
    public void testInvalidThresholdZeroThrows() { assertThrows(IllegalArgumentException.class, () -> {
        new RoundBarrier("s1", 0);
        });
    }

    @Test
    public void testMultipleRoundsIndependent() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        barrier.arrive(1, "p1");
        barrier.awaitRound(1, 100);
        barrier.arrive(2, "p1");
        barrier.awaitRound(2, 100);
        assertEquals(1, barrier.getArrivedCount(1));
        assertEquals(1, barrier.getArrivedCount(2));
    }

    @Test
    public void testAwaitRoundAlreadySatisfiedReturnsImmediately() {
        RoundBarrier barrier = new RoundBarrier("s1", 1);
        barrier.arrive(1, "p1");
        // 多次 await 都应立即返回
        long start = System.currentTimeMillis();
        barrier.awaitRound(1, 1000);
        barrier.awaitRound(1, 1000);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 100, "repeated await should be instant");
    }
}