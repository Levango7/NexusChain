package org.nexus.service;

import org.apache.commons.codec.DecoderException;
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
 * <p>支付扩展类型（0x10–0x1a）当前保留未启用：四个写端点保留路由与
 * 入参校验，但不再构造 CHANNEL_CLOSE / CHANNEL_UPDATE / TRANSFER 交易，
 * 也不修改通道状态，统一返回
 * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}。</p>
 *
 * <p>查询接口（{@link #getSettlementStatus}）仍可用。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class ChannelSettlementService {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelSettlementService.class);

    /** 通道管理器，用于查询通道状态。 */
    @Autowired
    private ChannelManager channelManager;

    /** 争议解决组件，用于查询争议状态。 */
    @Autowired
    private DisputeResolution disputeResolution;

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
        if (channelId == null || channelId.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
        }
        if (finalBalance1 < 0 || finalBalance2 < 0) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Balances must be non-negative: balance1=" + finalBalance1
                            + ", balance2=" + finalBalance2);
        }
        if (sig1 == null || sig1.length != Transaction.SIGNATURE_SIZE
                || sig2 == null || sig2.length != Transaction.SIGNATURE_SIZE) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid signature size");
        }
        if (fromPubkey1 == null || fromPubkey1.length != Transaction.PUBLIC_KEY_SIZE
                || fromPubkey2 == null || fromPubkey2.length != Transaction.PUBLIC_KEY_SIZE) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid public key size");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 CHANNEL_CLOSE / TRANSFER 交易，
        // 也不会修改通道状态（保持 OPEN）。
        return APIResult.paymentExtensionNotEnabled();
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

        // 支付扩展（0x10–0x1a）保留未启用：不再发起争议、不构造 CHANNEL_UPDATE/CLOSE 交易，
        // 也不会修改通道状态（保持 OPEN），不产生 DisputeRecord。
        return APIResult.paymentExtensionNotEnabled();
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
        if (channelId == null || channelId.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
        }
        if (currentBlockHeight < 0) {
            return APIResult.newFailResult(APIResult.FAIL, "currentBlockHeight must not be negative");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再结算争议、不构造 TRANSFER 交易，
        // 也不会修改通道状态或清理 DisputeRecord。
        return APIResult.paymentExtensionNotEnabled();
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
        if (channelId == null || channelId.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
        }
        if (currentBlockHeight < 0) {
            return APIResult.newFailResult(APIResult.FAIL, "currentBlockHeight must not be negative");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 TRANSFER 交易，也不会标记 EXPIRED。
        return APIResult.paymentExtensionNotEnabled();
    }

}
