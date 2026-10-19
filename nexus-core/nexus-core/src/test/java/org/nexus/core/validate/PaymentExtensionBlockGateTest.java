package org.nexus.core.validate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.nexus.command.TransactionCheck;
import org.nexus.core.Block;
import org.nexus.core.WhitelistTransaction;
import org.nexus.core.account.Transaction;
import org.nexus.db.StateDB;
import org.nexus.pool.PeningTransPool;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PLAN-014 Phase 3 回归：含支付扩展新类型的区块在完整验证链路被拒绝。
 *
 * <p>固化事实：{@link AccountRule} 在格式校验阶段调用
 * {@link TransactionCheck#TransactionFormatCheck}，0x19/0x1a 返回
 * {@code Type check error}，区块验证失败——不是"接受但不执行"。
 * 该测试同时防止未来"好心放宽 {@code type<=15} 门禁"造成共识语义漂移。</p>
 */
class PaymentExtensionBlockGateTest {

    private AccountRule accountRule;

    @BeforeEach
    void setUp() {
        accountRule = new AccountRule("exchange");

        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getAccounts(any(), any())).thenReturn(new HashMap<>());

        WhitelistTransaction whitelist = mock(WhitelistTransaction.class);
        when(whitelist.IsUnchecked(anyString())).thenReturn(false);

        PeningTransPool pool = mock(PeningTransPool.class);

        ReflectionTestUtils.setField(accountRule, "stateDB", stateDB);
        ReflectionTestUtils.setField(accountRule, "whitelistTransaction", whitelist);
        ReflectionTestUtils.setField(accountRule, "peningTransPool", pool);
        ReflectionTestUtils.setField(accountRule, "transactionCheck", new TransactionCheck());
    }

    @Test
    void rejectsBlockWithIdentityRegister() {
        Result result = accountRule.validateBlock(block(Transaction.Type.IDENTITY_REGISTER.ordinal()));
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Type check error"), result.getMessage());
    }

    @Test
    void rejectsBlockWithSubscriptionAuth() {
        Result result = accountRule.validateBlock(block(Transaction.Type.SUBSCRIPTION_AUTH.ordinal()));
        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().contains("Type check error"), result.getMessage());
    }

    private static Block block(int type) {
        Block block = new Block();
        block.nHeight = 1;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.body = new ArrayList<>();
        block.body.add(tx(type));
        return block;
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
