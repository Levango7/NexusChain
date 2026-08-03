package org.nexus.consortium;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.nexus.common.Block;
import org.nexus.common.Header;
import org.nexus.consortium.service.BlockRepositoryService;
import org.nexus.util.BigEndian;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.nexus.consortium.TestUtils.BYTES;
import static org.nexus.consortium.TestUtils.getBlock;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = Start.class)
// use SPRING_CONFIG_LOCATION environment to locate spring config
// for example: SPRING_CONFIG_LOCATION=classpath:\application.yml,some-path\custom-config.yml
// set consensus.name = 'none' to run this test class
public class BlockRepositoryTests {
    @Autowired
    private BlockRepositoryService blockStore;

    private void assertHeader(Header header) {
        assert header.getVersion() == 1;
        assert Arrays.equals(header.getHash().getBytes(), BigEndian.encodeInt64(header.getHeight()));
        assert Arrays.equals(header.getHashPrev().getBytes(), header.getHeight() == 0 ? BYTES : BigEndian.encodeInt64(header.getHeight() - 1));
        assert Arrays.equals(header.getMerkleRoot().getBytes(), BYTES);
        assert Arrays.equals(header.getPayload().getBytes(), BYTES);
    }

    private void assertBody(Block block) {
        for (int i = 0; i < block.getBody().size(); i++) {
            assert new String(block.getBody().get(i).getHash().getBytes()).equals(block.getHeight() + "" + i);
        }
    }

    private void assertBlock(Block block) {
        assertHeader(block.getHeader());
        assertBody(block);
    }

    @Before
    public void saveBlocks() {
        if (blockStore.getBlockByHeight(0).isPresent()){
            return;
        }
        for (int i = 0; i < 10; i++) {
            blockStore.writeBlock(getBlock(i));
        }
    }


    @Test
    public void testGetBestBlock() {
        Block best = blockStore.getBestBlock();
        assert best.getHeight() == 9;
        assert best.getBody().size() == 3;
        assertBlock(best);
    }

    @Test
    public void testGetBestHeader() {
        Header best = blockStore.getBestHeader();
        assert best.getHeight() == 9L;
        assertHeader(best);
    }

    @Test
    public void testGetHeader() {
        assert blockStore.getHeader(BigEndian.encodeInt64(5)).isPresent();
        assert !blockStore.getHeader(BigEndian.encodeInt64(-1)).isPresent();
    }

    @Test
    public void testGetBlock() {
        assert blockStore.getBlock(BigEndian.encodeInt64(5)).isPresent();
        assert !blockStore.getBlock(BigEndian.encodeInt64(-1)).isPresent();
    }

    @Test
    public void testGetHeaders() {
        assert blockStore.getHeaders(0, 10).size() == 10;
        assert blockStore.getHeaders(0, 0).size() == 0;
        assert blockStore.getHeaders(0, -1).size() == 10;
    }

    @Test
    public void testGetBlocks() {
        List<Block> blocks = blockStore.getBlocks(0, 10);
        assert blocks.size() == 10;
        assert blockStore.getBlocks(0, 0).size() == 0;
        assert blockStore.getBlocks(0, -1).size() == 10;
        blocks.forEach(this::assertBlock);
    }

    @Test
    public void testGetHeadersBetween() {
        List<Header> headers = blockStore.getHeadersBetween(0, 9);
        assert headers.size() == 10;
        headers.forEach(this::assertHeader);
        assert blockStore.getHeadersBetween(0, 0).size() == 1;
        assert blockStore.getHeadersBetween(0, -1).size() == 0;
        assert blockStore.getHeadersBetween(0, 9, 0).size() == 0;
        assert blockStore.getHeadersBetween(0, 9, -1).size() == 10;
        assert blockStore.getHeadersBetween(0, 9, 10).size() == 10;
    }

    @Test
    public void testGetBlocksBetween() {
        List<Block> blocks = blockStore.getBlocksBetween(0, 9);
        assert blocks.size() == 10;
        blocks.forEach(this::assertBlock);
        assert blockStore.getBlocksBetween(0, 0).size() == 1;
        assert blockStore.getBlocksBetween(0, -1).size() == 0;
        assert blockStore.getBlocksBetween(0, 9, 0).size() == 0;
        assert blockStore.getBlocksBetween(0, 9, -1).size() == 10;
        assert blockStore.getBlocksBetween(0, 9, 10).size() == 10;
        assert blockStore.getBlocksBetween(0, 9, 1).get(0).getHeight() == 0;
    }

    @Test
    public void testGetHeadersBetweenDesc() {
        List<Header> headers = blockStore.getHeadersBetweenDescend(0, 9, 1);
        assert headers.size() == 1;
        assert headers.get(0).getHeight() == 9;
    }

    @Test
    public void testGetBlocksBetweenDesc() {
        List<Block> blocks = blockStore.getBlocksBetweenDescend(0, 9, 1);
        assert blocks.size() == 1;
        assert blocks.get(0).getHeight() == 9;
    }

    @Test
    public void testGetHeaderByHeight() {
        Optional<Header> header = blockStore.getHeaderByHeight(0);
        assert header.isPresent();
        assertHeader(header.get());
        header = blockStore.getHeaderByHeight(9);
        assert header.isPresent();
        assertHeader(header.get());
        assert !blockStore.getHeaderByHeight(-1).isPresent();
    }

    @Test
    public void testGetBlockByHeight() {
        Optional<Block> block = blockStore.getBlockByHeight(0);
        assert block.isPresent();
        assertBlock(block.get());
        block = blockStore.getBlockByHeight(9);
        assert block.isPresent();
        assertBlock(block.get());
        assert !blockStore.getBlockByHeight(-1).isPresent();
    }

    @Test
    public void testAncestorHeaderQueries() {
        Optional<Header> ancestor = blockStore.getAncestorHeader(BigEndian.encodeInt64(9), 0);
        assert ancestor.isPresent();
        assert ancestor.get().getHeight() == 0;
        assertHeader(ancestor.get());
        assert !blockStore.getAncestorHeader(BigEndian.encodeInt64(9), -1).isPresent();
        assert blockStore.getAncestorHeaders(BigEndian.encodeInt64(9), 10).size() == 10;
        assert blockStore.getAncestorHeaders(BigEndian.encodeInt64(9), 0).size() == 0;
        assert blockStore.getAncestorHeaders(BigEndian.encodeInt64(9), -1).size() == 10;
    }

    @Test
    public void testAncestorBlockQueries() {
        Optional<Block> ancestor = blockStore.getAncestorBlock(BigEndian.encodeInt64(9), 0);
        assert ancestor.isPresent();
        assert ancestor.get().getHeight() == 0;
        assertBlock(ancestor.get());
        assert !blockStore.getAncestorBlock(BigEndian.encodeInt64(9), -1).isPresent();
        assert blockStore.getAncestorBlocks(BigEndian.encodeInt64(9), 10).size() == 10;
        assert blockStore.getAncestorBlocks(BigEndian.encodeInt64(9), 0).size() == 0;
        assert blockStore.getAncestorBlocks(BigEndian.encodeInt64(9), -1).size() == 10;
    }

    @Test
    public void testGetByID(){
        assert blockStore.hasBlock(BigEndian.encodeInt64(0));
        assert blockStore.hasBlock(BigEndian.encodeInt64(9));
        assert !blockStore.hasBlock(BigEndian.encodeInt64(-1));
    }
}
