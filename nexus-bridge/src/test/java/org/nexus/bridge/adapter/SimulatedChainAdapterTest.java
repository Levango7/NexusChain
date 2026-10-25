package org.nexus.bridge.adapter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模拟链适配器测试（PLAN-037 切片 3）。
 *
 * <p>锁定：伪哈希格式（0x + 32 字节 hex）/ 同内容不同序号派生出不同哈希 /
 * 回执可查（已提交）与 null（未知）/ 模拟高度=已提交交易数。</p>
 */
class SimulatedChainAdapterTest {

    @Test
    void sendTransactionReturnsHexHashAndQueryableReceipt() {
        SimulatedChainAdapter adapter = new SimulatedChainAdapter("L2-sim");
        byte[] tx = new byte[]{1, 2, 3};

        String h1 = adapter.sendTransaction(tx);
        String h2 = adapter.sendTransaction(tx);

        assertNotNull(h1);
        assertTrue(h1.startsWith("0x"), "应为 EVM 风格 hex 哈希：" + h1);
        assertEquals(66, h1.length(), "0x + 64 hex 字符");
        assertNotEquals(h1, h2, "同 tx 不同序号应派生出不同哈希");
        assertEquals(2L, adapter.getBlockHeight(), "模拟高度=已提交交易数");

        Object receipt = adapter.getTransactionReceipt(h1);
        assertNotNull(receipt);
        assertTrue(receipt instanceof Map);
        assertEquals("0x1", ((Map<?, ?>) receipt).get("status"), "模拟回执状态应为成功");
        assertNull(adapter.getTransactionReceipt("0xdeadbeef"), "未知哈希回执为 null");
        assertNull(adapter.getTransactionReceipt(null), "null 入参回执为 null");
    }

    @Test
    void chainIdPassthroughAndCallContract() {
        SimulatedChainAdapter adapter = new SimulatedChainAdapter("L3-sim");
        assertEquals("L3-sim", adapter.getChainId());
        assertEquals(0L, adapter.getBlockHeight(), "未提交交易时模拟高度为 0");
        assertEquals("0x", adapter.callContract("0xaddr", "0xdata"));
    }
}
