package org.nexus.core.crypto.bls;

/**
 * BLS12-381 签名接口（NexFinality BFT 投票域模型）。
 *
 * <p>支持聚合签名：N 个 Vote 的签名可以被压缩为单个常数大小（96 字节 G1 点），
 * 这是实现 "2/3 质押权重" 高效链上验证的关键。</p>
 */
public interface BlsSignature {
    /**
     * 获取签名原始字节（96 字节 G1 点压缩格式）。
     */
    byte[] toBytesCompressed();

    /**
     * 按 BLS 规范化序列化。
     */
    byte[] serialize();

    /**
     * 验证此签名是否与指定消息匹配。
     *
     * @param message 消息字节
     * @param publicKey 签名人公钥
     * @return 有效性
     */
    boolean verify(byte[] message, BlsPublicKey publicKey);
}
