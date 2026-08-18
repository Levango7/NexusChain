package org.nexus.l2.integration;


import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Merkle 树构建与证明生成工具（与 Solidity {@code MerkleLib} 一致）。
 *
 * <p>用于 L2→L1 提款端到端集成测试，构造与链上 {@code L2Bridge} 合约
 * 完全一致的 Merkle 树，生成单笔提款的 Merkle proof 供
 * {@code finalizeWithdrawsWithProof} 验证。</p>
 *
 * <h2>哈希方案</h2>
 * <ul>
 *   <li><b>叶节点哈希</b>：{@code keccak256(abi.encode(token, recipient, amount, index))}
 *       <ul>
 *         <li>{@code token} — address（20 字节，ABI 编码右对齐到 32 字节）</li>
 *         <li>{@code recipient} — address（同上）</li>
 *         <li>{@code amount} — uint256（32 字节大端）</li>
 *         <li>{@code index} — uint256（32 字节大端，叶子在批次中的位置）</li>
 *       </ul>
 *       共 128 字节输入 → 32 字节哈希</li>
 *   <li><b>内部节点哈希</b>：{@code keccak256(abi.encodePacked(left, right))}
 *       = {@code keccak256(left || right)}（64 字节输入 → 32 字节哈希）</li>
 * </ul>
 *
 * <h2>与 Solidity 一致性</h2>
 * <p>本类实现的哈希方案与 {@code L2Bridge.sol} 中
 * {@code finalizeWithdrawsWithProof} 的叶节点计算
 * {@code keccak256(abi.encode(token, recipient, amount, index))}
 * 及 {@code MerkleLib.verifyMerkleProof} 的内部节点计算
 * {@code keccak256(abi.encodePacked(proof[i], computed))}
 * 完全一致，确保 Java 侧生成的 proof 能被 Solidity 合约验证通过。</p>
 *
 * <h2>使用示例</h2>
 * <pre>{@code
 * // 1. 构造提款列表
 * List<WithdrawalLeaf> withdrawals = Arrays.asList(
 *     new WithdrawalLeaf(token, recipient1, amount1),
 *     new WithdrawalLeaf(token, recipient2, amount2),
 *     new WithdrawalLeaf(token, recipient3, amount3)
 * );
 *
 * // 2. 构建 Merkle 树
 * MerkleProofBuilder tree = MerkleProofBuilder.build(withdrawals);
 *
 * // 3. 获取根
 * byte[] root = tree.getRoot();
 *
 * // 4. 获取第 i 笔提款的 proof
 * MerkleProofBuilder.Proof proof = tree.getProof(i);
 * // proof.siblings  — bytes32[] 兄弟节点哈希
 * // proof.isRight   — bool[]   每层叶子是否在右侧
 * }</pre>
 *
 * <h2>线程安全</h2>
 * <p>本类不可变（build 后 root/proofs 固定），线程安全。</p>
 *
 * @since 2.1
 */
public final class MerkleProofBuilder {

    // ==================== 叶子数据结构 ====================

    /**
     * 单笔提款叶子数据（未哈希）。
     *
     * <p>对应 Solidity {@code L2Bridge.Withdrawal} struct 加上 {@code index} 字段。
     * 叶节点哈希 = {@code keccak256(abi.encode(token, recipient, amount, index))}。</p>
     */
    public static final class WithdrawalLeaf {
        /** ERC20 代币地址（0x 前缀，40 hex 字符） */
        public final String token;
        /** 收款人地址（0x 前缀，40 hex 字符） */
        public final String recipient;
        /** 提款金额（wei） */
        public final BigInteger amount;

        /**
         * @param token     ERC20 代币地址（0x 前缀）
         * @param recipient 收款人地址（0x 前缀）
         * @param amount    提款金额（必须 &gt; 0）
         */
        public WithdrawalLeaf(String token, String recipient, BigInteger amount) {
            if (token == null || !token.startsWith("0x")) {
                throw new IllegalArgumentException("token 必须是 0x 前缀地址: " + token);
            }
            if (recipient == null || !recipient.startsWith("0x")) {
                throw new IllegalArgumentException("recipient 必须是 0x 前缀地址: " + recipient);
            }
            if (amount == null || amount.signum() <= 0) {
                throw new IllegalArgumentException("amount 必须为正: " + amount);
            }
            // 标准化为小写 0x 前缀（Address 类型构造函数会校验合法性）
            this.token = Numeric.prependHexPrefix(Numeric.cleanHexPrefix(token)).toLowerCase();
            this.recipient = Numeric.prependHexPrefix(Numeric.cleanHexPrefix(recipient)).toLowerCase();
            this.amount = amount;
        }
    }

    // ==================== Proof 数据结构 ====================

    /**
     * 单笔提款的 Merkle proof。
     *
     * <p>对应 Solidity {@code finalizeWithdrawsWithProof} 的
     * {@code bytes32[] proof} 与 {@code bool[] isRight} 参数。</p>
     */
    public static final class Proof {
        /** 兄弟节点哈希列表（每层一个，从叶到根方向） */
        public final List<byte[]> siblings;
        /** 每层位置标记（true=当前节点在右侧，false=在左侧） */
        public final List<Boolean> isRight;

        Proof(List<byte[]> siblings, List<Boolean> isRight) {
            this.siblings = Collections.unmodifiableList(siblings);
            this.isRight = Collections.unmodifiableList(isRight);
        }

        /** @return proof 深度（= siblings.size() = isRight.size()） */
        public int depth() {
            return siblings.size();
        }
    }

    // ==================== 树状态 ====================

    /** Merkle 根（32 字节） */
    private final byte[] root;

    /** 所有叶节点哈希（32 字节 each） */
    private final List<byte[]> leaves;

    /** 每个叶子对应的 proof */
    private final List<Proof> proofs;

    // ==================== 构造 ====================

    private MerkleProofBuilder(byte[] root, List<byte[]> leaves, List<Proof> proofs) {
        this.root = root;
        this.leaves = leaves;
        this.proofs = proofs;
    }

    // ==================== 公开 API ====================

    /**
     * 构建 Merkle 树。
     *
     * @param withdrawals 提款叶子列表（至少 1 个）
     * @return 构建好的 Merkle 树
     * @throws IllegalArgumentException 如果 withdrawals 为空
     */
    public static MerkleProofBuilder build(List<WithdrawalLeaf> withdrawals) {
        if (withdrawals == null || withdrawals.isEmpty()) {
            throw new IllegalArgumentException("withdrawals 不能为空");
        }

        int n = withdrawals.size();

        // 1. 计算所有叶节点哈希
        List<byte[]> leaves = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            WithdrawalLeaf w = withdrawals.get(i);
            leaves.add(hashLeaf(w.token, w.recipient, w.amount, BigInteger.valueOf(i)));
        }

        // 2. 自底向上构建树，同时记录每层的兄弟节点
        //    levels.get(0) = leaves, levels.get(1) = parents, ... levels.get(top) = [root]
        List<List<byte[]>> levels = new ArrayList<>();
        levels.add(leaves);

        while (levels.get(levels.size() - 1).size() > 1) {
            List<byte[]> current = levels.get(levels.size() - 1);
            List<byte[]> next = new ArrayList<>((current.size() + 1) / 2);
            for (int j = 0; j < current.size(); j += 2) {
                byte[] left = current.get(j);
                byte[] right = (j + 1 < current.size()) ? current.get(j + 1) : left;
                next.add(hashParent(left, right));
            }
            levels.add(next);
        }

        byte[] root = levels.get(levels.size() - 1).get(0);

        // 3. 为每个叶子生成 proof
        List<Proof> proofs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            proofs.add(buildProof(levels, i));
        }

        return new MerkleProofBuilder(root, leaves, proofs);
    }

    /**
     * @return Merkle 根（32 字节，不可变副本）
     */
    public byte[] getRoot() {
        return root.clone();
    }

    /**
     * @return Merkle 根的 0x 前缀 hex 字符串（64 hex 字符）
     */
    public String getRootHex() {
        return Numeric.toHexString(root);
    }

    /**
     * 获取第 {@code index} 个叶子的 Merkle proof。
     *
     * @param index 叶子索引（0-based）
     * @return proof（不可变）
     * @throws IndexOutOfBoundsException 如果 index 越界
     */
    public Proof getProof(int index) {
        return proofs.get(index);
    }

    /**
     * @return 叶子数量
     */
    public int size() {
        return leaves.size();
    }

    /**
     * @return 第 {@code index} 个叶子的哈希（32 字节副本）
     */
    public byte[] getLeafHash(int index) {
        return leaves.get(index).clone();
    }

    // ==================== 验证 API ====================

    /**
     * 本地验证 proof（与 Solidity MerkleLib.verifyMerkleProof 一致）。
     *
     * @param leaf    叶节点哈希（32 字节）
     * @param proof   Merkle proof
     * @return true 表示 proof 验证通过且 computed root == this.root
     */
    public boolean verifyProof(byte[] leaf, Proof proof) {
        byte[] computed = leaf.clone();
        for (int i = 0; i < proof.siblings.size(); i++) {
            byte[] sibling = proof.siblings.get(i);
            if (proof.isRight.get(i)) {
                // 当前节点在右侧，兄弟在左侧
                computed = hashParent(sibling, computed);
            } else {
                // 当前节点在左侧，兄弟在右侧
                computed = hashParent(computed, sibling);
            }
        }
        return Arrays.equals(computed, root);
    }

    /**
     * 验证所有叶子的 proof 都能恢复出 root。
     *
     * @return true 表示所有 proof 自洽
     */
    public boolean verifyAllProofs() {
        for (int i = 0; i < leaves.size(); i++) {
            if (!verifyProof(leaves.get(i), proofs.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 内部：哈希计算 ====================

    /**
     * 计算叶节点哈希：keccak256(abi.encode(token, recipient, amount, index))。
     *
     * <p>abi.encode 对每个参数进行 32 字节对齐编码：
     * address 右对齐到 32 字节（前 12 字节为零），uint256 大端 32 字节。
     * 共 128 字节输入 → 32 字节 keccak256 哈希。</p>
     *
     * <p>使用手动字节构造而非 web3j FunctionEncoder，确保与 Solidity abi.encode
     * 完全一致，避免 web3j 编码器潜在的 padding 差异。</p>
     *
     * @param token     ERC20 地址（0x 前缀）
     * @param recipient 收款人地址（0x 前缀）
     * @param amount    金额
     * @param index     叶子索引
     * @return 32 字节 keccak256 哈希
     */
    static byte[] hashLeaf(String token, String recipient, BigInteger amount, BigInteger index) {
        // 手动构造 abi.encode(token, recipient, amount, index) 的 128 字节输出
        // 每个参数占 32 字节，address 右对齐，uint256 大端
        byte[] encoded = new byte[128];

        // token: address (20 字节)，右对齐到 bytes[12..31]
        byte[] tokenBytes = Numeric.hexStringToByteArray(token);
        System.arraycopy(tokenBytes, 0, encoded, 32 - tokenBytes.length, tokenBytes.length);

        // recipient: address (20 字节)，右对齐到 bytes[44..63]
        byte[] recipientBytes = Numeric.hexStringToByteArray(recipient);
        System.arraycopy(recipientBytes, 0, encoded, 32 + (32 - recipientBytes.length), recipientBytes.length);

        // amount: uint256 (32 字节大端)，bytes[64..95]
        byte[] amountBytes = bigIntegerTo32Bytes(amount);
        System.arraycopy(amountBytes, 0, encoded, 64, 32);

        // index: uint256 (32 字节大端)，bytes[96..127]
        byte[] indexBytes = bigIntegerTo32Bytes(index);
        System.arraycopy(indexBytes, 0, encoded, 96, 32);

        return Hash.sha3(encoded);
    }

    /**
     * 将 BigInteger 转换为 32 字节大端表示（uint256 ABI 编码）。
     *
     * @param value 非负 BigInteger
     * @return 32 字节数组，右对齐，左侧补零
     */
    private static byte[] bigIntegerTo32Bytes(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[32];
        if (bytes.length > 32) {
            // 取最后 32 字节（对 uint256 不应发生）
            System.arraycopy(bytes, bytes.length - 32, result, 0, 32);
        } else {
            // 右对齐，左侧补零
            System.arraycopy(bytes, 0, result, 32 - bytes.length, bytes.length);
        }
        return result;
    }

    /**
     * 计算内部节点哈希：keccak256(abi.encodePacked(left, right)) = keccak256(left || right)。
     *
     * @param left  左子节点哈希（32 字节）
     * @param right 右子节点哈希（32 字节）
     * @return 32 字节 keccak256 哈希
     */
    static byte[] hashParent(byte[] left, byte[] right) {
        if (left.length != 32 || right.length != 32) {
            throw new IllegalArgumentException(
                    "Merkle 节点哈希必须为 32 字节: left=" + left.length + ", right=" + right.length);
        }
        byte[] combined = new byte[64];
        System.arraycopy(left, 0, combined, 0, 32);
        System.arraycopy(right, 0, combined, 32, 32);
        return Hash.sha3(combined);
    }

    // ==================== 内部：proof 构建 ====================

    /**
     * 自顶向下为指定叶子索引构建 proof。
     *
     * @param levels 所有层（levels[0]=leaves, levels[top]=[root]）
     * @param index  叶子索引
     * @return Merkle proof
     */
    private static Proof buildProof(List<List<byte[]>> levels, int index) {
        List<byte[]> siblings = new ArrayList<>();
        List<Boolean> isRight = new ArrayList<>();

        int currentIndex = index;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<byte[]> currentLevel = levels.get(level);
            // 处理奇数个节点的情况：最后一个节点与自身配对
            int siblingIndex;
            boolean right;
            if (currentIndex % 2 == 0) {
                // 当前节点是左子，兄弟在右
                siblingIndex = currentIndex + 1;
                right = false;
                // 如果兄弟不存在（奇数叶），使用自身哈希
                if (siblingIndex >= currentLevel.size()) {
                    siblingIndex = currentIndex;
                }
            } else {
                // 当前节点是右子，兄弟在左
                siblingIndex = currentIndex - 1;
                right = true;
            }
            siblings.add(currentLevel.get(siblingIndex));
            isRight.add(right);
            currentIndex = currentIndex / 2;
        }

        return new Proof(siblings, isRight);
    }
}