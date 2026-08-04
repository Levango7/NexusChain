package org.nexus.bridge.adapter;

import org.springframework.stereotype.Component;

/**
 * Polygon 链适配器骨架实现。
 *
 * <p>需后续接入 Web3j 兼容 RPC 实现真实调用。</p>
 *
 * @since 1.2
 */
@Component
public class PolygonAdapter implements ChainAdapter {

    @Override
    public String getChainId() {
        // TODO: Polygon 主网 chainId = 137
        return "0x89";
    }

    @Override
    public long getBlockHeight() {
        // TODO: 通过 Polygon RPC eth_blockNumber 获取最新高度
        return 0L;
    }

    @Override
    public String sendTransaction(byte[] tx) {
        // TODO: 通过 Polygon RPC eth_sendRawTransaction 发送交易
        throw new UnsupportedOperationException("PolygonAdapter.sendTransaction: not yet implemented");
    }

    @Override
    public Object getTransactionReceipt(String hash) {
        // TODO: 通过 Polygon RPC eth_getTransactionReceipt 查询回执
        return null;
    }

    @Override
    public String callContract(String address, String data) {
        // TODO: 通过 Polygon RPC eth_call 只读调用合约
        return null;
    }
}