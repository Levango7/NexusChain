package org.nexus.core.crypto.bls;

import java.util.List;

/**
 * BLS12-381 签名者抽象（BFT 投票专用签名者）。
 *
 * <p>实例代表一个持有 BLS 密钥对的验证者节点签名能力。
 * 具体实现将在 M2 阶段绑定 Supranational blst。</p>
 */
public interface BlsSigner {
    /**
     * 对消息进行 BLS 签名。
     *
     * @param message 待签名消息字节
     * @return 签名结果
     */
    BlsSignature sign(byte[] message);

    /**
     * 批量签名（一次性创建多个独立签名）。
     *
     * @param message 消息
     * @return 签名列表（通常仅含单条）
     */
    default List<BlsSignature> signAll(byte[] message) {
        return List.of(sign(message));
    }

    /**
     * 生成新密钥对（用于测试或本地创世）。
     *
     * @return 新的签名者实例
     */
    static BlsSigner generate() {
        // NOTE: 纯Java环境使用secp256k1 EC点实现BLS-like签名验证。
        // 生产环境应接入blst原生库做完整BLS12-381配对验签。
        return Secp256k1BlsSigner.generate();
    }
}
