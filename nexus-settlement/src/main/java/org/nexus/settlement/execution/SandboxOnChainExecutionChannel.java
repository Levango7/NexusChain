package org.nexus.settlement.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * {@link OnChainExecutionChannel} 的 sandbox / fallback 实现，位于 nexus-settlement。
 * <p>
 * 当上层应用（如 nexus-gateway）未提供真实的 {@link OnChainExecutionChannel}
 * 实现时，本类作为默认实现被 Spring 装配（{@link ConditionalOnMissingBean}），
 * 生成模拟交易哈希（"SIMULATED-..." 前缀），保证 settlement 在独立运行 / 测试
 * 环境下也能完成"链上执行"步骤而不抛错。
 * </p>
 *
 * <p>当 nexus-gateway 启动并提供 {@code DefaultOnChainExecutionChannel} 时，
 * 本 bean 不会被装配（{@link ConditionalOnMissingBean} 生效），真实实现接管。</p>
 */
@Component
@ConditionalOnMissingBean(OnChainExecutionChannel.class)
public class SandboxOnChainExecutionChannel implements OnChainExecutionChannel {

    private static final Logger log = LoggerFactory.getLogger(SandboxOnChainExecutionChannel.class);

    /** 模拟交易哈希前缀 */
    public static final String SIMULATED_PREFIX = "SIMULATED-";

    @Override
    public TransactionResult execute(TransactionRequest request) {
        if (request == null) {
            return TransactionResult.failure("TransactionRequest is null", true);
        }
        String txHash = SIMULATED_PREFIX + UUID.randomUUID().toString().replace("-", "");
        log.info("sandbox execute: type={}, requestId={}, txHash={}",
                request.getType(), request.getRequestId(), txHash);
        return TransactionResult.success(txHash, 0, true);
    }

    @Override
    public TransactionResult queryStatus(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return TransactionResult.failure("txHash is null or empty", true);
        }
        if (txHash.startsWith(SIMULATED_PREFIX)) {
            return TransactionResult.success(txHash, 0, true);
        }
        return TransactionResult.pending(txHash, 0, true);
    }
}