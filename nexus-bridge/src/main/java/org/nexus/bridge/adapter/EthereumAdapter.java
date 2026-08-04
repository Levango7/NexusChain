package org.nexus.bridge.adapter;

import org.springframework.stereotype.Component;

/**
 * Ethereum 链适配器骨架实现。
 *
 * <p>需后续接入 Web3j SDK 实现真实 RPC 调用。</p>
 *
 * @since 1.2
 */
@Component
public class EthereumAdapter implements ChainAdapter {

    @Override
    public String getChainId() {
        // TODO: 通过 Web3j eth_chainId 返回主网/测试网 chainId
        return "0x1";
    }

    @Override
    public long getBlockHeight() {
        // TODO: 通过 Web3j eth_blockNumber 获取最新高度
        return 0L;
    }

    @Override
    public String sendTransaction(byte[] tx) {
        // TODO: 通过 Web3j eth_sendRawTransaction 发送交易
        throw new UnsupportedOperationException("EthereumAdapter.sendTransaction: not yet implemented");
    }

    @Override
    public Object getTransactionReceipt(String hash) {
        // TODO: 通过 Web3j eth_getTransactionReceipt 查询回执
        return null;
    }

    @Override
    public String callContract(String address, String data) {
        // TODO: 通过 Web3j eth_call 只读调用合约
        return null;
    }
}