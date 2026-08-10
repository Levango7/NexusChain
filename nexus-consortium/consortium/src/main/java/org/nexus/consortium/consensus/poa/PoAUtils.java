package org.nexus.consortium.consensus.poa;

import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import org.nexus.common.Block;
import org.nexus.common.Header;
import org.nexus.common.HexBytes;
import org.nexus.common.Transaction;
import org.nexus.util.CommonUtil;

import java.util.List;

public class PoAUtils {
    public static byte[] getHash(Transaction transaction) {
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(transaction)).asBytes();
    }

    public static byte[] merkleHash(List<Transaction> transactions) {
        byte[] all = transactions.stream().map(PoAUtils::getHash)
                .reduce(new byte[0], Bytes::concat);
        return Hashing.sha256().hashBytes(all).asBytes();
    }

    public static byte[] getHash(Block block) {
        block.setMerkleRoot(new HexBytes(merkleHash(block.getBody())));
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(block.getHeader())).asBytes();
    }

    public static byte[] getHash(Header header){
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(header)).asBytes();
    }
}
