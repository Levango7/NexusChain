package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.model.FinalityStatus;

/**
 * 公链最终性策略 — 封装 {@link FinalityService} 的 BFT 权重优先 + 确认数降级逻辑。
 *
 * <p>委托 {@link FinalityService#getFinality(String)} 进行最终性判定，
 * 该方法内部实现为：先查询 BFT 权重进度（NexFinality），不可达时降级为确认数驱动。</p>
 *
 * <p>策略名称：{@code "chain-bft"}</p>
 */
public class ChainFinalityPolicy implements FinalityPolicy {

    private final FinalityService finalityService;

    public ChainFinalityPolicy(FinalityService finalityService) {
        this.finalityService = finalityService;
    }

    @Override
    public FinalityService.FinalityInfo evaluateFinality(String txHash) {
        if (finalityService == null) {
            return new FinalityService.FinalityInfo(FinalityStatus.UNKNOWN, 0, 12,
                    "FinalityService not available (compatibility constructor)");
        }
        return finalityService.getFinality(txHash);
    }

    @Override
    public long getThreshold() {
        if (finalityService == null) {
            return 12;
        }
        return finalityService.getBlocksToFinalize();
    }

    @Override
    public String getName() {
        return "chain-bft";
    }
}