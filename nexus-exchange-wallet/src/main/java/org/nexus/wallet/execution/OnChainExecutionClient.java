package org.nexus.wallet.execution;

/**
 * Wallet 端链上执行通道客户端接口。
 * <p>
 * wallet 是独立服务，不依赖 settlement/gateway 的进程内接口，通过 HTTP 调用
 * gateway 的 {@code /api/v1/execution} 端点完成链上交易执行。
 * </p>
 *
 * <p>本接口与 settlement 中的 {@code OnChainExecutionChannel} 在方法语义上
 * 保持一致，便于未来若 wallet 并入统一构建时直接替换。</p>
 */
public interface OnChainExecutionClient {

    /**
     * 通过 HTTP 调用 gateway 执行一笔链上转账。
     *
     * @param request 交易请求
     * @return 执行结果；HTTP 调用失败时返回 FAILED 结果而非抛异常
     */
    WalletTransactionResult execute(WalletTransactionRequest request);

    /**
     * 通过 HTTP 查询指定交易哈希的链上确认状态。
     *
     * @param txHash 交易哈希
     * @return 查询结果；HTTP 调用失败时返回 FAILED 结果而非抛异常
     */
    WalletTransactionResult queryStatus(String txHash);
}