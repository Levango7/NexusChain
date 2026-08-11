package org.nexus.gateway.orchestration.settlement;

import org.nexus.gateway.client.ChainRpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * 支付最终性推导服务（NexFinality 网关侧原型）。
 *
 * <p>把链上确认数（confirmations）映射为 {@link FinalityStatus} 三层状态：</p>
 * <pre>
 *   confirmations == 0                     → UNKNOWN（未入块或查询失败）
 *   1 ≤ confirmations &lt; halfThreshold       → OPTIMISTIC（已确认但可被重组）
 *   halfThreshold ≤ confirmations &lt; threshold → FINALIZING（趋向最终化）
 *   confirmations ≥ threshold              → FINALIZED（不可逆）
 * </pre>
 *
 * <p>阈值配置：</p>
 * <ul>
 *   <li>{@code nexus.finality.blocks-to-finalize}（默认 12）——最终化所需确认数</li>
 *   <li>半数点为 FINALIZING 起点</li>
 * </ul>
 *
 * <p>血缘与可追溯性：</p>
 * <ul>
 *   <li>当前实现为块数驱动；接入 NexFinality 后切换为 BFT 投票权重驱动</li>
 *   <li>对外 API 契约不变，只改内部判定逻辑</li>
 * </ul>
 */
@Service
public class FinalityService {

    private static final Logger log = LoggerFactory.getLogger(FinalityService.class);

    private final ChainRpcClient chainRpc;

    /**
     * 最终化所需的最小确认数（默认 12 块，约 1 小时 @ 30s/块）。
     * 治理可调整为大额结算场景采取更高的阈值。
     */
    private final long blocksToFinalize;

    public FinalityService(ChainRpcClient chainRpc,
                           @Value("${nexus.finality.blocks-to-finalize:12}") long blocksToFinalize) {
        this.chainRpc = chainRpc;
        this.blocksToFinalize = blocksToFinalize;
    }

    /**
     * 查询一笔链上支付的最终性状态。
     *
     * @param txHash 链上交易哈希（connector 内部支付 ID 映射的链上哈希）
     * @return 最终性状态 + 原始确认数（供前端展示进度）
     */
    public FinalityInfo getFinality(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return new FinalityInfo(FinalityStatus.UNKNOWN, 0, blocksToFinalize,
                    "no txHash mapped to connector payment");
        }

        Map<String, Object> status = chainRpc.getTransactionStatus(txHash);
        if (status == null || !status.containsKey("confirmations")) {
            return new FinalityInfo(FinalityStatus.UNKNOWN, 0, blocksToFinalize,
                    "chain unreachable or tx not found");
        }

        long confirmations = parseLongField(status.get("confirmations"), 0L);
        String chainStatus = String.valueOf(status.get("status"));

        if (confirmations <= 0 || "NOT_FOUND".equals(chainStatus)) {
            return new FinalityInfo(FinalityStatus.UNKNOWN, confirmations, blocksToFinalize,
                    "transaction not yet in a block");
        }

        long half = Math.max(blocksToFinalize / 2, 1);
        FinalityStatus finality;
        if (confirmations >= blocksToFinalize) {
            finality = FinalityStatus.FINALIZED;
        } else if (confirmations >= half) {
            finality = FinalityStatus.FINALIZING;
        } else {
            finality = FinalityStatus.OPTIMISTIC;
        }

        log.debug("Finality derived: txHash={}, confirmations={}, threshold={}, finality={}",
                txHash, confirmations, blocksToFinalize, finality);
        return new FinalityInfo(finality, confirmations, blocksToFinalize,
                "block-count based; will upgrade to BFT vote weight after NexFinality");
    }

    private long parseLongField(Object value, long defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 最终性信息载体：状态 + 原始确认数 + 阈值 + 备注。
     */
    public record FinalityInfo(
            FinalityStatus status,          // 三层最终性状态
            long confirmations,              // 当前链上确认数
            long threshold,                  // 最终化阈值（用于计算百分比）
            String note                      // 语义备注（供前端展示）
    ) {
        /**
         * 确认进度百分比（0-100），供前端做实时进度条。
         */
        public int progressPercent() {
            if (threshold <= 0) return status == FinalityStatus.FINALIZED ? 100 : 0;
            return (int) Math.min(100, confirmations * 100 / threshold);
        }
    }
}
