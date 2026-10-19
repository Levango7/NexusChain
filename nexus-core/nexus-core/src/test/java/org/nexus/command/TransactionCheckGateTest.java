package org.nexus.command;

import org.junit.jupiter.api.Test;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLAN-014 Phase 3 回归：支付扩展类型门禁（{@link TransactionCheck#TransactionFormatCheck}）。
 *
 * <p>固化事实：完整区块/RPC 路径的硬边界是 {@code type <= 15}；0x19/0x1a
 * （IDENTITY_REGISTER / SUBSCRIPTION_AUTH）在类型分支直接返回
 * {@code Type check error}——不是"验证通过、执行缺失"。0x0f 作为对照，
 * 证明门禁未误伤既有类型（其后续失败原因不是类型错误）。</p>
 */
class TransactionCheckGateTest {

    @Test
    void rejectsIdentityRegisterAtTypeGate() {
        APIResult result = check(Transaction.Type.IDENTITY_REGISTER.ordinal());
        assertEquals(5000, result.getCode());
        assertEquals("Type check error", result.getMessage());
    }

    @Test
    void rejectsSubscriptionAuthAtTypeGate() {
        APIResult result = check(Transaction.Type.SUBSCRIPTION_AUTH.ordinal());
        assertEquals(5000, result.getCode());
        assertEquals("Type check error", result.getMessage());
    }

    @Test
    void legacyTypePassesTypeGate() {
        APIResult result = check(0x0f);
        assertNotEquals("Type check error", result.getMessage(),
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
