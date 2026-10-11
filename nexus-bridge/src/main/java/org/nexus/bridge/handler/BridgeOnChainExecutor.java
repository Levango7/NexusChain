package org.nexus.bridge.handler;

import org.nexus.bridge.BridgeConfig;
import org.nexus.bridge.BridgeException;
import org.nexus.bridge.MintRequest;
import org.nexus.bridge.UnlockRequest;
import org.nexus.bridge.config.BridgeChainProperties;
import org.nexus.bridge.model.BridgeTransaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 桥链上执行器（P0 修复 2026-10-09）。
 *
 * <p><b>背景：</b>桥的链上执行子系统（{@code AbstractBridgeHandler} 系及
 * {@code submitContractCall}/{@code eth_sendRawTransaction} 真实现）此前
 * **零生产调用方** —— 4 个 handler 都不是 Spring bean，桥在 {@code mint}/{@code unlock}
 * 时只改数据库并标 {@code MINTED}/{@code UNLOCKED}，{@code targetTxHash} 恒为 null。
 * 即"报告已铸造，但目标链上什么都没发生"。</p>
 *
 * <p>本类把 handler 按链装配起来，供 {@code BridgeServiceImpl} 在
 * mint/unlock 时做**真实广播**并取回链上交易哈希。</p>
 *
 * <h2>装配前提（缺一不可，缺则视为该链不可上链）</h2>
 * <ol>
 *   <li>{@code nexus.bridge.<chain>.rpc-endpoint}</li>
 *   <li>{@code nexus.bridge.<chain>.contract-address}（桥合约）</li>
 *   <li>{@code nexus.bridge.<chain>.relayer-private-key}（中继者 EVM 私钥，env 注入）</li>
 * </ol>
 *
 * <h2>当前覆盖范围（2026-10-11 扩展）</h2>
 * <p>支持凭证签名（真发交易）的链：<b>ethereum / bsc / avalanche</b>
 * —— 三者皆为 EVM 且共用 {@code AbstractBridgeHandler.submitContractCall}
 * （credentials 非空即 {@code eth_sendRawTransaction}）。
 * {@code SolanaBridgeHandler} 仍未接入（非 EVM，签名与交易构造是另一套）。</p>
 * <p>未配置凭证的链返回 {@link Optional#empty()}（由调用方按 {@code mockMode}
 * 决定 fail-closed 或放行）。</p>
 *
 * <h2>失败语义</h2>
 * <p>广播失败（RPC 不可达 / 合约调用失败 / 私钥非法）→ 异常向上抛，
 * 由 {@code BridgeServiceImpl} 捕获后置 FAILED 并拒绝标成功（fail-closed）。</p>
 */
@Component
public class BridgeOnChainExecutor {

    private static final Logger log = LoggerFactory.getLogger(BridgeOnChainExecutor.class);

    /** 支持真实签名广播的链（EVM 系；Solana 未接入——非 EVM，签名与交易构造另一套）。 */
    private static final java.util.Set<String> EVM_SIGNING_CHAINS =
            java.util.Set.of("ethereum", "bsc", "avalanche");

    private final BridgeConfig config;
    private final BridgeChainProperties chainProperties;
    private final Map<String, AbstractBridgeHandler> handlers = new ConcurrentHashMap<>();

    public BridgeOnChainExecutor(BridgeConfig config, BridgeChainProperties chainProperties) {
        this.config = config;
        this.chainProperties = chainProperties;
    }

    /**
     * 目标链是否具备真实上链能力（已配置 rpc/合约/私钥，且该链有支持凭证的 handler）。
     */
    public boolean isOnChainAvailable(String chainId) {
        return resolveHandler(chainId).isPresent();
    }

    /**
     * 在目标链广播 mint（铸造）交易。
     *
     * @param chainId 目标链 ID（本桥 mint 的目标链）
     * @param request 原始 mint 请求（含多签）
     * @param lockTx  已 LOCKED 的锁定交易（handler 会校验其状态与时间锁）
     * @return 真链上交易哈希；该链不可上链时返回 {@link Optional#empty()}
     * @throws BridgeException 广播/校验失败（fail-closed，调用方据此置 FAILED）
     */
    public Optional<String> broadcastMint(String chainId, MintRequest request, BridgeTransaction lockTx)
            throws BridgeException {
        Optional<AbstractBridgeHandler> handler = resolveHandler(chainId);
        if (handler.isEmpty()) {
            return Optional.empty();
        }
        BridgeTransaction result = handler.get().mint(request, lockTx);
        String hash = result != null ? result.getTargetTxHash() : null;
        log.info("[BridgeOnChain] mint broadcast on chain={}, txHash={}", chainId, hash);
        return Optional.ofNullable(hash);
    }

    /**
     * 在目标链广播 unlock（解锁）交易。
     *
     * @param chainId 目标链 ID
     * @param request 原始 unlock 请求（含多签）
     * @param burnTx  已 BURNED 的销毁交易
     * @return 真链上交易哈希；该链不可上链时返回 {@link Optional#empty()}
     * @throws BridgeException 广播/校验失败（fail-closed）
     */
    public Optional<String> broadcastUnlock(String chainId, UnlockRequest request, BridgeTransaction burnTx)
            throws BridgeException {
        Optional<AbstractBridgeHandler> handler = resolveHandler(chainId);
        if (handler.isEmpty()) {
            return Optional.empty();
        }
        BridgeTransaction result = handler.get().unlock(request, burnTx);
        String hash = result != null ? result.getTargetTxHash() : null;
        log.info("[BridgeOnChain] unlock broadcast on chain={}, txHash={}", chainId, hash);
        return Optional.ofNullable(hash);
    }

    /**
     * 解析并（首次使用时）装配目标链的 handler。
     *
     * <p>装配失败（如私钥非 hex）不抛异常而是记 ERROR 后视为"不可上链"——
     * 由调用方按 {@code mockMode} fail-closed，避免"配置错但静默成功"。</p>
     */
    private Optional<AbstractBridgeHandler> resolveHandler(String chainId) {
        if (chainId == null) {
            return Optional.empty();
        }
        String chain = chainId.trim().toLowerCase();
        if (!EVM_SIGNING_CHAINS.contains(chain)) {
            return Optional.empty();
        }
        BridgeChainProperties.Chain props = chainPropertiesFor(chain);
        if (props == null || !props.isOnChainReady()) {
            return Optional.empty();
        }
        try {
            return Optional.of(handlers.computeIfAbsent(chain, k -> build(chain, props)));
        } catch (RuntimeException e) {
            log.error("[BridgeOnChain] 装配 {} handler 失败（视为不可上链，fail-closed）: {}",
                    chain, e.getMessage());
            return Optional.empty();
        }
    }

    /** 链名 → 配置（bsc / avalanche 的 getter 已存在于 BridgeChainProperties）。 */
    private BridgeChainProperties.Chain chainPropertiesFor(String chain) {
        switch (chain) {
            case "ethereum":
                return chainProperties.getEthereum();
            case "bsc":
                return chainProperties.getBsc();
            case "avalanche":
                return chainProperties.getAvalanche();
            default:
                return null;
        }
    }

    private AbstractBridgeHandler build(String chain, BridgeChainProperties.Chain props) {
        Credentials credentials = Credentials.create(props.getRelayerPrivateKey());
        String targetContract = props.getTargetContractAddress() != null
                && !props.getTargetContractAddress().isBlank()
                ? props.getTargetContractAddress()
                : props.getContractAddress();
        log.info("[BridgeOnChain] 装配 {} handler: rpc={}, contract={}, targetContract={}, evmChainId={}",
                chain, props.getRpcEndpoint(), props.getContractAddress(), targetContract,
                props.getEvmChainId());
        switch (chain) {
            case "bsc":
                return new BSCBridgeHandler(config, props.getContractAddress(), targetContract,
                        props.getNexusTokenAddress(), props.getRpcEndpoint(), credentials,
                        props.getEvmChainId(), null, null);
            case "avalanche":
                return new AvalancheBridgeHandler(config, props.getContractAddress(), targetContract,
                        props.getNexusTokenAddress(), props.getRpcEndpoint(), credentials,
                        props.getEvmChainId(), null, null);
            default:
                return new EthereumBridgeHandler(config, props.getContractAddress(), targetContract,
                        props.getNexusTokenAddress(), props.getRpcEndpoint(), credentials,
                        props.getEvmChainId(), null, null);
        }
    }
}
