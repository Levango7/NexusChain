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

    /**
     * 共识 epoch 长度（区块数/epoch，默认 32，与 ADR-030 一致）。
     * 用于把交易所在高度换算为该高度所属 epoch，进而查询 BFT 权重最终化进度。
     */
    private final long epochLength;

    public FinalityService(ChainRpcClient chainRpc,
                           @Value("${nexus.finality.blocks-to-finalize:12}") long blocksToFinalize,
                           @Value("${nexus.finality.epoch-length:32}") long epochLength) {
        this.chainRpc = chainRpc;
        this.blocksToFinalize = blocksToFinalize;
        this.epochLength = epochLength > 0 ? epochLength : 32;
    }

    /**
     * 查询一笔链上支付的最终性状态。
     *
     * <p><b>BFT 权重优先、确认数降级</b>（NexFinality 血缘）：
     * <ol>
     *   <li>先查询交易所在高度 → 换算 epoch</li>
     *   <li>调用 core 最终性 RPC 获得 BFT 质押权重进度（voted/total + progress）</li>
     *   <li>最终性层未启用/不可达时，降级为确认数驱动（保留旧语义）</li>
     * </ol>
     * </p>
     *
     * @param txHash 链上交易哈希（connector 内部支付 ID 映射的链上哈希）
     * @return 最终性状态 + 进度（确认数或权重）
     */
    public FinalityInfo getFinality(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return new FinalityInfo(FinalityStatus.UNKNOWN, 0, blocksToFinalize,
                    "no txHash mapped to connector payment");
        }

        // 1) BFT 权重优先：txHash → block_height → epoch → 最终性 RPC
        Map<String, Object> status = chainRpc.getTransactionStatus(txHash);
        if (status != null && status.containsKey("block_height")) {
            long blockHeight = parseLongField(status.get("block_height"), -1L);
            if (blockHeight > 0) {
                long epoch = epochOf(blockHeight);
                Map<String, Object> finality = chainRpc.getEpochFinality(epoch);
                // 仅当 RPC 返回有效 finality_status 时才采用 BFT 权重；
                // 空 Map / 异常 / 未装配（NOT_ACTIVE）一律降级 confirmations
                if (finality != null && finality.get("finality_status") instanceof String
                        && !String.valueOf(finality.get("finality_status")).isEmpty()) {
                    return buildFromWeight(epoch, finality);
                }
            }
        }

        // 2) 降级：确认数驱动（原语义）
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
        log.debug("Finality derived (confirmations fallback): txHash={}, confirmations={}, threshold={}, finality={}",
                txHash, confirmations, blocksToFinalize, finality);
        return new FinalityInfo(finality, confirmations, blocksToFinalize,
                "confirmations-based (NexFinality BFT layer not active)");
    }

    /**
     * 把 BFT 权重进度映射为三层最终性状态。
     */
    private FinalityInfo buildFromWeight(long epoch, Map<String, Object> f) {
        long voted = parseLongField(f.get("voted_weight"), 0L);
        long total = parseLongField(f.get("total_weight"), 0L);
        long progress = parseLongField(f.get("progress_percent"), 0L);
        String rawStatus = String.valueOf(f.get("finality_status"));

        FinalityStatus status;
        if ("FINALIZED".equals(rawStatus) || progress >= 100) {
            status = FinalityStatus.FINALIZED;
        } else if ("FINALIZING".equals(rawStatus) || progress >= 50) {
            status = FinalityStatus.FINALIZING;
        } else if ("OPTIMISTIC".equals(rawStatus) || progress > 0) {
            status = FinalityStatus.OPTIMISTIC;
        } else {
            status = FinalityStatus.UNKNOWN;
        }
        log.debug("Finality derived (BFT weight): epoch={}, voted={}, total={}, progress={}%, status={}",
                epoch, voted, total, progress, status);
        // progress_percent 直接作为 confirmations 值，threshold=100 使
        // FinalityInfo.progressPercent() 返回原始 BFT 百分比（避免整数截断）
        return new FinalityInfo(status, progress, 100, "staking-weight based (NexFinality BFT)");
    }

    private long epochOf(long height) {
        return (height - 1) / epochLength + 1;
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
