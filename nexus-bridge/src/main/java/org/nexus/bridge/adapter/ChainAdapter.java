package org.nexus.bridge.adapter;

/**
 * 链适配器统一接口。
 *
 * <p>抽象与外部真实链（Ethereum / BSC / Polygon 等）的交互能力，
 * 屏蔽各链 SDK 差异，为桥上层提供统一调用面。</p>
 *
 * @since 1.2
 */
public interface ChainAdapter {

    /**
     * 获取链 ID。
     *
     * @return 链 ID
     */
    String getChainId();

    /**
     * 获取当前最新区块高度。
     *
     * @return 区块高度
     */
    long getBlockHeight();

    /**
     * 向链发送交易。
     *
     * @param tx 交易原始字节（RLP / 各链约定）
     * @return 交易哈希
     */
    String sendTransaction(byte[] tx);

    /**
     * 按交易哈希查询交易回执。
     *
     * @param hash 交易哈希
     * @return 回执对象（链相关，建议 JSON 字符串）
     */
    Object getTransactionReceipt(String hash);

    /**
     * 只读调用链上合约。
     *
     * @param address 合约地址
     * @param data    调用 calldata（hex）
     * @return 调用返回值（hex）
     */
    String callContract(String address, String data);
}