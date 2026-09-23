package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.model.FinalityStatus;
import org.nexus.gateway.client.ConsortiumRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PoA 联盟链最终性策略 — 区块一旦入块即视为 FINALIZED（PoA 无重组风险）。
 *
 * <p>PoA（Proof of Authority）共识下，区块由授权节点产出，不存在链分叉/重组，
 * 因此交易一旦入块即可视为不可逆的最终化状态。</p>
 *
 * <p>策略名称：{@code "consortium-poa"}</p>
 */
public class ConsortiumFinalityPolicy implements FinalityPolicy {

    private static final Logger log = LoggerFactory.getLogger(ConsortiumFinalityPolicy.class);

    private final ConsortiumRpcClient consortiumRpc;

    public ConsortiumFinalityPolicy(ConsortiumRpcClient consortiumRpc) {
        this.consortiumRpc = consortiumRpc;
    }

    @Override
    public FinalityService.FinalityInfo evaluateFinality(String txHash) {
        if (consortiumRpc == null) {
            return new FinalityService.FinalityInfo(FinalityStatus.UNKNOWN, 0, 1,
                    "ConsortiumRpcClient not available");
        }
        try {
            boolean confirmed = consortiumRpc.isTransactionConfirmed(txHash);
            if (confirmed) {
                log.debug("Consortium PoA finality: txHash={} → FINALIZED", txHash);
                return new FinalityService.FinalityInfo(FinalityStatus.FINALIZED, 1, 1,
                        "PoA instant finality (block included)");
            }
            log.debug("Consortium PoA finality: txHash={} → UNKNOWN (not yet in block)", txHash);
            return new FinalityService.FinalityInfo(FinalityStatus.UNKNOWN, 0, 1,
                    "transaction not yet in a block");
        } catch (RuntimeException e) {
            log.warn("Consortium PoA finality query failed: txHash={} error={}", txHash, e.getMessage());
            return new FinalityService.FinalityInfo(FinalityStatus.UNKNOWN, 0, 1,
                    "consortium RPC error: " + e.getMessage());
        }
    }

    @Override
    public long getThreshold() {
        return 1;
    }

    @Override
    public String getName() {
        return "consortium-poa";
    }
}