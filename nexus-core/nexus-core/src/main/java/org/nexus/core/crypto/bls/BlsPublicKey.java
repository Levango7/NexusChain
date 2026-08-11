package org.nexus.core.crypto.bls;

/**
 * BLS12-381 签名域接口（NexFinality ADR-030 P1 阶段抽象层）。
 *
 * <p>设计原则：
 * <ul>
 *   <li>零自研：仅定义协议面，具体实现绑定第三方库（Supranational blst）</li>
 *   <li>可替换：接口不变，实现可切换为 mock/skeleton 或真实 blst</li>
 *   <li>隔离性：共识层依赖此接口而非具体实现</li>
 * </ul>
 *
 * <p>不引入 BLS 实现库的原因：接口先行，实现延迟至 M2 阶段决定。
 * Mock 实现满足当前开发迭代。</p>
 */
public interface BlsPublicKey {
    /**
     * 获取压缩格式公钥字节序列（48 字节）。
     */
    byte[] toBytesCompressed();

    /**
     * 从压缩字节恢复公钥。
     *
     * @param compressed BLS12-381 G2 点压缩格式（48 字节）
     * @return 公钥对象
     * @throws IllegalArgumentException 若字节不合法
     */
    static BlsPublicKey fromBytesCompressed(byte[] compressed) {
        throw new UnsupportedOperationException("BLS public key deserialization not yet implemented");
    }
}
