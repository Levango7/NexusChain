package org.nexus.consortium;

import org.nexus.common.Block;
import org.nexus.consortium.dao.Mapping;
import org.nexus.util.BigEndian;

import java.util.Arrays;

public class TestUtils {
    static final byte[] BYTES = new byte[32];

    static Block getBlock(long height) {
        org.nexus.consortium.entity.Block b = new org.nexus.consortium.entity.Block(
                BigEndian.encodeInt64(height), 1, height == 0 ? BYTES : BigEndian.encodeInt64(height - 1),
                BYTES, height, System.currentTimeMillis() / 1000, BYTES
        );
        org.nexus.consortium.entity.Transaction.TransactionBuilder builder = org.nexus.consortium.entity
                .Transaction.builder().blockHash(BigEndian.encodeInt64(height))
                .height(height)
                .from(BYTES).payload(BYTES).to(BYTES)
                .signature(BYTES);
        b.setBody(Arrays.asList(
                builder.position(0).hash((height + "" + 0).getBytes()).build(),
                builder.position(1).hash((height + "" + 1).getBytes()).build(),
                builder.position(2).hash((height + "" + 2).getBytes()).build()
        ));
        return Mapping.getFromBlockEntity(b);
    }

    public static void main(String[] args){
        print(new ISUB());
    }

    public static interface IN{}

    public static interface INIM extends IN{}

    public static class I implements IN{}

    public static class ISUB implements INIM{}

    static void print(IN in){
        System.out.println("in");
    }

    static void print(INIM in){
        System.out.println("inim");
    }
}
