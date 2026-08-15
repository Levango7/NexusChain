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
 * PLAN-004 同类：BRIDGE_BURN 二进制 payload + BridgeRule 校验闭环。
 *
 * <p>burn 为单用户自签销毁（tx.signature Ed25519 验签），非验证人多签——
 * payload 二进制（时间戳+签名数 0），BridgeRule 不再要求多签。</p>
 */
class BridgeBurnPayloadFormatTest {

    private BridgeService bridgeService;
    private BridgeRule bridgeRule;
    private TransactionPool txPool;
    private List<Transaction> poolTxs;

    // 用户 from pubkey（32 字节 hex = 64 字符）+ prikey（签名）
    private static final String FROM_PUB = "aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899";
    private static final String PRIKEY = "1122334455667788990011223344556677889900112233445566778899001122";

    @BeforeEach
    void setUp() throws Exception {
        bridgeService = new BridgeService();
        txPool = mock(TransactionPool.class);
        poolTxs = new ArrayList<>();
        doAnswer(inv -> { poolTxs.add(inv.getArgument(0)); return null; })
                .when(txPool).add(any(Transaction.class));
        setField(bridgeService, "txPool", txPool);
        setField(bridgeService, "minValidators", 3);

        bridgeRule = new BridgeRule();
        setField(bridgeRule, "minValidators", 3);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void burnConstructsBinaryPayloadMatchingBridgeRule() throws Exception {
        var result = bridgeService.burn(FROM_PUB, "ETH", 500L, PRIKEY, 1L);
        assertEquals(2000, result.getCode(), "burn 应成功: " + result.getMessage());

        assertEquals(1, poolTxs.size(), "交易应入池");
        Transaction tx = poolTxs.get(0);
        assertEquals(Transaction.Type.BRIDGE_BURN.ordinal(), tx.type);

        // payload 二进制：8 字节时间戳 + 1 字节签名数(0)
        byte[] payload = tx.payload;
        assertEquals(9, payload.length, "payload = 8+1");
        long ts = ByteBuffer.wrap(payload, 0, 8).getLong();
        assertTrue(ts <= System.currentTimeMillis() / 1000, "时间戳应为当前秒（立即生效）");
        assertEquals(0, payload[8] & 0xFF, "签名数应为 0（burn 单用户自签）");

        // tx.from 非空（真实 pubkey）
        assertEquals(Transaction.PUBLIC_KEY_SIZE, tx.from.length);

        // BridgeRule 校验闭环（二进制格式 + 无多签要求）
        var check = bridgeRule.validateTransaction(tx);
        assertTrue(check.isSuccess(), "BridgeRule 应接受 burn: " + check.getMessage());
    }

    @Test
    void burnZeroAmountRejected() {
        var result = bridgeService.burn(FROM_PUB, "ETH", 0L, PRIKEY, 1L);
        assertNotEquals(2000, result.getCode(), "零金额应拒绝");
        assertEquals(0, poolTxs.size());
    }

    @Test
    void burnMissingPubkeyRejected() {
        var result = bridgeService.burn(null, "ETH", 500L, PRIKEY, 1L);
        assertNotEquals(2000, result.getCode(), "缺 fromPubkey 应拒绝");
    }
}
