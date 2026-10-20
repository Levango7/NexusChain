package org.nexus.consensus.finality;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SignatureAggregator.CollectingAggregator} 的打包契约测试。
 *
 * <p>回归背景：最终性投票可能由本地事件线程和 P2P 线程并发提交。旧实现
 * 在“计算签名总长度”和“复制签名”之间对同一个并发列表迭代两次；若两次
 * 迭代之间新增一票，就会按一票的长度分配缓冲区、却复制两票，触发
 * {@code ArrayIndexOutOfBoundsException}。聚合器现在固定一次快照。</p>
 */
public class SignatureAggregatorTest {

    @Test
    public void collectingAggregatorPacksAllSignatures() {
        Vote a = new Vote(1, new byte[32], "validator-a", new byte[64]);
        Vote b = new Vote(1, new byte[32], "validator-b", new byte[64]);

        SignatureAggregator aggregator = new SignatureAggregator.CollectingAggregator();
        SignatureAggregator.AggregatedSignature result = aggregator.aggregate(List.of(a, b));

        assertNotNull(result);
        assertEquals(2, result.signerCount());
        assertEquals(128, result.compressed().length);
    }

    @Test
    public void collectingAggregatorHandlesEmptyInput() {
        SignatureAggregator aggregator = new SignatureAggregator.CollectingAggregator();
        assertNull(aggregator.aggregate(List.of()));
    }
}
