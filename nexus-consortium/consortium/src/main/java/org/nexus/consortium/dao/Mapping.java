package org.nexus.consortium.dao;

import org.nexus.common.HexBytes;
import org.nexus.consortium.entity.Block;
import org.nexus.consortium.entity.HeaderAdapter;
import org.nexus.consortium.entity.Transaction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class Mapping {
    public static org.nexus.common.Block getFromBlockEntity(Block block){
        org.nexus.common.Header header = getFromHeaderEntity(block);
        org.nexus.common.Block res = new org.nexus.common.Block(header);
        res.setBody(getFromTransactionEntities(block.getBody()));
        return res;
    }

    public static List<org.nexus.common.Block> getFromBlocksEntity(Collection<Block> blocks){
        return blocks.stream().map(Mapping::getFromBlockEntity).collect(Collectors.toList());
    }

    public static org.nexus.common.Header getFromHeaderEntity(HeaderAdapter header){
        return org.nexus.common.Header.builder()
                .hash(new HexBytes(header.getHash()))
                .version(header.getVersion())
                .hashPrev(new HexBytes(header.getHashPrev()))
                .merkleRoot(new HexBytes(header.getMerkleRoot()))
                .height(header.getHeight())
                .createdAt(header.getCreatedAt())
                .payload(new HexBytes(header.getPayload()))
                .build();
    }

    public static List<org.nexus.common.Header> getFromHeaderEntities(Collection<? extends HeaderAdapter> headers){
        return headers.stream().map(Mapping::getFromHeaderEntity).collect(Collectors.toList());
    }

    public static org.nexus.common.Transaction getFromTransactionEntity(Transaction transaction){
        return org.nexus.common.Transaction.builder()
                .blockHash(new HexBytes(transaction.getBlockHash()))
                .height(transaction.getHeight())
                .version(transaction.getVersion())
                .type(transaction.getType()).createdAt(transaction.getCreatedAt())
                .nonce(transaction.getNonce()).from(new HexBytes(transaction.getFrom()))
                .gasPrice(transaction.getGasPrice()).amount(transaction.getAmount())
                .payload(new HexBytes(transaction.getPayload())).to(new HexBytes(transaction.getTo()))
                .signature(new HexBytes(transaction.getSignature())).hash(new HexBytes(transaction.getHash()))
                .build();
    }

    public static List<org.nexus.common.Transaction> getFromTransactionEntities(Collection<Transaction> transactions){
        return transactions.stream()
                .sorted((x, y) -> x.getPosition() - y.getPosition())
                .map(Mapping::getFromTransactionEntity)
                .collect(Collectors.toList());
    }

    public static Block getEntityFromBlock(org.nexus.common.Block block){
        HeaderAdapter adapter = HeaderAdapter.builder().hash(block.getHash().getBytes())
                .version(block.getVersion())
                .hashPrev(block.getHashPrev().getBytes())
                .merkleRoot(block.getMerkleRoot().getBytes())
                .height(block.getHeight())
                .createdAt(block.getCreatedAt())
                .payload(block.getPayload().getBytes()).build();
        Block b = new Block(adapter);
        b.setBody(new ArrayList<>(block.getBody().size()));
        for(int i = 0; i < block.getBody().size(); i++){
            org.nexus.common.Transaction tx = block.getBody().get(i);
            Transaction mapped = Transaction.builder()
                    .blockHash(block.getHash().getBytes())
                    .height(tx.getHeight())
                    .hash(tx.getHash().getBytes())
                    .version(tx.getVersion())
                    .type(tx.getType())
                    .createdAt(tx.getCreatedAt())
                    .nonce(tx.getNonce())
                    .from(tx.getFrom().getBytes())
                    .gasPrice(tx.getGasPrice())
                    .amount(tx.getAmount())
                    .payload(tx.getPayload().getBytes())
                    .to(tx.getTo().getBytes())
                    .signature(tx.getSignature().getBytes())
                    .position(i).build();
            b.getBody().add(mapped);
        }
        return b;
    }
}
