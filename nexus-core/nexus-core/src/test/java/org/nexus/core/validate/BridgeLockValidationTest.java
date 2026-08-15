package org.nexus.core.validate;

import org.junit.jupiter.api.Test;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BridgeTransaction;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BRIDGE_LOCK 内容校验测试：payload 解析后校验 targetChain/recipient 非空
 * （注释承诺"须包含目标链和收款人"——此前仅校验非空）。
 */
class BridgeLockValidationTest {

    private BridgeRule rule() throws Exception {
        BridgeRule r = new BridgeRule();
        setField(r, "minValidators", 3);
        setField(r, "singleTxLimit", 1_000_000L);
        setField(r, "dailyLimit", 10_000_000L);
        return r;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Transaction lockTx(byte[] payload) {
        // from/to 用非零字节（通过 isNonEmpty 前置校验，聚焦 payload 校验）
        byte[] from = new byte[Transaction.PUBLIC_KEY_SIZE];
        from[0] = 0x01;
        byte[] to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        to[0] = 0x02;
        return new Transaction(
                1, Transaction.Type.BRIDGE_LOCK.ordinal(), 0L,
                from, 1L, 100L,
                payload, to,
                new byte[Transaction.SIGNATURE_SIZE]);
    }

    @Test
    void validLockPayload_accepted() throws Exception {
        // BridgeTransaction.toJson 序列化（含 targetChain/recipient）
        BridgeTransaction bt = new BridgeTransaction(
                "br_1", "NEX", "ETH", 100L, "0xRecipient", 3, 9999999999L);
        byte[] payload = bt.toJson().getBytes(StandardCharsets.UTF_8);
        var result = rule().validateTransaction(lockTx(payload));
        assertTrue(result.isSuccess(), "合法 lock payload 应通过: " + result.getMessage());
    }

    @Test
    void missingTargetChain_rejected() throws Exception {
        byte[] payload = "{\"bridgeTxId\":\"br_1\",\"recipient\":\"0xRec\",\"amount\":100}"
                .getBytes(StandardCharsets.UTF_8);
        var result = rule().validateTransaction(lockTx(payload));
        assertFalse(result.isSuccess(), "缺 targetChain 应拒绝");
        assertTrue(result.getMessage().contains("targetChain"), "错误信息应指明字段");
    }

    @Test
    void missingRecipient_rejected() throws Exception {
        byte[] payload = "{\"bridgeTxId\":\"br_1\",\"targetChain\":\"ETH\",\"amount\":100}"
                .getBytes(StandardCharsets.UTF_8);
        var result = rule().validateTransaction(lockTx(payload));
        assertFalse(result.isSuccess(), "缺 recipient 应拒绝");
        assertTrue(result.getMessage().contains("recipient"), "错误信息应指明字段");
    }

    @Test
    void nonJsonPayload_rejected() throws Exception {
        byte[] payload = "not-json".getBytes(StandardCharsets.UTF_8);
        var result = rule().validateTransaction(lockTx(payload));
        assertFalse(result.isSuccess(), "非 JSON payload 应拒绝");
    }
}
