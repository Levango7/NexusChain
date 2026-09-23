package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.model.FinalityStatus;

/**
 * PSP 确认最终性策略 — PSP 返回 SUCCEEDED 即视为 FINALIZED。
 *
 * <p>PSP（Payment Service Provider）通道不依赖链上确认，支付状态由 PSP 直接判定。
 * 只有当 PSP 确认支付成功后才会调用此策略，因此始终返回 FINALIZED。</p>
 *
 * <p>策略名称：{@code "psp-confirm"}</p>
 */
public class PspFinalityPolicy implements FinalityPolicy {

    @Override
    public FinalityService.FinalityInfo evaluateFinality(String txHash) {
        return new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 1, 1,
                "PSP confirmation = finality (no on-chain confirmation needed)");
    }

    @Override
    public long getThreshold() {
        return 1;
    }

    @Override
    public String getName() {
        return "psp-confirm";
    }
}