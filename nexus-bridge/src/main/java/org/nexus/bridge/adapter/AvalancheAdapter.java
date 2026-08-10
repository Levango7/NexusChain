package org.nexus.bridge.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Avalanche C-Chain 链适配器，基于 Web3j 实现真实 JSON-RPC 调用。
 *
 * <p>Avalanche C-Chain（Contract Chain）兼容 EVM，复用 {@link AbstractEvmChainAdapter} 通用逻辑，
 * 仅指定 Avalanche 主网链 ID（{@code 0xA86A}，十进制 43114）与 RPC 端点。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.bridge.avalanche.rpc-endpoint} — Avalanche C-Chain RPC 端点 URL</li>
 *   <li>{@code nexus.bridge.avalanche.chain-id} — 链 ID（默认 {@code 0xA86A}，主网 43114）</li>
 * </ul>
 *
 * <h2>Avalanche C-Chain 特性</h2>
 * <ul>
 *   <li>出块时间约 2 秒，最终性由 Snowman 共识保证（约 1-2 秒）</li>
 *   <li>Chain ID：主网 {@code 43114}（0xA86A），Fuji 测试网 {@code 43113}（0xA869）</li>
 *   <li>RPC 端点：主网 {@code https://api.avax.network/ext/bc/C/rpc}</li>
 *   <li>RPC 端点：Fuji 测试网 {@code https://api.avax-test.network/ext/bc/C/rpc}</li>
 *   <li>Gas 费用以 AVAX 计价，C-Chain 使用与 EVM 一致的 {@code eth_*} JSON-RPC 命名空间</li>
 *   <li>推荐确认数：20 个区块（C-Chain 最终性快，但保守取 20 以应对网络抖动）</li>
 * </ul>
 *
 * <h2>三链架构说明</h2>
 * <p>Avalanche 由三条链组成：X-Chain（资产链，AVAX 转账）、P-Chain（平台链，验证者与子网）、
 * C-Chain（合约链，EVM 兼容）。本适配器仅与 C-Chain 交互，X/P Chain 的资产转移
 * 详见 {@code docs/avalanche-cross-chain-guide.md}。</p>
 *
 * @since 1.2
 */
@Component
public class AvalancheAdapter extends AbstractEvmChainAdapter {

    /**
     * 构造 Avalanche C-Chain 适配器。
     *
     * <p>RPC 端点与链 ID 通过 Spring {@code @Value} 注入，默认值兼容主网。
     * 切换至 Fuji 测试网时，需将 RPC 端点改为
     * {@code https://api.avax-test.network/ext/bc/C/rpc}，链 ID 改为 {@code 0xA869}。</p>
     *
     * @param rpcEndpoint RPC 端点 URL，取自配置 {@code nexus.bridge.avalanche.rpc-endpoint}
     * @param chainId     链 ID，取自配置 {@code nexus.bridge.avalanche.chain-id}，默认 {@code 0xA86A}
     */
    public AvalancheAdapter(
            @Value("${nexus.bridge.avalanche.rpc-endpoint:https://api.avax.network/ext/bc/C/rpc}") String rpcEndpoint,
            @Value("${nexus.bridge.avalanche.chain-id:0xA86A}") String chainId) {
        super(chainId, rpcEndpoint);
    }
}