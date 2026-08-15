package org.nexus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.validate.BridgeRule;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PLAN-004 往返测试：BridgeService.mint 构造 → payload 二进制格式对齐 BridgeRule。
 *
 * <p>验证：</p>
 * <ul>
 *   <li>payload 前 8 字节 = timelock 时间戳，第 9 字节 = 签名数，后续 N×64 签名</li>
 *   <li>tx.to 填充真实 recipient（pubkeyHash），不再占位</li>
 *   <li>BridgeRule.validateBridgeMint 校验通过（构造→校验闭环）</li>
 * </ul>
 */
class BridgeMintPayloadFormatTest {

    private BridgeService bridgeService;
    private BridgeRule bridgeRule;
    private TransactionPool txPool;
    private List<Transaction> poolTxs;

    // 3 个真实 64 字节 Ed25519 签名（hex），匹配 min-validators=3
    private static final List<String> SIGNATURES = List.of(
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                    + "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
            "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f"
                    + "606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f",
            "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f"
                    + "a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf");

    // recipient = 20 字节 pubkeyHash hex（40 字符）
    private static final String RECIPIENT_HASH =
            "aabbccddeeff00112233445566778899aabbccdd";

    @BeforeEach
    void setUp() throws Exception {
        bridgeService = new BridgeService();
        txPool = mock(TransactionPool.class);
        poolTxs = new ArrayList<>();
        doAnswer(inv -> { poolTxs.add(inv.getArgument(0)); return null; })
                .when(txPool).add(any(Transaction.class));

        setField(bridgeService, "txPool", txPool);
        setField(bridgeService, "minValidators", 3);
        // timelockDuration=0 → 构造即到期（便于 BridgeRule "timelock 已过期"校验通过）
        setField(bridgeService, "timelockDuration", 0L);

        // BridgeRule：minValidators=3
        bridgeRule = new BridgeRule();
        setField(bridgeRule, "minValidators", 3);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void mintConstructsBinaryPayloadMatchingBridgeRule() {
        var result = bridgeService.mint("br_test_001", "ethereum", RECIPIENT_HASH, 1000L, SIGNATURES);
        assertEquals(2000, result.getCode(), "mint 应成功（APIResult 约定成功码 2000）: " + result.getMessage());

        assertEquals(1, poolTxs.size(), "交易应进入 txPool");
        Transaction tx = poolTxs.get(0);
        assertEquals(Transaction.Type.BRIDGE_MINT.ordinal(), tx.type);

        // 1. payload 二进制格式：8 字节时间戳 + 1 字节签名数 + 3×64 签名
        byte[] payload = tx.payload;
        assertEquals(8 + 1 + 3 * 64, payload.length, "payload 长度 = 8+1+3×64");
        // 前 8 字节 = 到期时间戳（>= 当前秒 - 1，容忍构造/断言跨秒边界）
        long timelock = ByteBuffer.wrap(payload, 0, 8).getLong();
        assertTrue(timelock >= System.currentTimeMillis() / 1000 - 1, "时间戳应为到期时间（>= 当前秒-1）");
        // 第 9 字节 = 签名数 3
        assertEquals(3, payload[8] & 0xFF, "第 9 字节应为签名数");
        // 后续签名应等于传入签名
        for (int i = 0; i < 3; i++) {
            byte[] expected = java.util.HexFormat.of().parseHex(SIGNATURES.get(i));
            byte[] actual = java.util.Arrays.copyOfRange(payload, 9 + i * 64, 9 + (i + 1) * 64);
            assertArrayEquals(expected, actual, "签名 " + i + " 应与传入一致");
        }

        // 2. tx.to 填充真实 recipient pubkeyHash
        assertFalse(java.util.Arrays.equals(tx.to, new byte[Transaction.PUBLIC_KEY_HASH_SIZE]),
                "tx.to 不应为占位零字节");
        assertArrayEquals(java.util.HexFormat.of().parseHex(RECIPIENT_HASH), tx.to,
                "tx.to 应等于 recipient pubkeyHash");
    }

    @Test
    void bridgeRuleValidatesMintedPayload() {
        bridgeService.mint("br_test_002", "ethereum", RECIPIENT_HASH, 1000L, SIGNATURES);
        Transaction tx = poolTxs.get(0);

        // BridgeRule.validateTransaction 应通过（构造→校验闭环）
        var result = bridgeRule.validateTransaction(tx);
        assertTrue(result.isSuccess(), "BridgeRule 应接受 mint 构造的交易: " + result.getMessage());
    }

    @Test
    void insufficientSignaturesRejected() {
        var result = bridgeService.mint("br_test_003", "ethereum", RECIPIENT_HASH, 1000L,
                SIGNATURES.subList(0, 2));  // 2 < minValidators=3
        assertNotEquals(2000, result.getCode(), "签名不足应被拒绝");
        assertEquals(0, poolTxs.size(), "拒绝时不应入池");
    }

    @Test
    void invalidRecipientRejected() {
        var result = bridgeService.mint("br_test_004", "ethereum", "zz-not-hex", 1000L, SIGNATURES);
        assertNotEquals(2000, result.getCode(), "非法 recipient hex 应被拒绝");
    }
}
