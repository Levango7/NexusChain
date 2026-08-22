package org.nexus.consortium.consensus.poa;

import com.google.common.hash.Hashing;
import com.google.common.primitives.Bytes;
import org.nexus.common.Block;
import org.nexus.common.Header;
import org.nexus.common.HexBytes;
import org.nexus.common.Transaction;
import org.nexus.util.CommonUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PoAUtils {
    public static byte[] getHash(Transaction transaction) {
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(transaction)).asBytes();
    }

    /**
     * 计算 body 中所有交易的 Merkle 树根。
     * <p>
     * P1-11: 替换原先"拼接所有 tx hash 后再 sha256 一次"的简化实现，
     * 改为标准 Merkle 树计算：
     * <ul>
     *   <li>叶子层为每笔交易的 sha256 hash；</li>
     *   <li>逐层将相邻两个 hash 拼接后 sha256，得到上一层；</li>
     *   <li>若某层节点数为奇数，最后一个节点复制自身参与配对；</li>
     *   <li>直到只剩一个节点即为 merkleRoot。</li>
     * </ul>
     * 空交易列表返回 32 字节全零（与 {@link PoAConstants#ZERO_BYTES} 一致）。
     */
    public static byte[] merkleHash(List<Transaction> transactions) {
        if (transactions == null || transactions.isEmpty()) {
            return new byte[32];
        }
        // 叶子层：每笔交易的 hash
        List<byte[]> layer = new ArrayList<>();
        for (Transaction tx : transactions) {
            layer.add(getHash(tx));
        }
        // 逐层两两配对 hash，直到根
        while (layer.size() > 1) {
            List<byte[]> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                byte[] left = layer.get(i);
                byte[] right;
                if (i + 1 < layer.size()) {
                    right = layer.get(i + 1);
                } else {
                    // 奇数个节点：最后一个复制自身
                    right = left;
                }
                next.add(Hashing.sha256().hashBytes(Bytes.concat(left, right)).asBytes());
            }
            layer = next;
        }
        return layer.get(0);
    }

    public static byte[] getHash(Block block) {
        block.setMerkleRoot(new HexBytes(merkleHash(block.getBody())));
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(block.getHeader())).asBytes();
    }

    public static byte[] getHash(Header header){
        return Hashing.sha256().hashBytes(CommonUtil.getRaw(header)).asBytes();
    }
}
