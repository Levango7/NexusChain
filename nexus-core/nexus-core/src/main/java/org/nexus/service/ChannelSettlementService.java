package org.nexus.service;

import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.Block;
import org.nexus.core.TransactionPool;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.ChannelSettlement;
import org.nexus.core.payment.ChannelUpdate;
import org.nexus.core.payment.DisputeRecord;
import org.nexus.core.payment.DisputeResolution;
import org.nexus.core.payment.DisputeSettlement;
import org.nexus.core.payment.ChannelManager;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.db.StateDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付通道结算服务。
 *
 * <p>通道关闭时，根据最终余额分配锁定资金：
 * <ol>
 *   <li>协作关闭：双方签名同意最终余额，直接结算</li>
 *   <li>争议关闭：争议期过后，按最高 nonce 的 update 结算</li>
 *   <li>单方关闭：一方提交最终状态，等待争议期后结算</li>
 * </ol></p>
 *
 * <p>结算产生两笔 {@code TRANSFER} 交易，将通道资金分配给双方。
 * 惩罚金（如有）从过错方余额中扣除。本服务为 NEX 支付通道的
 * 终局结算层，依赖 {@link ChannelManager} 获取通道状态，
 * 依赖 {@link DisputeResolution} 处理争议流程，
 * 依赖 {@link TransactionPool} 提交链上交易。</p>
 *
 * <p>典型流程：
 * <ul>
 *   <li>协作关闭：{@link #cooperativeClose} 直接构造 CHANNEL_CLOSE 交易
 *       并分配资金</li>
 *   <li>单方关闭：{@link #unilateralClose} 通过
 *       {@link DisputeResolution#initiateDispute} 发起争议，
 *       争议期过后调用 {@link #settleChannel} 结算</li>
 *   <li>过期关闭：通道锁定期到期后调用 {@link #forceExpire}
 *       按当前余额分配</li>
 * </ul></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class ChannelSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelSettlementService.class);

    /** 交易版本号，固定为 1。 */
    private static final int TX_VERSION = Transaction.DEFAULT_TRANSACTION_VERSION;

    /** 默认 gasPrice，按最小单位计费。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

    /** 交易池，用于提交链上结算交易。 */
    @Autowired
    private TransactionPool txPool;

    /** 通道管理器，用于查询通道状态。 */
    @Autowired
    private ChannelManager channelManager;

    /** 争议解决组件，用于发起与结算争议。 */
    @Autowired
    private DisputeResolution disputeResolution;

    /** 状态数据库，用于查询当前最佳区块高度。 */
    @Autowired
    private StateDB stateDB;

    /** 结算结果存储（channelId -> ChannelSettlement）。 */
    private final ConcurrentHashMap<String, ChannelSettlement> settlements;

    /**
     * 默认构造函数，初始化内部存储。
     */
    public ChannelSettlementService() {
        this.settlements = new ConcurrentHashMap<>();
    }

    // ==================== Cooperative Close ====================

    /**
     * 协作关闭通道。
     *
     * <p>双方签名同意最终余额后，直接结算通道资金：
     * <ol>
     *   <li>获取通道，验证状态为 {@link PaymentChannel.State#OPEN}</li>
     *   <li>验证余额守恒（finalBalance1 + finalBalance2 == channel.getTotalBalance()）</li>
     *   <li>验证双方签名（对 channelId + nonce + finalBalance1 + finalBalance2 的签名）</li>
     *   <li>构造 CHANNEL_CLOSE 交易（payload = ChannelUpdate JSON）并提交到交易池</li>
     *   <li>执行资金分配：构造 2 笔 TRANSFER 交易分配给双方</li>
     *   <li>更新通道状态为 CLOSED</li>
     *   <li>返回结算详情</li>
     * </ol></p>
     *
     * @param channelId     通道 ID
     * @param finalBalance1   参与方一最终余额（NEX 最小单位）
     * @param finalBalance2   参与方二最终余额
     * @param nonce           最终状态 nonce
     * @param sig1            参与方一签名（64 字节 Ed25519 签名）
     * @param sig2            参与方二签名（64 字节 Ed25519 签名）
     * @param fromPubkey1     参与方一公钥（32 字节）
     * @param fromPubkey2     参与方二公钥（32 字节）
     * @return 统一响应结果，data 中含 {@link ChannelSettlement} 结算详情
     */
    public APIResult cooperativeClose(String channelId, long finalBalance1, long finalBalance2,
                                        long nonce, byte[] sig1, byte[] sig2,
                                        byte[] fromPubkey1, byte[] fromPubkey2) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            PaymentChannel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return APIResult.newFailResult(APIResult.FAIL, "Channel not found: " + channelId);
            }
            if (channel.getState() != PaymentChannel.State.OPEN) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel is not OPEN, current state: " + channel.getState());
            }

            // 余额守恒验证
            long totalBalance = channel.getTotalBalance();
            if (finalBalance1 < 0 || finalBalance2 < 0) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Balances must be non-negative: balance1=" + finalBalance1
                                + ", balance2=" + finalBalance2);
            }
            if (finalBalance1 + finalBalance2 != totalBalance) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Balance conservation violated: expected=" + totalBalance
                                + ", actual=" + (finalBalance1 + finalBalance2));
            }

            // 验证双方签名
            if (sig1 == null || sig1.length != Transaction.SIGNATURE_SIZE
                    || sig2 == null || sig2.length != Transaction.SIGNATURE_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL, "Invalid signature size");
            }
            if (fromPubkey1 == null || fromPubkey1.length != Transaction.PUBLIC_KEY_SIZE
                    || fromPubkey2 == null || fromPubkey2.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL, "Invalid public key size");
            }

            // 构造 ChannelUpdate 用于签名验证（签名内容 = channelId + nonce + balance1 + balance2）
            ChannelUpdate finalUpdate = new ChannelUpdate(
                    channelId, nonce, finalBalance1, finalBalance2,
                    sig1, sig2, System.currentTimeMillis()
            );

            // 验证双方 Ed25519 签名（对 Keccak-256 哈希后的消息签名）
            byte[] messageHash = HashUtil.keccak256(finalUpdate.getMessageToSign());
            Ed25519PublicKey pk1 = new Ed25519PublicKey(fromPubkey1);
            Ed25519PublicKey pk2 = new Ed25519PublicKey(fromPubkey2);
            if (!pk1.verify(messageHash, sig1) || !pk2.verify(messageHash, sig2)) {
                return APIResult.newFailResult(APIResult.FAIL, "Signature verification failed");
            }

            long blockHeight = safeBestHeight();

            // 构造 CHANNEL_CLOSE 交易（payload = ChannelUpdate JSON）
            byte[] payload = finalUpdate.toJson().getBytes(StandardCharsets.UTF_8);
            byte[] placeholderFrom = new byte[Transaction.PUBLIC_KEY_SIZE];
            byte[] placeholderTo = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];

            Transaction closeTx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.CHANNEL_CLOSE.ordinal(),
                    nonce,
                    placeholderFrom,
                    DEFAULT_GAS_PRICE,
                    totalBalance,
                    payload,
                    placeholderTo,
                    new byte[Transaction.SIGNATURE_SIZE]
            );
            txPool.add(closeTx);

            // 请求关闭并完成结算
            channel.requestClose(blockHeight);
            channel.close(blockHeight);

            // 执行资金分配
            String[] txHashes = distributeFunds(channel, finalBalance1, finalBalance2, 0L);

            // 构造结算结果
            ChannelSettlement settlement = new ChannelSettlement(
                    channelId, finalBalance1, finalBalance2, 0L,
                    ChannelSettlement.TYPE_COOPERATIVE, ChannelSettlement.WINNER_DRAW,
                    blockHeight, txHashes[0], txHashes[1],
                    finalBalance1 + finalBalance2
            );
            settlements.put(channelId, settlement);

            LOG.info("Cooperative close: channelId={}, balance1={}, balance2={}, txHash1={}, txHash2={}",
                    channelId, finalBalance1, finalBalance2, txHashes[0], txHashes[1]);

            return APIResult.newSuccess(settlement);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to cooperative close: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to cooperative close: " + e.getMessage());
        }
    }

    // ==================== Unilateral Close ====================

    /**
     * 单方关闭通道。
     *
     * <p>一方提交最终状态，发起争议流程，等待争议期后结算：
     * <ol>
     *   <li>验证通道状态</li>
     *   <li>验证 closer 签名</li>
     *   <li>通过 {@link DisputeResolution#initiateDispute} 发起争议</li>
     *   <li>返回争议期信息</li>
     * </ol></p>
     *
     * <p>争议期过后，需调用 {@link #settleChannel} 完成结算。</p>
     *
     * @param channelId      通道 ID
     * @param latestUpdate     发起方持有的最新双方签名 ChannelUpdate
     * @param closerPubkey     发起方公钥（32 字节）
     * @param closerSig        发起方对交易的签名（64 字节）
     * @return 统一响应结果，data 中含争议期信息
     */
    public APIResult unilateralClose(String channelId, ChannelUpdate latestUpdate,
                                      byte[] closerPubkey, byte[] closerSig) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }
            if (latestUpdate == null) {
                return APIResult.newFailResult(APIResult.FAIL, "latestUpdate is required");
            }
            if (closerPubkey == null || closerPubkey.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL, "Invalid closer public key size");
            }
            if (closerSig == null || closerSig.length != Transaction.SIGNATURE_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL, "Invalid closer signature size");
            }

            PaymentChannel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return APIResult.newFailResult(APIResult.FAIL, "Channel not found: " + channelId);
            }
            if (channel.getState() != PaymentChannel.State.OPEN) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel is not OPEN, current state: " + channel.getState());
            }

            // 验证 update 已双方签名
            if (!latestUpdate.isFullySigned()) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Update must be fully signed by both parties");
            }

            long blockHeight = safeBestHeight();
            int disputePeriod = channel.getDisputePeriod() > 0
                    ? channel.getDisputePeriod() : PaymentChannel.DEFAULT_DISPUTE_PERIOD;

            // 通过 DisputeResolution 发起争议
            DisputeRecord record = disputeResolution.initiateDispute(
                    channelId, latestUpdate, closerPubkey, closerSig,
                    blockHeight, disputePeriod
            );

            // 通道进入争议状态
            channel.requestClose(blockHeight);
            channel.dispute();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("channelId", channelId);
            data.put("settlementType", ChannelSettlement.TYPE_UNILATERAL);
            data.put("disputeStartBlock", record.getStartBlock());
            data.put("disputeEndBlock", record.getEndBlock());
            data.put("disputePeriod", disputePeriod);
            data.put("nonce", latestUpdate.getNonce());
            data.put("state", channel.getState().name());
            data.put("message", "Dispute initiated, settle after block " + record.getEndBlock());

            LOG.info("Unilateral close: channelId={}, disputeStart={}, disputeEnd={}",
                    channelId, record.getStartBlock(), record.getEndBlock());

            return APIResult.newSuccess(data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to unilateral close: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to unilateral close: " + e.getMessage());
        }
    }

    // ==================== Settle Dispute Channel ====================

    /**
     * 结算争议通道。
     *
     * <p>争议期过后，按最高 nonce 的 update 结算通道：
     * <ol>
     *   <li>调用 {@link DisputeResolution#settleDispute} 结算争议</li>
     *   <li>获取 {@link DisputeSettlement}（含 finalBalance1, finalBalance2, penalty）</li>
     *   <li>执行资金分配</li>
     *   <li>更新通道状态为 CLOSED</li>
     *   <li>返回结算结果</li>
     * </ol></p>
     *
     * @param channelId           通道 ID
     * @param currentBlockHeight    当前区块高度
     * @return 统一响应结果，data 中含 {@link ChannelSettlement} 结算详情
     */
    public APIResult settleChannel(String channelId, long currentBlockHeight) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            PaymentChannel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return APIResult.newFailResult(APIResult.FAIL, "Channel not found: " + channelId);
            }
            if (channel.getState() != PaymentChannel.State.DISPUTED
                    && channel.getState() != PaymentChannel.State.CLOSING) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel is not in DISPUTED/CLOSING state, current: " + channel.getState());
            }

            // 调用 DisputeResolution 结算争议
            DisputeSettlement ds = disputeResolution.settleDispute(channelId, currentBlockHeight);

            long finalBalance1 = ds.getFinalBalance1();
            long finalBalance2 = ds.getFinalBalance2();
            long penalty = ds.getPenaltyAmount();

            // 执行资金分配
            String[] txHashes = distributeFunds(channel, finalBalance1, finalBalance2, penalty);

            // 更新通道状态为 CLOSED
            channel.setState(PaymentChannel.State.CLOSED);

            ChannelSettlement settlement = new ChannelSettlement(
                    channelId, finalBalance1, finalBalance2, penalty,
                    ChannelSettlement.TYPE_DISPUTE, ds.getWinner(),
                    currentBlockHeight, txHashes[0], txHashes[1],
                    finalBalance1 + finalBalance2
            );
            settlements.put(channelId, settlement);

            // 清理争议记录
            disputeResolution.removeDispute(channelId);

            LOG.info("Settled dispute channel: channelId={}, balance1={}, balance2={}, penalty={}, winner={}",
                    channelId, finalBalance1, finalBalance2, penalty, ds.getWinner());

            return APIResult.newSuccess(settlement);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to settle channel: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to settle channel: " + e.getMessage());
        }
    }

    // ==================== Fund Distribution ====================

    /**
     * 执行资金分配（内部方法）。
     *
     * <p>构造两笔 {@code TRANSFER} 交易，将通道资金分配给双方：
     * <ol>
     *   <li>构造 TRANSFER 交易 1：从通道地址 -> participant1，金额 = finalBalance1</li>
     *   <li>构造 TRANSFER 交易 2：从通道地址 -> participant2，金额 = finalBalance2</li>
     *   <li>惩罚金（如有）已由调用方从过错方余额中扣除，
     *       此处按传入的最终净余额分配</li>
     *   <li>提交到交易池</li>
     * </ol></p>
     *
     * <p>注意：传入的 finalBalance1 / finalBalance2 应为扣除惩罚金后的
     * 最终净余额。通道地址在骨架实现中以零公钥占位，
     * 实际由钱包层在签名时填充。</p>
     *
     * @param channel        支付通道
     * @param finalBalance1    参与方一最终净余额
     * @param finalBalance2    参与方二最终净余额
     * @param penaltyAmount    惩罚金额（仅用于日志记录，不重复扣除）
     * @return 长度为 2 的数组，[0]=分配给 participant1 的交易哈希，[1]=分配给 participant2 的交易哈希
     */
    private String[] distributeFunds(PaymentChannel channel, long finalBalance1, long finalBalance2,
                                      long penaltyAmount) {
        String[] txHashes = new String[2];

        // 通道地址占位（零公钥，实际由钱包层签名时填充）
        byte[] channelAddr = new byte[Transaction.PUBLIC_KEY_SIZE];
        byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

        // 解析参与方地址（公钥哈希十六进制 -> 20 字节）
        byte[] to1;
        byte[] to2;
        try {
            to1 = Hex.decodeHex(channel.getParticipant1());
            to2 = Hex.decodeHex(channel.getParticipant2());
        } catch (org.apache.commons.codec.DecoderException e) {
            throw new IllegalArgumentException(
                    "Invalid participant address format: " + e.getMessage(), e);
        }

        // TRANSFER 交易 1：通道 -> participant1
        if (finalBalance1 > 0) {
            Transaction tx1 = new Transaction(
                    TX_VERSION,
                    Transaction.Type.TRANSFER.ordinal(),
                    0L,
                    channelAddr,
                    DEFAULT_GAS_PRICE,
                    finalBalance1,
                    null,
                    to1,
                    emptySig
            );
            txPool.add(tx1);
            txHashes[0] = tx1.getHashHexString();
        } else {
            txHashes[0] = null;
        }

        // TRANSFER 交易 2：通道 -> participant2
        if (finalBalance2 > 0) {
            Transaction tx2 = new Transaction(
                    TX_VERSION,
                    Transaction.Type.TRANSFER.ordinal(),
                    0L,
                    channelAddr,
                    DEFAULT_GAS_PRICE,
                    finalBalance2,
                    null,
                    to2,
                    emptySig
            );
            txPool.add(tx2);
            txHashes[1] = tx2.getHashHexString();
        } else {
            txHashes[1] = null;
        }

        LOG.info("Distributed funds: channelId={}, balance1={}, balance2={}, penalty={}",
                channel.getChannelId(), finalBalance1, finalBalance2, penaltyAmount);

        return txHashes;
    }

    // ==================== Query ====================

    /**
     * 查询结算状态。
     *
     * <p>返回指定通道的结算结果。如果通道尚未结算，返回通道当前状态。</p>
     *
     * @param channelId 通道 ID
     * @return 统一响应结果，data 中含 {@link ChannelSettlement} 或通道状态信息
     */
    public APIResult getSettlementStatus(String channelId) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            ChannelSettlement settlement = settlements.get(channelId);
            if (settlement != null) {
                return APIResult.newSuccess(settlement);
            }

            // 通道尚未结算，返回当前状态
            PaymentChannel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel not found and no settlement record: " + channelId);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("channelId", channelId);
            data.put("state", channel.getState().name());
            data.put("balance1", channel.getBalance1());
            data.put("balance2", channel.getBalance2());
            data.put("nonce", channel.getNonce());
            data.put("settled", false);

            // 如果存在争议记录，附加争议信息
            DisputeRecord dispute = disputeResolution.getDisputeStatus(channelId);
            if (dispute != null) {
                data.put("disputeStartBlock", dispute.getStartBlock());
                data.put("disputeEndBlock", dispute.getEndBlock());
                data.put("disputeState", dispute.getState().name());
                data.put("disputePenalty", dispute.getPenaltyAmount());
            }

            return APIResult.newSuccess(data);
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to query settlement status: " + e.getMessage());
        }
    }

    // ==================== Force Expire ====================

    /**
     * 强制过期关闭（通道锁定期到期后）。
     *
     * <p>通道锁定期到期后，按当前余额分配资金并标记为 EXPIRED：
     * <ol>
     *   <li>验证通道锁定期已过（currentBlockHeight >= lockTime）</li>
     *   <li>按当前余额分配资金</li>
     *   <li>标记通道状态为 EXPIRED</li>
     * </ol></p>
     *
     * @param channelId           通道 ID
     * @param currentBlockHeight    当前区块高度
     * @return 统一响应结果，data 中含 {@link ChannelSettlement} 结算详情
     */
    public APIResult forceExpire(String channelId, long currentBlockHeight) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            PaymentChannel channel = channelManager.getChannel(channelId);
            if (channel == null) {
                return APIResult.newFailResult(APIResult.FAIL, "Channel not found: " + channelId);
            }
            if (channel.getState() != PaymentChannel.State.OPEN) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel is not OPEN, current state: " + channel.getState());
            }

            // 验证锁定期已过
            if (channel.getLockTime() <= 0) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "Channel has no lockTime set, cannot force expire");
            }
            if (currentBlockHeight < channel.getLockTime()) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "LockTime not yet reached: current=" + currentBlockHeight
                                + ", lockTime=" + channel.getLockTime());
            }

            long finalBalance1 = channel.getBalance1();
            long finalBalance2 = channel.getBalance2();

            // 执行资金分配
            String[] txHashes = distributeFunds(channel, finalBalance1, finalBalance2, 0L);

            // 标记为 EXPIRED
            channel.expire(currentBlockHeight);

            ChannelSettlement settlement = new ChannelSettlement(
                    channelId, finalBalance1, finalBalance2, 0L,
                    ChannelSettlement.TYPE_EXPIRED, ChannelSettlement.WINNER_DRAW,
                    currentBlockHeight, txHashes[0], txHashes[1],
                    finalBalance1 + finalBalance2
            );
            settlements.put(channelId, settlement);

            LOG.info("Force expired channel: channelId={}, balance1={}, balance2={}, block={}",
                    channelId, finalBalance1, finalBalance2, currentBlockHeight);

            return APIResult.newSuccess(settlement);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to force expire channel: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Failed to force expire channel: " + e.getMessage());
        }
    }

    // ==================== Private Helpers ====================

    /**
     * 安全获取当前最佳区块高度。
     *
     * <p>如果 {@link StateDB} 不可用或查询失败，返回 0。</p>
     *
     * @return 当前最佳区块高度，查询失败返回 0
     */
    private long safeBestHeight() {
        try {
            if (stateDB == null) {
                return 0L;
            }
            Block best = stateDB.getBestBlock();
            return best != null ? best.getnHeight() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }
}
