package org.nexus.consortium.dao;

import org.junit.jupiter.api.Test;
import org.nexus.common.Block;
import org.nexus.common.Header;
import org.nexus.common.HexBytes;
import org.nexus.consortium.entity.HeaderAdapter;
import org.nexus.consortium.entity.Transaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mapping 单元测试。
 * 覆盖 entity ↔ common 模型转换的双向映射。
 */
public class MappingTest {

    private static final byte[] HASH = new byte[]{1, 2, 3, 4};
    private static final byte[] HASH_PREV = new byte[]{5, 6, 7, 8};
    private static final byte[] MERKLE_ROOT = new byte[]{9, 10, 11, 12};
    private static final byte[] PAYLOAD = new byte[]{13, 14, 15, 16};

    @Test
    public void testGetFromHeaderEntity() {
        HeaderAdapter adapter = new HeaderAdapter(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        Header header = Mapping.getFromHeaderEntity(adapter);
        assertNotNull(header);
        assertEquals(1, header.getVersion());
        assertEquals(100L, header.getHeight());
        assertEquals(200L, header.getCreatedAt());
        assertArrayEquals(HASH, header.getHash().getBytes());
        assertArrayEquals(HASH_PREV, header.getHashPrev().getBytes());
        assertArrayEquals(MERKLE_ROOT, header.getMerkleRoot().getBytes());
        assertArrayEquals(PAYLOAD, header.getPayload().getBytes());
    }

    @Test
    public void testGetFromHeaderEntities() {
        HeaderAdapter adapter1 = new HeaderAdapter(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        HeaderAdapter adapter2 = new HeaderAdapter(
                HASH_PREV, 1, HASH, MERKLE_ROOT, 101L, 201L, PAYLOAD);
        List<HeaderAdapter> adapters = Arrays.asList(adapter1, adapter2);
        List<Header> headers = Mapping.getFromHeaderEntities(adapters);
        assertEquals(2, headers.size());
        assertEquals(100L, headers.get(0).getHeight());
        assertEquals(101L, headers.get(1).getHeight());
    }

    @Test
    public void testGetFromTransactionEntity() {
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(200L)
                .nonce(1L)
                .from(HASH)
                .gasPrice(0L)
                .amount(500L)
                .payload(PAYLOAD)
                .to(HASH_PREV)
                .signature(PAYLOAD)
                .position(0)
                .build();
        org.nexus.common.Transaction commonTx = Mapping.getFromTransactionEntity(tx);
        assertNotNull(commonTx);
        assertEquals(100L, commonTx.getHeight());
        assertEquals(1, commonTx.getVersion());
        assertEquals(0, commonTx.getType());
        assertEquals(500L, commonTx.getAmount());
    }

    @Test
    public void testGetFromBlockEntity() {
        org.nexus.consortium.entity.Block block = new org.nexus.consortium.entity.Block(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        List<Transaction> body = new ArrayList<>();
        Transaction tx = Transaction.builder()
                .blockHash(HASH)
                .height(100L)
                .hash(new byte[]{1})
                .version(1)
                .type(0)
                .createdAt(200L)
                .nonce(1L)
                .from(HASH)
                .gasPrice(0L)
                .amount(500L)
                .payload(PAYLOAD)
                .to(HASH_PREV)
                .signature(PAYLOAD)
                .position(0)
                .build();
        body.add(tx);
        block.setBody(body);

        Block commonBlock = Mapping.getFromBlockEntity(block);
        assertNotNull(commonBlock);
        assertEquals(100L, commonBlock.getHeight());
        assertEquals(1, commonBlock.getVersion());
        assertNotNull(commonBlock.getBody());
        assertEquals(1, commonBlock.getBody().size());
    }

    @Test
    public void testGetFromBlocksEntity() {
        org.nexus.consortium.entity.Block block1 = new org.nexus.consortium.entity.Block(
                HASH, 1, HASH_PREV, MERKLE_ROOT, 100L, 200L, PAYLOAD);
        block1.setBody(new ArrayList<>());
        org.nexus.consortium.entity.Block block2 = new org.nexus.consortium.entity.Block(
                HASH_PREV, 1, HASH, MERKLE_ROOT, 101L, 201L, PAYLOAD);
        block2.setBody(new ArrayList<>());

        List<org.nexus.consortium.entity.Block> blocks = Arrays.asList(block1, block2);
        List<Block> commonBlocks = Mapping.getFromBlocksEntity(blocks);
        assertEquals(2, commonBlocks.size());
        assertEquals(100L, commonBlocks.get(0).getHeight());
        assertEquals(101L, commonBlocks.get(1).getHeight());
    }

    @Test
    public void testGetFromTransactionEntitiesEmpty() {
        List<org.nexus.common.Transaction> result = Mapping.getFromTransactionEntities(new ArrayList<>());
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    public void testGetFromHeaderEntitiesEmpty() {
        List<Header> result = Mapping.getFromHeaderEntities(new ArrayList<>());
        assertNotNull(result);
        assertEquals(0, result.size());
    }
}