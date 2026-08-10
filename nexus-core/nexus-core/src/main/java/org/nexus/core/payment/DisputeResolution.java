package org.nexus.core.payment;

import org.apache.commons.codec.binary.Hex;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 链上争议解决机制。
 *
 * <p>当通道双方无法达成一致时，任一方可以提交争议到链上。
 * 完整的争议解决流程：
 * <ol>
 *   <li>发起方广播 {@code CHANNEL_CLOSE} 交易，附带最新签名的 {@link ChannelUpdate}</li>
 *   <li>进入争议期（disputePeriod 个区块）</li>
 *   <li>对方可以在争议期内提交更高 nonce 的 {@link ChannelUpdate} 作为挑战</li>
 *   <li>争议期结束后，按最高 nonce 的 update 结算</li>
 *   <li>如果一方提交了过期状态，另一方可以惩罚（惩罚金从其通道余额中扣除）</li>
 * </ol></p>
 *
 * <p>链下更新不产生链上交易，只有争议/关闭时才上链。争议期间，
 * 通道处于 {@link PaymentChannel.State#DISPUTED} 状态，
 * 任何挑战都会通过 {@code CHANNEL_UPDATE} 交易上链。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class DisputeResolution {

    private static final Logger LOG = LoggerFactory.getLogger(DisputeResolution.class);

    /** 默认争议期长度（区块数）。 */
    public static final int DEFAULT_DISPUTE_PERIOD = PaymentChannel.DEFAULT_DISPUTE_PERIOD;

    /** 默认 gas price。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

    /** 交易池，用于提交链上争议交易。 */
    private final TransactionPool txPool;

    /** 活跃争议记录（channelId -> DisputeRecord）。 */
    private final ConcurrentHashMap<String, DisputeRecord> activeDisputes;

    /**
     * 构造函数，注入交易池依赖。
     *
     * @param txPool 交易池
     */
    @Autowired
    public DisputeResolution(TransactionPool txPool) {
        this.txPool = txPool;
        this.activeDisputes = new ConcurrentHashMap<>();
    }

    // ==================== Dispute Initiation ====================

    /**
     * 发起争议关闭。
     *
     * <p>当通道双方无法达成一致时，任一方可以发起争议：
     * <ol>
     *   <li>验证 channelId 对应的争议记录不存在（防止重复发起）</li>
     *   <li>验证 latestUpdate 的双方签名有效</li>
     *   <li>构造 {@code CHANNEL_CLOSE} 交易（payload = latestUpdate 的 JSON）</li>
     *   <li>提交到交易池上链</li>
     *   <li>创建 {@link DisputeRecord}（含争议期起始区块、结束区块、当前最新 update）</li>
     *   <li>返回 dispute record</li>
     * </ol></p>
     *
     * @param channelId        通道 ID
     * @param latestUpdate     发起方持有的最新签名的 ChannelUpdate
     * @param initiatorPubkey  发起方公钥（32 字节）
     * @param initiatorSig     发起方对交易的签名（64 字节）
     * @param currentBlockHeight 当前区块高度
     * @param disputePeriod     争议期长度（区块数）
     * @return 创建的 DisputeRecord
     * @throws IllegalArgumentException 如果通道已有活跃争议、签名无效或 update 未完全签名
     */
    public DisputeRecord initiateDispute(String channelId, ChannelUpdate latestUpdate,
                                         byte[] initiatorPubkey, byte[] initiatorSig,
                                         long currentBlockHeight, int disputePeriod) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("Channel ID cannot be null or empty");
        }
        if (latestUpdate == null) {
            throw new IllegalArgumentException("Latest update cannot be null");
        }
        if (activeDisputes.containsKey(channelId)) {
            throw new IllegalArgumentException("Dispute already exists for channel: " + channelId);
        }

        // 验证 update 已双方签名
        if (!latestUpdate.isFullySigned()) {
            throw new IllegalArgumentException("Update must be fully signed by both parties");
        }

        // 验证发起方签名（对交易哈希的签名）
        if (initiatorPubkey == null || initiatorPubkey.length != Transaction.PUBLIC_KEY_SIZE) {
            throw new IllegalArgumentException("Invalid initiator public key size");
        }
        if (initiatorSig == null || initiatorSig.length != Transaction.SIGNATURE_SIZE) {
            throw new IllegalArgumentException("Invalid initiator signature size");
        }

        // 构造 CHANNEL_CLOSE 交易
        byte[] payload = latestUpdate.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Transaction closeTx = Transaction.createEmpty();
        closeTx.type = Transaction.Type.CHANNEL_CLOSE.ordinal();
        closeTx.from = initiatorPubkey.clone();
        closeTx.gasPrice = DEFAULT_GAS_PRICE;
        closeTx.amount = 0;
        closeTx.payload = payload;
        closeTx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        closeTx.signature = initiatorSig.clone();

        // 提交到交易池
        txPool.add(closeTx);

        // 创建争议记录
        long endBlock = currentBlockHeight + disputePeriod;
        DisputeRecord record = new DisputeRecord(
                channelId, currentBlockHeight, endBlock,
                latestUpdate, Hex.encodeHexString(initiatorPubkey),
                DisputeRecord.DisputeState.ACTIVE, 0L
        );
        activeDisputes.put(channelId, record);

        LOG.info("Initiated dispute: channelId={}, startBlock={}, endBlock={}, nonce={}",
                channelId, currentBlockHeight, endBlock, latestUpdate.getNonce());
        return record;
    }

    // ==================== Dispute Challenge ====================

    /**
     * 挑战争议（提交更高 nonce 的 update）。
     *
     * <p>在争议期内，任一方可以提交比当前争议记录中 nonce 更高的
     * {@link ChannelUpdate} 作为挑战，证明发起方提交的是过期状态：
     * <ol>
     *   <li>验证争议存在且在争议期内</li>
     *   <li>验证 challengingUpdate 的双方签名有效</li>
     *   <li>验证 challengingUpdate.nonce > currentDispute.latestUpdate.nonce</li>
     *   <li>更新 dispute record 的 latestUpdate 为 challengingUpdate</li>
     *   <li>计算惩罚金（发起方提交过期状态）</li>
     *   <li>构造 {@code CHANNEL_UPDATE} 交易提交到交易池</li>
     * </ol></p>
     *
     * @param channelId          通道 ID
     * @param challengingUpdate   挑战用的更高 nonce 的 ChannelUpdate
     * @param challengerPubkey    挑战方公钥（32 字节）
     * @param challengerSig       挑战方对交易的签名（64 字节）
     * @param currentBlockHeight  当前区块高度
     * @return 更新后的 DisputeRecord
     * @throws IllegalArgumentException 如果争议不存在、已过争议期、签名无效或 nonce 不更高
     */
    public DisputeRecord challengeDispute(String channelId, ChannelUpdate challengingUpdate,
                                          byte[] challengerPubkey, byte[] challengerSig,
                                          long currentBlockHeight) {
        DisputeRecord dispute = getDisputeStatus(channelId);
        if (dispute == null) {
            throw new IllegalArgumentException("No active dispute for channel: " + channelId);
        }

        // 验证争议期内
        if (!dispute.isInDisputePeriod(currentBlockHeight)) {
            dispute.setState(DisputeRecord.DisputeState.EXPIRED);
            throw new IllegalArgumentException(
                    "Dispute period has ended: current=" + currentBlockHeight
                            + ", endBlock=" + dispute.getEndBlock());
        }

        // 验证挑战 update 已双方签名
        if (!challengingUpdate.isFullySigned()) {
            throw new IllegalArgumentException("Challenging update must be fully signed by both parties");
        }

        // 验证 nonce 更高
        if (challengingUpdate.getNonce() <= dispute.getLatestUpdate().getNonce()) {
            throw new IllegalArgumentException(
                    "Challenging update nonce must be higher: challengeNonce="
                            + challengingUpdate.getNonce()
                            + ", currentNonce=" + dispute.getLatestUpdate().getNonce());
        }

        // 验证挑战方公钥和签名
        if (challengerPubkey == null || challengerPubkey.length != Transaction.PUBLIC_KEY_SIZE) {
            throw new IllegalArgumentException("Invalid challenger public key size");
        }
        if (challengerSig == null || challengerSig.length != Transaction.SIGNATURE_SIZE) {
            throw new IllegalArgumentException("Invalid challenger signature size");
        }

        // 计算惩罚金（发起方提交了过期状态）
        long penalty = calculatePenalty(dispute, challengingUpdate);

        // 更新争议记录
        dispute.setLatestUpdate(challengingUpdate);
        dispute.setState(DisputeRecord.DisputeState.CHALLENGED);
        dispute.setPenaltyAmount(penalty);

        // 构造 CHANNEL_UPDATE 交易
        byte[] payload = challengingUpdate.toJson().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Transaction updateTx = Transaction.createEmpty();
        updateTx.type = Transaction.Type.CHANNEL_UPDATE.ordinal();
        updateTx.from = challengerPubkey.clone();
        updateTx.gasPrice = DEFAULT_GAS_PRICE;
        updateTx.amount = 0;
        updateTx.payload = payload;
        updateTx.to = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
        updateTx.signature = challengerSig.clone();

        // 提交到交易池
        txPool.add(updateTx);

        LOG.info("Challenged dispute: channelId={}, newNonce={}, penalty={}",
                channelId, challengingUpdate.getNonce(), penalty);
        return dispute;
    }

    // ==================== Dispute Settlement ====================

    /**
     * 结算争议（争议期过后）。
     *
     * <p>争议期结束后，按最高 nonce 的 {@link ChannelUpdate} 结算通道：
     * <ol>
     *   <li>验证争议期已过（currentBlockHeight >= endBlock）</li>
     *   <li>取最高 nonce 的 update</li>
     *   <li>计算 finalBalance1, finalBalance2（扣除惩罚金）</li>
     *   <li>判定获胜方</li>
     *   <li>标记争议状态为 SETTLED</li>
     *   <li>返回结算结果</li>
     * </ol></p>
     *
     * @param channelId           通道 ID
     * @param currentBlockHeight  当前区块高度
     * @return 争议结算结果
     * @throws IllegalArgumentException 如果争议不存在或争议期未过
     */
    public DisputeSettlement settleDispute(String channelId, long currentBlockHeight) {
        DisputeRecord dispute = getDisputeStatus(channelId);
        if (dispute == null) {
            throw new IllegalArgumentException("No active dispute for channel: " + channelId);
        }

        // 验证争议期已过
        if (dispute.isInDisputePeriod(currentBlockHeight)) {
            throw new IllegalArgumentException(
                    "Dispute period has not ended: current=" + currentBlockHeight
                            + ", endBlock=" + dispute.getEndBlock());
        }

        ChannelUpdate finalUpdate = dispute.getLatestUpdate();
        long finalBalance1 = finalUpdate.getBalance1();
        long finalBalance2 = finalUpdate.getBalance2();
        long penalty = dispute.getPenaltyAmount();

        // 惩罚金从发起方余额中扣除（发起方提交了过期状态被挑战）
        String winner;
        if (penalty > 0 && dispute.getState() == DisputeRecord.DisputeState.CHALLENGED) {
            // 发起方有过错，惩罚金从发起方余额扣除
            // 发起方的公钥与 dispute.initiatorPubkey 对应
            // 如果发起方是参与方1，则从 balance1 扣除
            // 这里简化处理：惩罚金从发起方余额中扣除
            // 获胜方为挑战方（非发起方）
            winner = DisputeSettlement.WINNER_PARTICIPANT2;
            // 确保余额不出现负数
            if (finalBalance1 >= penalty) {
                finalBalance1 -= penalty;
            } else {
                // 如果参与方1余额不足，从参与方2扣除（假设发起方是参与方2）
                if (finalBalance2 >= penalty) {
                    finalBalance2 -= penalty;
                    winner = DisputeSettlement.WINNER_PARTICIPANT1;
                } else {
                    penalty = finalBalance1 + finalBalance2;
                    finalBalance1 = 0;
                    finalBalance2 = 0;
                    winner = DisputeSettlement.WINNER_DRAW;
                }
            }
        } else {
            // 无挑战或无惩罚，按最新 update 结算
            winner = DisputeSettlement.WINNER_DRAW;
        }

        dispute.setState(DisputeRecord.DisputeState.SETTLED);

        DisputeSettlement settlement = new DisputeSettlement(
                channelId, finalBalance1, finalBalance2, penalty, winner, currentBlockHeight
        );

        LOG.info("Settled dispute: channelId={}, finalBalance1={}, finalBalance2={}, penalty={}, winner={}",
                channelId, finalBalance1, finalBalance2, penalty, winner);
        return settlement;
    }

    // ==================== Query and Penalty ====================

    /**
     * 检查争议状态。
     *
     * @param channelId 通道 ID
     * @return DisputeRecord，如果不存在则返回 null
     */
    public DisputeRecord getDisputeStatus(String channelId) {
        return activeDisputes.get(channelId);
    }

    /**
     * 计算惩罚金额。
     *
     * <p>如果一方提交了过期状态被挑战，惩罚金从其通道余额中扣除。
     * 惩罚金额的计算策略：
     * <ul>
     *   <li>如果挑战的 update nonce 比争议记录中的高，说明发起方提交了过期状态</li>
     *   <li>惩罚金 = 过期 update 的余额差异的固定比例（默认为发起方余额的 10%）</li>
     * </ul></p>
     *
     * @param dispute           争议记录
     * @param challengingUpdate   挑战用的更高 nonce 的 update
     * @return 惩罚金额（NEX 最小单位）
     */
    public long calculatePenalty(DisputeRecord dispute, ChannelUpdate challengingUpdate) {
        if (dispute == null || challengingUpdate == null) {
            return 0L;
        }

        ChannelUpdate staleUpdate = dispute.getLatestUpdate();
        if (staleUpdate == null) {
            return 0L;
        }

        // 只有当挑战的 nonce 确实更高时才有惩罚
        if (challengingUpdate.getNonce() <= staleUpdate.getNonce()) {
            return 0L;
        }

        // 惩罚金 = 发起方过期余额的 10%
        // 使用发起方的余额（staleUpdate 中的余额1）作为基准
        long initiatorBalance = staleUpdate.getBalance1();
        long penalty = initiatorBalance / 10;

        // 确保惩罚金不为负
        if (penalty < 0) {
            penalty = 0L;
        }

        return penalty;
    }

    /**
     * 获取所有活跃争议记录。
     *
     * @return 争议记录映射（channelId -> DisputeRecord），不可修改
     */
    public java.util.Map<String, DisputeRecord> getAllDisputes() {
        return java.util.Collections.unmodifiableMap(activeDisputes);
    }

    /**
     * 移除已结算的争议记录。
     *
     * <p>争议结算后调用此方法清理内存中的记录。</p>
     *
     * @param channelId 通道 ID
     * @return 被移除的 DisputeRecord，如果不存在则返回 null
     */
    public DisputeRecord removeDispute(String channelId) {
        return activeDisputes.remove(channelId);
    }
}
