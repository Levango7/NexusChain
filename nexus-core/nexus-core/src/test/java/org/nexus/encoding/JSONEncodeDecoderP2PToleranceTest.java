package org.nexus.encoding;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P2P 线上解码的未知字段容忍回归 —— F2 rest 形态验证发现（2026-10-25）。
 *
 * <p>背景：服务端区块 JSON 会带 getter 派生属性
 * （{@code channelTransaction/stableCoinTransaction/paymentExtensionType/typeName/
 * bridgeTransaction}）；{@code decodeBlocks/decodeTransactions} 原用严格 mapper
 * ⇒ rest 追块永久失败（SyncClient.getBlocks 拉不到块）。</p>
 *
 * <p>本测试锁定：① P2P 两方法容忍未知字段并可解出值；② 普通 {@code decode}
 * 保持严格（放宽不外溢到本地快照/持久化路径）。</p>
 */
class JSONEncodeDecoderP2PToleranceTest {

    private final JSONEncodeDecoder codec = new JSONEncodeDecoder();

    @Test
    void decodeBlocksToleratesGetterDerivedUnknownFields() {
        String json = "[{\"nHeight\":1,"
                + "\"body\":[{\"type\":0,\"nonce\":0,\"channelTransaction\":false,\"typeName\":\"COINBASE\"}],"
                + "\"channelTransaction\":false,\"blockHeight\":900}]";
        List<Block> blocks = codec.decodeBlocks(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(blocks, "P2P 解码必须容忍未知字段（旧实现返回 null）");
        assertEquals(1, blocks.size());
        assertEquals(1, blocks.get(0).nHeight);
    }

    @Test
    void decodeTransactionsToleratesUnknownFields() {
        String json = "[{\"type\":0,\"nonce\":0,\"channelTransaction\":false,\"stableCoinTransaction\":false}]";
        List<Transaction> txs = codec.decodeTransactions(json.getBytes(StandardCharsets.UTF_8));
        assertNotNull(txs, "P2P 解码必须容忍未知字段（旧实现返回 null）");
        assertEquals(1, txs.size());
    }

    @Test
    void strictDecodeStillRejectsUnknownFieldsForNonP2PPaths() {
        // 放宽只作用于 P2P 路径；普通 decode（本地快照/持久化）仍严格。
        String json = "{\"nHeight\":1,\"channelTransaction\":false}";
        assertNull(codec.decode(json.getBytes(StandardCharsets.UTF_8), Block.class),
                "非 P2P 路径保持严格：未知字段 ⇒ 解码失败（返回 null）");
    }
}
