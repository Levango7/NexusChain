package org.nexus.signing.mpc.barrier;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link RoundTimeoutConfig} 单元测试。
 */
public class RoundTimeoutConfigTest {

    @Test
    public void testDefaultTimeout() {
        RoundTimeoutConfig config = new RoundTimeoutConfig(5000);
        assertEquals(5000, config.getDefaultTimeoutMillis());
        assertEquals(5000, config.getTimeout(1));
        assertEquals(5000, config.getTimeout(99));
    }

    @Test
    public void testWithRoundTimeout() {
        RoundTimeoutConfig config = new RoundTimeoutConfig(5000)
                .withRoundTimeout(1, 1000)
                .withRoundTimeout(2, 2000);
        assertEquals(1000, config.getTimeout(1));
        assertEquals(2000, config.getTimeout(2));
        assertEquals(5000, config.getTimeout(3)); // 默认
    }

    @Test
    public void testGetPerRoundTimeoutsSnapshot() {
        RoundTimeoutConfig config = new RoundTimeoutConfig(5000)
                .withRoundTimeout(1, 1000)
                .withRoundTimeout(2, 2000);
        Map<Integer, Long> snapshot = config.getPerRoundTimeouts();
        assertEquals(2, snapshot.size());
        assertEquals(Long.valueOf(1000), snapshot.get(1));
        assertEquals(Long.valueOf(2000), snapshot.get(2));
    }

    @Test
    public void testValidateValidTimeout() {
        RoundTimeoutConfig config = new RoundTimeoutConfig(5000);
        config.validate(1); // 不抛异常
        config.validate(99);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidDefaultTimeoutZeroThrows() {
        new RoundTimeoutConfig(0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidDefaultTimeoutNegativeThrows() {
        new RoundTimeoutConfig(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithRoundTimeoutZeroThrows() {
        new RoundTimeoutConfig(5000).withRoundTimeout(1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testWithRoundTimeoutNegativeThrows() {
        new RoundTimeoutConfig(5000).withRoundTimeout(1, -1);
    }
}