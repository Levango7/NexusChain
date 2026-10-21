package org.nexus.core;

import org.junit.jupiter.api.Test;
import org.nexus.db.StateDB;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态接口的链头必须来自内存，不能在 PG 故障时回落到数据库查询。
 */
public class ChainHeadProviderTest {

    @Test
    void returnsInMemoryBestBlock() {
        StateDB stateDB = mock(StateDB.class);
        Block best = mock(Block.class);
        Block genesis = mock(Block.class);
        when(stateDB.getBestBlock()).thenReturn(best);

        ChainHeadProvider provider = provider(stateDB, genesis);

        assertSame(best, provider.currentHeader());
        verify(stateDB).getBestBlock();
    }

    @Test
    void fallsBackToGenesisWhenMemoryHasNoHead() {
        StateDB stateDB = mock(StateDB.class);
        Block genesis = mock(Block.class);
        when(stateDB.getBestBlock()).thenReturn(null);

        ChainHeadProvider provider = provider(stateDB, genesis);

        assertSame(genesis, provider.currentHeader());
    }

    private static ChainHeadProvider provider(StateDB stateDB, Block genesis) {
        ChainHeadProvider provider = new ChainHeadProvider();
        ReflectionTestUtils.setField(provider, "stateDB", stateDB);
        ReflectionTestUtils.setField(provider, "genesis", genesis);
        return provider;
    }
}
