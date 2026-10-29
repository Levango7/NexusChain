package org.nexus.settlement.execution;

/**
 * 统一链上执行通道接口。
 * <p>
 * 打通 settlement / gateway / wallet 等业务模块到链上"最后一公里"的执行管道：
 * 构造交易 → 请求签名 → 广播 → 等待确认 → 返回结果。所有需要发起链上转账的
 * 业务（结算、退款、提币、归集）都应通过本接口执行，避免每个模块各自实现
 * 签名 / 广播 / 确认的细节。
 * </p>
 *
 * <p>实现位置：接口定义在 {@code nexus-settlement} 中（处于依赖链下层，
 * gateway / wallet 均可访问）；默认实现 {@code DefaultOnChainExecutionChannel}
 * 在 {@code nexus-gateway} 中，注入 ChainRpcClient 与签名服务。</p>
 *
 * <p>幂等性：实现应基于 {@link TransactionRequest#getRequestId()} 做幂等控制，
 * 同一 requestId 多次执行应返回同一 txHash，避免重复上链。</p>
 *
 * <p>sandbox / mock 模式：当真实签名服务不可用时，实现可降级为直接通过
 * ChainRpcClient 广播或返回模拟交易哈希（结果以 {@link TransactionResult#isSimulated()}
 * 标记），便于本地与测试环境运行。</p>
 */
public interface OnChainExecutionChannel {

    /**
     * 执行一笔链上转账。
     * <p>
     * 完整流程：构造交易 → 请求签名 → 广播 → 等待确认 → 返回结果。
     * 实现可基于 {@link TransactionRequest#getRequestId()} 做幂等控制。
     * </p>
     *
     * @param request 链上交易请求，不允许为 null
     * @return 执行结果；不会返回 null，失败时 {@link TransactionResult#getStatus()} 为 FAILED
     * @throws IllegalArgumentException 当 request 为 null 或关键字段非法时
     */
    TransactionResult execute(TransactionRequest request);

    /**
     * 查询指定交易哈希的链上确认状态。
     * <p>
     * 用于异步确认场景：execute 返回 PENDING_CONFIRMATION 后，调用方轮询本方法
     * 直到状态变为 SUCCESS 或超时。
     * </p>
     *
     * @param txHash 链上交易哈希，不允许为 null
     * @return 查询结果；查询失败时返回 {@link TransactionResult#failure}，
     *         不抛异常（便于轮询）
     */
    TransactionResult queryStatus(String txHash);

    /**
     * 预检：给定收款地址是否可用于链上转账。
     * <p>
     * <b>为什么需要这个预检</b>：调用方（结算引擎）在发起链上转账<b>之前</b>就已经
     * 提交了不可回滚的副作用（复式记账走独立事务当场提交，见
     * {@code Ledger#bookSettlement}）。若目标地址不可用这件事在转账时才暴露，就会留下
     * 「已记账、必失败」的永久偏移——终态 FAILED 不可重试，也没有冲正能力。
     * 因此把「地址可用性」的判定前移到副作用之前，由本方法承担。
     * </p>
     *
     * <p>默认实现只做非空校验（适用于不需要真实地址的 sandbox / mock 实现）；
     * 真实实现应覆盖本方法并做可解析性校验（例如地址 → pubkeyHash 是否成功）。
     * 使用 default 方法是为了不破坏既有实现的二进制兼容。</p>
     *
     * @param toAddress 目标收款地址，允许为 null
     * @return 可用于转账返回 true；null / 空白 / 不可解析返回 false
     */
    default boolean canTransferTo(String toAddress) {
        return toAddress != null && !toAddress.isBlank();
    }
}