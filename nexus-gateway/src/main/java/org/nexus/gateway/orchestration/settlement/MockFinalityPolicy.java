package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.model.FinalityStatus;

/**
 * Mock 最终性策略 — 即时 FINALIZED，用于测试环境。
 *
 * <p>测试环境下所有支付立即视为最终化，无需等待任何确认。</p>
 *
 * <p>策略名称：{@code "mock-instant"}</p>
 */
public class MockFinalityPolicy implements FinalityPolicy {

    @Override
    public FinalityService.FinalityInfo evaluateFinality(String txHash) {
        return new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 1, 1,
                "mock instant finality (test environment)");
    }

    @Override
    public long getThreshold() {
        return 1;
    }

    @Override
    public String getName() {
        return "mock-instant";
    }
}