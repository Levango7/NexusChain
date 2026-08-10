package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.nexus.crypto.HashUtil;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.util.ArrayList;
import java.util.List;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {NexusChainTestConfig.class})
public abstract class NexusChainTest {
    private Block getGenesis() {
        return ctx.getBean(Block.class);
    }

    private Block getHeightOne() {
        Block b = getGenesis();
        b.hashPrevBlock = getGenesis().getHash();
        b.nHeight = 1;
        b.weight = 1;
        return b;
    }

    private List<Block> getHeightN(long endHeight, byte[] merkleRoot) {
        List<Block> blocks = new ArrayList<>();
        Block prev = getGenesis();
        for (int i = 1; i <= endHeight; i++) {
            Block newBlock = getGenesis();
            newBlock.hashPrevBlock = prev.getHash();
            if (merkleRoot != null) {
                newBlock.hashMerkleRoot = merkleRoot;
            }
            newBlock.nHeight = i;
            newBlock.weight = 1;
            newBlock.reHash();
            blocks.add(newBlock);
            prev = newBlock;
        }
        return blocks;
    }

    @Autowired
    protected ApplicationContext ctx;

    @Autowired
    private JSONEncodeDecoder encodeDecoder;

    public abstract NexusChainBlockChain getChain();

    @Test
    public void testGetChain() {
        NexusChainBlockChain chain = getChain();
        assert Arrays.areEqual(chain.getCanonicalHeader(0).getHash(), getGenesis().getHash());
    }

    @Test
    public void testGetGenesis() {
        NexusChainBlockChain bc = getChain();
        assert Arrays.areEqual(bc.getGenesis().getHash(), getGenesis().getHash());
    }

    @Test
    public void testHasBlock() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.hasBlock(getHeightOne().getHash());
    }

    @Test
    public void testGetCurrentHeader() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert Arrays.areEqual(bc.currentHeader().getHash(), getHeightOne().getHash());
    }

    @Test
    public void testGetCurrentBlock() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert Arrays.areEqual(bc.currentBlock().getHash(), getHeightOne().getHash());
    }

    @Test
    public void testGetHeader() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.getHeader(getHeightOne().getHash()) != null;
    }

    @Test
    public void testGetBlock() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.getBlock(getHeightOne().getHash()) != null;
    }

    @Test
    public void testGetHeaders() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.getHeaders(1, 1000).size() == 1;
    }

    @Test
    public void testGetBlocks() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.getBlocks(1, 1000).size() == 1;
    }

    @Test
    public void testGetCanonicalHeader() {
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert Arrays.areEqual(bc.getCanonicalHeader(1).getHash(), getHeightOne().getHash());
    }

    @Test
    public void testGetCanonicalHeaders() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        List<Block> headers = bc.getCanonicalHeaders(0, 11);
        assert headers != null;
        assert headers.size() == 11;
        assert headers.get(0) != null;
        assert Arrays.areEqual(headers.get(0).getHash(), getGenesis().getHash());
        assert Arrays.areEqual(headers.get(10).getHash(), bc.currentHeader().getHash());
    }

    @Test
    public void testGetCanonicalBlock() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        Block block9 = bc.getCanonicalBlock(9);
        assert block9 != null;
    }

    @Test
    public void testGetCanonicalBlocks() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        List<Block> canonicalBlocks = bc.getCanonicalBlocks(0, 10);
        assert canonicalBlocks != null;
        assert Arrays.areEqual(getGenesis().getHash(), canonicalBlocks.get(0).getHash());
        assert canonicalBlocks.size() == 10;
    }

    @Test
    public void testIsCanonical(){
        NexusChainBlockChain bc = getChain();
        bc.writeBlock(getHeightOne());
        assert bc.isCanonical(getHeightOne().getHash());
    }

    @Test
    public void testFindAncestorHeader() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        Block currentHeader = bc.currentHeader();
        Block foundHeader = bc.findAncestorHeader(currentHeader.getHash(), 0);
        assert foundHeader != null;
        assert foundHeader.nHeight == 0;
    }

    @Test
    public void testgetAncestorHeaders() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        Block currentHeader = bc.currentHeader();
        List<Block> foundHeaders = bc.getAncestorHeaders(currentHeader.getHash(), 0);
        assert foundHeaders != null;
        assert foundHeaders.size() == currentHeader.nHeight + 1;
    }

    @Test
    public void testFindAncestorBlock() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        Block currentHeader = bc.currentHeader();
        Block b = bc.findAncestorBlock(currentHeader.getHash(), 0);
        assert b != null;
        assert Arrays.areEqual(getGenesis().getHash(), b.getHash());
    }

    @Test
    public void testGetAncestorBlocks() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        Block currentHeader = bc.currentHeader();
        Block b = bc.findAncestorBlock(currentHeader.getHash(), 0);
        assert b != null;
        assert Arrays.areEqual(getGenesis().getHash(), b.getHash());
    }

    @Test
    public void testGetCurrentTotalWeight(){
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(10, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        assert bc.getCurrentTotalWeight() == 10;
    }

    @Test
    public void testHasTransaction() {
        NexusChainBlockChain bc = getChain();
        assert bc.hasTransaction(getGenesis().body.get(0).getHash());
    }

    @Test
    public void testGetTransaction() {
        NexusChainBlockChain bc = getChain();
        assert bc.getTransaction(getGenesis().body.get(0).getHash()) != null;
    }

    @Test
    public void testWriteBlocks() {
        NexusChainBlockChain bc = getChain();
        List<Block> blocks = getHeightN(20, null);
        for (Block b : blocks) {
            bc.writeBlock(b);
        }
        assert bc.getCanonicalHeader(10) != null;
    }

    // test fork when a longer chain occurs
    @Test
    public void testNewLongChainFork() {
        NexusChainBlockChain bc = getChain();
        List<Block> fork1 = getHeightN(5, null);
        List<Block> fork2 = getHeightN(20, HashUtil.keccak256("abc".getBytes()));
        for (Block b : fork1) {
            bc.writeBlock(b);
        }
        for (Block b : fork2) {
            bc.writeBlock(b);
        }
        assert bc.isCanonical(fork2.get(9).getHash());
        assert bc.isCanonical(fork2.get(5).getHash());
    }


    // test fork when encounters a big-weight block
    @Test
    public void testNewBigWeightBlockFork() {
        NexusChainBlockChain bc = getChain();
        List<Block> fork1 = getHeightN(20, HashUtil.keccak256("abc".getBytes()));
        List<Block> fork2 = getHeightN(10, HashUtil.keccak256("def".getBytes()));
        fork2.get(fork2.size() - 1).weight = 1000;
        for (Block b : fork1) {
            bc.writeBlock(b);
        }
        for (Block b : fork2) {
            if(b.nHeight == 10){
                System.out.println("============");
            }
            bc.writeBlock(b);
        }
        byte[] h = fork2.get(2).getHash();
        String hx = Hex.encodeHexString(h);
        assert bc.isCanonical(h);
        h = fork2.get(5).getHash();
        assert bc.isCanonical(h);
        assert Arrays.areEqual(bc.currentHeader().getHash(), fork2.get(fork2.size() - 1).getHash());
    }
}
