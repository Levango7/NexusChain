package org.nexus.bridge.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Ethereum 链适配器，基于 Web3j 实现真实 JSON-RPC 调用。
 *
 * <p>通过 {@link AbstractEvmChainAdapter} 复用 EVM 通用交互逻辑，
 * 仅指定 Ethereum 主网链 ID（{@code 0x1}）与 RPC 端点。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.bridge.ethereum.rpc-endpoint} — Ethereum RPC 端点 URL</li>
 *   <li>{@code nexus.bridge.ethereum.chain-id} — 链 ID（默认 {@code 0x1}）</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class EthereumAdapter extends AbstractEvmChainAdapter {

    /**
     * 构造 Ethereum 适配器。
     *
     * <p>RPC 端点与链 ID 通过 Spring {@code @Value} 注入，默认值兼容主网。</p>
     *
     * @param rpcEndpoint RPC 端点 URL，取自配置 {@code nexus.bridge.ethereum.rpc-endpoint}
     * @param chainId     链 ID，取自配置 {@code nexus.bridge.ethereum.chain-id}，默认 {@code 0x1}
     */
    public EthereumAdapter(
            @Value("${nexus.bridge.ethereum.rpc-endpoint:https://mainnet.infura.io/v3/}") String rpcEndpoint,
            @Value("${nexus.bridge.ethereum.chain-id:0x1}") String chainId) {
        super(chainId, rpcEndpoint);
    }
}
