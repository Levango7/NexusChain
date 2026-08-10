package org.nexus.oracle.random;

/**
 * 可验证随机数预言机。
 *
 * <p>基于 VRF（Verifiable Random Function）方案产出可链上验证的随机数，
 * 用于抽奖、NFT 铸造、随机任务分配等场景。
 *
 * <p>核心特性：
 * <ul>
 *   <li>不可预测：在 seed 提交前任何方无法预知输出</li>
 *   <li>可验证：任意第三方可基于 proof 独立校验 random 与 seed 的对应关系</li>
 *   <li>不可篡改：生成者无法在看到 seed 后调整输出</li>
 * </ul>
 */
public interface RandomOracle {

    /**
     * 基于 seed 生成可验证随机数。
     *
     * @param seed 种子（通常为链上未来区块哈希）
     * @return 包含随机数与证明的 {@link RandomProof}
     */
    RandomProof generateRandom(String seed);

    /**
     * 校验随机数与证明的对应关系。
     *
     * @param random 待校验随机数
     * @param proof  伴随证明
     * @return true 表示校验通过
     */
    boolean verifyRandom(String random, String proof);
}