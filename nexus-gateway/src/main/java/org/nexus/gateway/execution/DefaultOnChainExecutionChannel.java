package org.nexus.gateway.execution;

import org.nexus.gateway.client.ChainRpcClient;
import org.nexus.gateway.client.ExchangeWalletClient;
import org.nexus.gateway.config.GatewayConfig;
import org.nexus.settlement.execution.OnChainExecutionChannel;
import org.nexus.settlement.execution.TransactionRequest;
import org.nexus.settlement.execution.TransactionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link OnChainExecutionChannel} 的默认实现，位于 nexus-gateway。
 * <p>
 * 注入 {@link ChainRpcClient}（广播与确认查询）和 {@link ExchangeWalletClient}
 * （签名 + 广播），打通"构造交易 → 请求签名 → 广播 → 等待确认 → 返回结果"
 * 的完整链上执行管道。
 * </p>
 *
 * <h3>执行流程</h3>
 * <ol>
 *   <li>校验 {@link TransactionRequest} 关键字段</li>
 *   <li>幂等检查：相同 requestId 已有结果则直接返回</li>
 *   <li>sandbox / mock 模式判定（platformPubkey 未配置 或 skipConfirmation=true）：
 *       生成模拟 txHash 并直接返回 SUCCESS（标记 simulated=true）</li>
 *   <li>生产模式：将 toAddress 转为 pubkeyHash → 调用
 *       {@link ExchangeWalletClient#signTransfer} 完成签名 + 广播 → 轮询
 *       {@link ChainRpcClient#isTransactionConfirmed} 等待确认</li>
 *   <li>返回 {@link TransactionResult}，缓存结果以保证幂等</li>
 * </ol>
 *
 * <h3>sandbox 模式</h3>
 * <p>当 {@code nexus.exchange-wallet.platform-pubkey} 未配置（空）或
 * {@code nexus.chain.skip-confirmation=true} 时进入 sandbox 模式，不调用真实
 * 签名服务与链上节点，返回 "SIMULATED-..." 形式的模拟交易哈希，便于本地与
 * 测试环境运行。结果以 {@link TransactionResult#isSimulated()} = true 标记。</p>
 *
 * <h3>幂等性</h3>
 * <p>基于 {@link TransactionRequest#getRequestId()} 在进程内缓存执行结果；
 * 相同 requestId 的重复 execute 调用直接返回缓存的 txHash，避免重复上链。
 * 生产环境如需跨进程幂等，应在签名服务侧基于 requestId 做去重。</p>
 */
@Component
public class DefaultOnChainExecutionChannel implements OnChainExecutionChannel {

    private static final Logger log = LoggerFactory.getLogger(DefaultOnChainExecutionChannel.class);

    /** 模拟交易哈希前缀，便于上游识别 sandbox 结果 */
    public static final String SIMULATED_PREFIX = "SIMULATED-";

    private final ChainRpcClient chainRpcClient;
    private final ExchangeWalletClient exchangeWalletClient;
    private final GatewayConfig gatewayConfig;

    /** requestId → 已执行结果，用于幂等控制 */
    private final ConcurrentMap<String, TransactionResult> idempotentCache = new ConcurrentHashMap<>();

    public DefaultOnChainExecutionChannel(ChainRpcClient chainRpcClient,
                                          ExchangeWalletClient exchangeWalletClient,
                                          GatewayConfig gatewayConfig) {
        this.chainRpcClient = chainRpcClient;
        this.exchangeWalletClient = exchangeWalletClient;
        this.gatewayConfig = gatewayConfig;
    }

    @Override
    public TransactionResult execute(TransactionRequest request) {
        validate(request);

        // 幂等：相同 requestId 直接返回缓存
        if (request.getRequestId() != null) {
            TransactionResult cached = idempotentCache.get(request.getRequestId());
            if (cached != null) {
                log.info("execute idempotent hit: requestId={}, txHash={}",
                        request.getRequestId(), cached.getTxHash());
                return cached;
            }
        }

        TransactionResult result;
        if (isSandboxMode()) {
            result = executeSandbox(request);
        } else {
            result = executeProduction(request);
        }

        // 缓存结果以保证幂等
        if (request.getRequestId() != null && result.getTxHash() != null) {
            idempotentCache.put(request.getRequestId(), result);
        }
        return result;
    }

    @Override
    public TransactionResult queryStatus(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return TransactionResult.failure("txHash is null or empty", false);
        }
        // 模拟交易哈希直接返回 SUCCESS
        if (txHash.startsWith(SIMULATED_PREFIX)) {
            return TransactionResult.success(txHash, gatewayConfig.getChain().getConfirmations(), true);
        }
        try {
            boolean confirmed = chainRpcClient.isTransactionConfirmed(txHash);
            if (confirmed) {
                return TransactionResult.success(txHash, gatewayConfig.getChain().getConfirmations(), false);
            }
            return TransactionResult.pending(txHash, 0, false);
        } catch (Exception e) {
            log.warn("queryStatus failed: txHash={}, error={}", txHash, e.getMessage());
            return TransactionResult.failure("query failed: " + e.getMessage(), false);
        }
    }

    // --- 内部方法 ---

    private void validate(TransactionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("TransactionRequest is null");
        }
        if (request.getType() == null) {
            throw new IllegalArgumentException("TransactionRequest.type is null");
        }
        if (request.getFromAddress() == null || request.getFromAddress().isEmpty()) {
            throw new IllegalArgumentException("TransactionRequest.fromAddress is empty");
        }
        if (request.getToAddress() == null || request.getToAddress().isEmpty()) {
            throw new IllegalArgumentException("TransactionRequest.toAddress is empty");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("TransactionRequest.amount must be positive");
        }
    }

    /**
     * 判定是否进入 sandbox / mock 模式：
     * platformPubkey 未配置 或 skipConfirmation=true。
     */
    private boolean isSandboxMode() {
        String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();
        boolean pubkeyMissing = platformPubkey == null || platformPubkey.isEmpty();
        boolean skipConfirm = gatewayConfig.getChain().isSkipConfirmation();
        if (pubkeyMissing) {
            log.debug("sandbox mode active: platformPubkey not configured");
        }
        if (skipConfirm) {
            log.debug("sandbox mode active: skipConfirmation=true");
        }
        return pubkeyMissing || skipConfirm;
    }

    /**
     * sandbox 模式执行：生成模拟交易哈希，不调用真实签名服务与链上节点。
     */
    private TransactionResult executeSandbox(TransactionRequest request) {
        String txHash = SIMULATED_PREFIX + UUID.randomUUID().toString().replace("-", "");
        int confirmations = gatewayConfig.getChain().getConfirmations();
        log.info("executeSandbox: type={}, requestId={}, txHash={}",
                request.getType(), request.getRequestId(), txHash);
        return TransactionResult.success(txHash, confirmations, true);
    }

    /**
     * 生产模式执行：地址 → pubkeyHash → 签名+广播 → 等待确认。
     */
    private TransactionResult executeProduction(TransactionRequest request) {
        String platformPubkey = gatewayConfig.getExchangeWallet().getPlatformPubkey();

        // 1. 将 toAddress 转为 pubkeyHash
        String toPubkeyHash = exchangeWalletClient.addressToPubkeyHash(request.getToAddress());
        if (toPubkeyHash == null || toPubkeyHash.isEmpty()) {
            log.error("executeProduction: addressToPubkeyHash failed for toAddress={}", request.getToAddress());
            return TransactionResult.failure(
                    "cannot resolve pubkeyHash for toAddress: " + request.getToAddress(), false);
        }

        // 2. 调用签名服务完成签名 + 广播，返回 txHash
        String txHash;
        try {
            txHash = exchangeWalletClient.signTransfer(
                    platformPubkey, toPubkeyHash, request.getAmount());
        } catch (Exception e) {
            log.error("executeProduction: signTransfer threw, requestId={}", request.getRequestId(), e);
            return TransactionResult.failure("signTransfer failed: " + e.getMessage(), false);
        }
        if (txHash == null || txHash.isEmpty()) {
            log.error("executeProduction: signTransfer returned null txHash, requestId={}", request.getRequestId());
            return TransactionResult.failure("signTransfer returned null txHash", false);
        }
        log.info("executeProduction: signed and broadcast, requestId={}, txHash={}",
                request.getRequestId(), txHash);

        // 3. 等待确认（同步查询一次；异步轮询由调用方通过 queryStatus 完成）
        boolean confirmed = false;
        try {
            confirmed = chainRpcClient.isTransactionConfirmed(txHash);
        } catch (Exception e) {
            log.warn("executeProduction: isTransactionConfirmed threw, txHash={}: {}", txHash, e.getMessage());
        }

        if (confirmed) {
            return TransactionResult.success(txHash, gatewayConfig.getChain().getConfirmations(), false);
        }
        return TransactionResult.pending(txHash, 0, false);
    }
}