package org.nexus.gateway.orchestration.settlement;

/**
 * 最终性策略接口 — 不同支付通道（链、联盟链、PSP、Mock）的最终性判定抽象。
 *
 * <p>每个 {@code FinalityPolicy} 实现封装了一种特定通道的最终性推导逻辑，
 * 使 {@code Connector} 可以通过统一接口查询交易的最终性状态，而无需关心
 * 底层通道的具体共识机制（BFT、PoA、PSP 确认等）。</p>
 *
 * <p>已知实现：</p>
 * <ul>
 *   <li>{@link ChainFinalityPolicy} — 公链 BFT 权重优先 + 确认数降级</li>
 *   <li>{@link ConsortiumFinalityPolicy} — PoA 联盟链即时最终性</li>
 *   <li>{@link PspFinalityPolicy} — PSP 确认即最终化</li>
 *   <li>{@link MockFinalityPolicy} — Mock 即时最终化（测试用）</li>
 * </ul>
 */
public interface FinalityPolicy {

    /**
     * 评估指定交易的最终性状态。
     *
     * @param txHash 交易哈希（或 connector 内部支付 ID 映射的链上哈希）
     * @return 最终性信息（状态 + 确认进度 + 阈值 + 备注）
     */
    FinalityService.FinalityInfo evaluateFinality(String txHash);

    /**
     * 返回该策略的最终化阈值（确认数或权重百分比）。
     *
     * @return 最终化阈值
     */
    long getThreshold();

    /**
     * 返回策略名称，用于日志和可观测性。
     *
     * @return 策略名称（如 "chain-bft", "consortium-poa", "psp-confirm", "mock-instant"）
     */
    String getName();
}