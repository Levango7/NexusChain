package org.nexus.bridge.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * BSC（Binance Smart Chain）链适配器，基于 Web3j 实现真实 JSON-RPC 调用。
 *
 * <p>BSC 兼容 EVM，复用 {@link AbstractEvmChainAdapter} 通用逻辑，
 * 仅指定 BSC 主网链 ID（{@code 0x38}）与 RPC 端点。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.bridge.bsc.rpc-endpoint} — BSC RPC 端点 URL</li>
 *   <li>{@code nexus.bridge.bsc.chain-id} — 链 ID（默认 {@code 0x38}）</li>
 * </ul>
 *
 * <h2>BSC 特性</h2>
 * <ul>
 *   <li>出块时间约 3 秒</li>
 *   <li>Gas 费用较低，适合大额跨链</li>
 *   <li>推荐确认数：12 个区块</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class BscAdapter extends AbstractEvmChainAdapter {

    /**
     * 构造 BSC 适配器。
     *
     * @param rpcEndpoint RPC 端点 URL，取自配置 {@code nexus.bridge.bsc.rpc-endpoint}
     * @param chainId     链 ID，取自配置 {@code nexus.bridge.bsc.chain-id}，默认 {@code 0x38}
     */
    public BscAdapter(
            @Value("${nexus.bridge.bsc.rpc-endpoint:https://bsc-dataseed.binance.org}") String rpcEndpoint,
            @Value("${nexus.bridge.bsc.chain-id:0x38}") String chainId) {
        super(chainId, rpcEndpoint);
    }
}
