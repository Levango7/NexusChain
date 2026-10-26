package org.nexus.bridge.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 模拟链事件源测试（PLAN-046）。
 *
 * <p>锁定：① nonce 从 startNonce 起严格单调、poll 之间不重复；② maxEvents
 * 钳制（&le;0 视为 1）；③ 负载含 nonce（可追溯）；④ 必填字段校验 fail-fast；
 * ⑤ 目标/源链 getter 不产生副作用（状态快照不得偷事件）。</p>
 */
class SimulatedChainEventSourceTest {

    private SimulatedChainEventSource source() {
        return new SimulatedChainEventSource("L1-sim", "L2-sim", "0xsrc", "0xdst", 7L);
    }

    @Test
    void nonceIsMonotonicFromStartNonce() {
        SimulatedChainEventSource source = source();
        assertEquals(7L, source.peekNextNonce());
        ChainEvent first = source.pollEvents(1).get(0);
        assertEquals(7L, first.nonce());
        ChainEvent second = source.pollEvents(1).get(0);
        assertEquals(8L, second.nonce());
        assertEquals(9L, source.peekNextNonce());
    }

    @Test
    void maxEventsClampsAndNonPositiveMeansOne() {
        SimulatedChainEventSource source = source();
        assertEquals(3, source.pollEvents(3).size());
        assertEquals(1, source.pollEvents(0).size(), "maxEvents<=0 视为 1");
        assertEquals(1, source.pollEvents(-5).size());
        // 已产出 3+1+1=5 条（nonce 7..11），下一条为 12
        assertEquals(12L, source.pollEvents(1).get(0).nonce());
    }

    @Test
    void payloadCarriesNonceForTraceability() {
        ChainEvent event = source().pollEvents(1).get(0);
        assertNotNull(event.payload());
        assertTrue(event.payload().encode().byteLength() > 0,
                "encode() 后应可测长度（formatMessage 会做同样的调用）");
        assertEquals(MessagePayload.Type.ARBITRARY, event.payload().getType());
        assertTrue(event.payload().getData().contains("sim-event:L1-sim:7:"),
                "负载必须可追溯到 nonce：" + event.payload().getData());
    }

    @Test
    void requiredFieldsFailFast() {
        // null 与空串同一口径：IllegalArgumentException（不许可缺字段启动）
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedChainEventSource(null, "L2", "0xs", "0xt", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedChainEventSource("", "L2", "0xs", "0xt", 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatedChainEventSource("L1", "L2", "", "0xt", 0));
    }

    @Test
    void chainGettersHaveNoSideEffects() {
        SimulatedChainEventSource source = source();
        assertEquals("L1-sim", source.getSourceChain());
        assertEquals("L2-sim", source.getTargetChain());
        assertEquals("SimulatedChainEventSource", source.describe());
        assertEquals(7L, source.peekNextNonce(), "getter 不得消耗事件");
    }
}
