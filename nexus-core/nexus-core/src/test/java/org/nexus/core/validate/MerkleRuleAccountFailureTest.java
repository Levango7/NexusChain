package org.nexus.core.validate;

import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.account.AccountDB;
import org.nexus.core.account.AccountStoreUnavailableException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 总账户缺失/账户库不可用时必须显式失败，不能继续按零余额构造区块。
 */
public class MerkleRuleAccountFailureTest {

    @Test
    void missingTotalAccountFailsClosedInsteadOfNullPointerException() {
        AccountDB accountDB = mock(AccountDB.class);
        when(accountDB.selectaccount(any())).thenReturn(null);

        MerkleRule rule = new MerkleRule("validator");
        ReflectionTestUtils.setField(rule, "accountDB", accountDB);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> rule.validateMerkle(Collections.emptyList(), 1L));

        assertTrue(error.getMessage().contains("total account is missing"), error.getMessage());
    }

    @Test
    void accountStoreFailureIsMarkedForRetryInsteadOfSilentlyDroppingBlock() {
        AccountDB accountDB = mock(AccountDB.class);
        when(accountDB.selectaccount(any()))
                .thenThrow(new AccountStoreUnavailableException("database down", null));

        MerkleRule rule = new MerkleRule("validator");
        ReflectionTestUtils.setField(rule, "accountDB", accountDB);

        Block block = new Block();
        block.nHeight = 1;
        block.body = Collections.emptyList();
        block.hashMerkleRoot = Block.calculateMerkleRoot(block.body);

        Result result = rule.validateBlock(block);

        assertFalse(result.isSuccess());
        assertTrue(result.getMessage().startsWith(MerkleRule.ACCOUNT_STORE_UNAVAILABLE_PREFIX),
                result.getMessage());
    }
}
