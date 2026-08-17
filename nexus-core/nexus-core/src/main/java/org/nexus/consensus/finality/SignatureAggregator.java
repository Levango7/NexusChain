package org.nexus.consensus.finality;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 签名聚合器抽象（ADR-030 M3 架构层）。
 *
 * <p>NexFinality 的性能基石：N 个验证者投票的签名，从"逐一验签 O(N)"降为
 * "聚合后一次验签 O(1)"。本接口定义聚合的<strong>调用契约</strong>，
 * 与具体密码学实现（BLS）解耦。</p>
 *
 * <p>实现分层：</p>
 * <ul>
 *   <li>{@link CollectingAggregator} —— M2 默认实现：仅收集，验签退化为逐一验证（诚实降级）</li>
 *   <li>BlstSignatureAggregator —— M3 目标实现（需 blst jar，见 docs/adr/M3-BLS-blocking-notes.md）</li>
 * </ul>
 *
 * <p><b>设计纪律</b>：FinalityGadget 只依赖此接口；接入 blst 时零改动调用方。</p>
 */
public interface SignatureAggregator {

    /**
     * 聚合多个投票的签名为单一聚合签名。
     *
     * @param votes 同一 epoch、同一检查点的投票集合（签名已由提交者各自产生）
     * @return 聚合签名句柄；null 表示聚合不可用（调用方应回退逐一验证）
     */
    AggregatedSignature aggregate(List<Vote> votes);

    /**
     * 验证聚合签名确实覆盖了所声称的投票集合。
     *
     * @param votes      被聚合的投票集合
     * @param aggregated 聚合签名句柄
     * @return 验证通过返回 true
     */
    boolean verifyAggregate(List<Vote> votes, AggregatedSignature aggregated);

    /**
     * 聚合产物句柄。M2 占位为签名拼接，M3 为 96 字节 G1 点。
     */
    interface AggregatedSignature {
        /** 聚合签名的紧凑表示 */
        byte[] compressed();
        /** 覆盖的签名者数量 */
        int signerCount();
    }

    /**
     * M2 默认实现：收集式聚合（诚实降级）。
     *
     * <p>不做密码学聚合，仅把签名打包并回退为逐一对比，
     * 语义上<strong>不提供</strong> O(1) 验签收益，但保证接口契约可被先行消费与测试。</p>
     */
    final class CollectingAggregator implements SignatureAggregator {

        private static final Logger log = LoggerFactory.getLogger(CollectingAggregator.class);

        /** BLS 签名压缩格式最小长度（G1 点 48 字节，Ed25519 64 字节，下限取 32 字节做格式护栏）。 */
        private static final int MIN_SIGNATURE_BYTES = 32;

        @Override
        public AggregatedSignature aggregate(List<Vote> votes) {
            if (votes == null || votes.isEmpty()) {
                return null;
            }
            int total = 0;
            for (Vote v : votes) {
                total += v.getSignature() == null ? 0 : v.getSignature().length;
            }
            byte[] packed = new byte[total];
            int off = 0;
            for (Vote v : votes) {
                byte[] sig = v.getSignature() == null ? new byte[0] : v.getSignature();
                System.arraycopy(sig, 0, packed, off, sig.length);
                off += sig.length;
            }
            final byte[] packedFinal = packed;
            final int count = votes.size();
            return new AggregatedSignature() {
                @Override public byte[] compressed() { return packedFinal; }
                @Override public int signerCount() { return count; }
            };
        }

        @Override
        public boolean verifyAggregate(List<Vote> votes, AggregatedSignature aggregated) {
            // 收集式聚合：无法比逐一验证更优，诚实返回"需要上层走逐签验证"
            if (aggregated == null || votes == null || aggregated.signerCount() != votes.size()) {
                return false;
            }
            // 格式校验：拒绝无签名/空签名/异常短签名的投票，防止冒充验证人零成本触发 finalized
            // TODO: Phase2 - 接入 blst 做完整 BLS 验签（替换格式校验为密码学验签）
            for (Vote vote : votes) {
                byte[] sig = vote.getSignature();
                if (sig == null || sig.length == 0) {
                    log.warn("Vote from {} has null/empty signature, rejecting", vote.getValidatorAddress());
                    return false;
                }
                if (sig.length < MIN_SIGNATURE_BYTES) {
                    log.warn("Vote from {} has suspiciously short signature: {} bytes",
                            vote.getValidatorAddress(), sig.length);
                    return false;
                }
            }
            return true;
        }
    }
}
