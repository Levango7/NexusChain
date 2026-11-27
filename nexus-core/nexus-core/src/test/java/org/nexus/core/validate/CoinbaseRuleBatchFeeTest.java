package org.nexus.core.validate;

import org.junit.jupiter.api.Test;
import org.nexus.consensus.pow.EconomicModel;
import org.nexus.core.Block;
import org.nexus.core.StateCommitment;
import org.nexus.core.account.Transaction;
import org.nexus.db.StateDB;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CoinbaseRuleBatchFeeTest {

    @Test
    void batchFeeConservationFollowsExactBlockHeight() {
        assertTrue(rule(100).validateBlock(block()).isSuccess());

        Result before = rule(101).validateBlock(block());
        assertFalse(before.isSuccess());
        assertTrue(before.getMessage().contains("fee conservation"), before.getMessage());
    }

    @Test
    void otherPaymentTypesNeverQualifyForFeeConservation() {
        Block block = block();
        block.body.get(1).type = Transaction.Type.CHANNEL_OPEN.ordinal();
        assertFalse(rule(100).validateBlock(block).isSuccess());
    }

    private static CoinbaseRule rule(long activation) {
        StateDB stateDB = mock(StateDB.class);
        when(stateDB.getBlock(any())).thenReturn(new Block());
        EconomicModel economicModel = mock(EconomicModel.class);
        when(economicModel.getConsensusRewardAtHeight(100)).thenReturn(1_000L);

        CoinbaseRule rule = new CoinbaseRule();
        ReflectionTestUtils.setField(rule, "stateDB", stateDB);
        ReflectionTestUtils.setField(rule, "economicModel", economicModel);
        ReflectionTestUtils.setField(rule, "stateCommitment",
                new StateCommitment(StateCommitment.DISABLED_ACTIVATION_HEIGHT, activation));
        return rule;
    }

    private static Block block() {
        Transaction coinbase = Transaction.createEmpty();
        coinbase.amount = 51_000;

        Transaction batch = Transaction.createEmpty();
        batch.type = Transaction.Type.BATCH_TRANSFER.ordinal();
        batch.gasPrice = 1;
        batch.amount = 30;

        Block block = new Block();
        block.nHeight = 100;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.body = new java.util.ArrayList<>(List.of(coinbase, batch));
        return block;
    }
}
