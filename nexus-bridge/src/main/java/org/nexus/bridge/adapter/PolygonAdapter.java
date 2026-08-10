package org.nexus.bridge.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Polygon 链适配器，基于 Web3j 实现真实 JSON-RPC 调用。
 *
 * <p>Polygon 兼容 EVM，复用 {@link AbstractEvmChainAdapter} 通用逻辑，
 * 仅指定 Polygon 主网链 ID（{@code 0x89}）与 RPC 端点。</p>
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code nexus.bridge.polygon.rpc-endpoint} — Polygon RPC 端点 URL</li>
 *   <li>{@code nexus.bridge.polygon.chain-id} — 链 ID（默认 {@code 0x89}）</li>
 * </ul>
 *
 * @since 1.2
 */
@Component
public class PolygonAdapter extends AbstractEvmChainAdapter {

    /**
     * 构造 Polygon 适配器。
     *
     * @param rpcEndpoint RPC 端点 URL，取自配置 {@code nexus.bridge.polygon.rpc-endpoint}
     * @param chainId     链 ID，取自配置 {@code nexus.bridge.polygon.chain-id}，默认 {@code 0x89}
     */
    public PolygonAdapter(
            @Value("${nexus.bridge.polygon.rpc-endpoint:https://polygon-rpc.com}") String rpcEndpoint,
            @Value("${nexus.bridge.polygon.chain-id:0x89}") String chainId) {
        super(chainId, rpcEndpoint);
    }
}
