package org.nexus.command;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 类型门禁回归：{@link TransactionCheck#TransactionFormatCheck} 的显式白名单。
 *
 * <p>P0 #522 起，共识门禁由 {@code 15 >= type >= 0} 收紧为
 * {@code 0x00–0x03, 0x09–0x0f} 白名单：白名单外类型（含 0x04–0x08 与 0x10–0x1a）
 * 一律返回 {@code code=5000 "Transaction type not enabled: 0xNN (Type check error)"}，
 * 不是"验证通过、执行缺失"。0x0f 作为对照，证明门禁未误伤既有类型。</p>
 */
class TransactionCheckGateTest {

    @Test
    void rejectsIdentityRegisterAtTypeGate() {
        APIResult result = check(Transaction.Type.IDENTITY_REGISTER.ordinal());
        assertEquals(5000, result.getCode());
        assertTrue(result.getMessage().contains("not enabled"), result.getMessage());
    }

    @Test
    void rejectsSubscriptionAuthAtTypeGate() {
        APIResult result = check(Transaction.Type.SUBSCRIPTION_AUTH.ordinal());
        assertEquals(5000, result.getCode());
        assertTrue(result.getMessage().contains("not enabled"), result.getMessage());
    }

    @Test
    void rejectsMultiSigAndAssetTypesAtTypeGate() {
        for (int type = 0x04; type <= 0x08; type++) {
            APIResult result = check(type);
            assertEquals(5000, result.getCode(), "type 0x0" + type);
            assertTrue(result.getMessage().contains("not enabled"),
                    "type 0x0" + type + ": " + result.getMessage());
        }
    }

    @Test
    void legacyTypePassesTypeGate() {
        APIResult result = check(0x0f);
        assertNotEquals("Transaction type not enabled: 0x0f (Type check error)", result.getMessage(),
                "既有类型 0x0f 不应被类型门禁拒绝（后续失败原因可能是签名/地址/手续费）");
    }

    private APIResult check(int type) {
        TransactionCheck transactionCheck = new TransactionCheck();
        Configuration configuration = mock(Configuration.class);
        when(configuration.getMin_procedurefee()).thenReturn(0L);
        ReflectionTestUtils.setField(transactionCheck, "configuration", configuration);
        return transactionCheck.TransactionFormatCheck(tx(type).toRPCBytes());
    }

    private static Transaction tx(int type) {
        return new Transaction(
                1,
                type,
                1L,
                new byte[Transaction.PUBLIC_KEY_SIZE],
                0L,
                1L,
                "payload".getBytes(StandardCharsets.UTF_8),
                new byte[Transaction.PUBLIC_KEY_HASH_SIZE],
                new byte[Transaction.SIGNATURE_SIZE]);
    }
}
