package org.nexus.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.nexus.core.Block;
import org.nexus.core.ChainHeadProvider;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.p2p.entity.Status;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GET /consensus/status 只依赖内存链头；PG 故障时不能调用数据库型 currentHeader。
 */
public class ConsensusControllerStatusTest {

    @Test
    void getStatusUsesChainHeadProviderInsteadOfDatabaseBackedChain() {
        ConsensusController controller = new ConsensusController();
        ChainHeadProvider provider = mock(ChainHeadProvider.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        JSONEncodeDecoder codec = mock(JSONEncodeDecoder.class);
        Block best = mock(Block.class);
        Block genesis = mock(Block.class);

        when(provider.currentHeader()).thenReturn(best);
        when(best.getHash()).thenReturn(new byte[Block.HASH_SIZE]);
        when(genesis.getHash()).thenReturn(new byte[Block.HASH_SIZE]);
        when(codec.encode(any())).thenReturn(new byte[]{0x01});

        ReflectionTestUtils.setField(controller, "chainHeadProvider", provider);
        ReflectionTestUtils.setField(controller, "bc", bc);
        ReflectionTestUtils.setField(controller, "genesis", genesis);
        ReflectionTestUtils.setField(controller, "codec", codec);

        Object result = controller.getStatus();

        assertArrayEquals(new byte[]{0x01}, (byte[]) result);
        verifyNoInteractions(bc);
    }

    @Test
    void handleStatusAtSameHeightDoesNotQueryDatabaseBackedChain() {
        ConsensusController controller = new ConsensusController();
        ChainHeadProvider provider = mock(ChainHeadProvider.class);
        NexusChainBlockChain bc = mock(NexusChainBlockChain.class);
        JSONEncodeDecoder codec = mock(JSONEncodeDecoder.class);

        Block header = block(100);
        byte[] genesisHash = new byte[Block.HASH_SIZE];
        when(provider.currentHeader()).thenReturn(header);

        Status status = new Status();
        status.currentHeight = header.nHeight;
        status.bestBlockHash = header.getHash();
        status.genesisHash = genesisHash;
        when(codec.decode(any(byte[].class), eq(Status.class))).thenReturn(status);

        ReflectionTestUtils.setField(controller, "chainHeadProvider", provider);
        ReflectionTestUtils.setField(controller, "bc", bc);
        ReflectionTestUtils.setField(controller, "codec", codec);
        // handleStatus 用 genesis.getHash() 比较；避免依赖 Block 的内部哈希字段。
        ReflectionTestUtils.setField(controller, "genesis", genesis(genesisHash));

        assertNotNull(controller.handleStatus(new byte[]{0x01}, mock(HttpServletRequest.class)));
        verifyNoInteractions(bc);
    }

    private static Block block(long height) {
        Block block = new Block();
        block.nVersion = 1;
        block.nHeight = height;
        block.nTime = 1700000000L + height;
        block.hashPrevBlock = new byte[Block.HASH_SIZE];
        block.hashMerkleRoot = new byte[Block.HASH_SIZE];
        block.hashMerkleState = new byte[Block.HASH_SIZE];
        block.hashMerkleIncubate = new byte[Block.HASH_SIZE];
        block.nBits = new byte[Block.HASH_SIZE];
        block.nNonce = new byte[Block.HASH_SIZE];
        return block;
    }

    private static Block genesis(byte[] hash) {
        Block genesis = mock(Block.class);
        when(genesis.getHash()).thenReturn(hash);
        return genesis;
    }
}
