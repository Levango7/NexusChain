package org.nexus.consortium.consensus.poa;

import org.nexus.common.HexBytes;
import org.nexus.util.BigEndian;

public class PoAConstants {
    public static final HexBytes ZERO_BYTES = new HexBytes(new byte[32]);
    public static final int BLOCK_VERSION = BigEndian.decodeInt32(new byte[]{0, 'p', 'o', 'a'});
    public static final int TRANSACTION_VERSION = BigEndian.decodeInt32(new byte[]{0, 'p', 'o', 'a'});

    // poa
    public static void main(String[] args){
        System.out.println(new String(BigEndian.encodeInt32(BLOCK_VERSION)));
    }
}
