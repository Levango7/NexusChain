package org.nexus.core.payment;

import org.apache.commons.codec.binary.Hex;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 支付通道管理器。
 *
 * <p>管理通道的完整生命周期：开启 -> 链下更新 -> 关闭/争议。
 * 内存中维护通道的当前状态和待确认的链下更新，支持多线程安全访问。</p>
 *
 * <p>链下更新不产生链上交易，只有争议或关闭时才需要上链。
 * 通道通过 {@code CHANNEL_OPEN} 交易开启后，双方可以通过交换签名的
 * {@link ChannelUpdate} 消息在链下无限次更新余额，最终通过
 * {@code CHANNEL_CLOSE} 交易结算上链。</p>
 *
 * <p>典型的链下支付流程：
 * <ol>
 *   <li>调用 {@link #openChannel} 开启通道（需上链）</li>
 *   <li>调用 {@link #initiatePayment} 发起链下支付（发送方签名）</li>
 *   <li>接收方调用 {@link #confirmPayment} 确认（接收方签名，链下完成）</li>
 *   <li>重复步骤 2-3 进行多次链下支付</li>
 *   <li>通过 {@link DisputeResolution} 发起链上争议关闭（需上链）</li>
 * </ol></p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class ChannelManager {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelManager.class);

    /** 随机数生成器，用于生成通道 ID。 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 通道状态存储（channelId -> PaymentChannel）。 */
    private final ConcurrentHashMap<String, PaymentChannel> channels;

    /** 待确认的链下更新（channelId -> 最新 ChannelUpdate）。 */
    private final ConcurrentHashMap<String, ChannelUpdate> pendingUpdates;

    /**
     * 默认构造函数，初始化内部存储。
     */
    public ChannelManager() {
        this.channels = new ConcurrentHashMap<>();
        this.pendingUpdates = new ConcurrentHashMap<>();
    }

    // ==================== Channel Lifecycle ====================

    /**
     * 开启支付通道。
     *
     * <p>创建一个新的双向支付通道，由 from 注资 amount，通道进入
     * {@link PaymentChannel.State#OPEN} 状态。通道 ID 通过 UUID +
     * 随机数生成，确保全局唯一性。</p>
     *
     * <p>注意：此方法仅创建通道内存状态，实际上链需要构造
     * {@code CHANNEL_OPEN} 交易并提交到交易池。</p>
     *
     * @param from     发起方地址（公钥哈希十六进制字符串）
     * @param to       对方地址（公钥哈希十六进制字符串）
     * @param amount   注资金额（NEX 最小单位）
     * @param lockTime 通道锁定时间（区块高度），到期后可强制关闭
     * @return 新创建的 PaymentChannel
     * @throws IllegalArgumentException 如果 amount <= 0 或地址为空
     */
    public PaymentChannel openChannel(String from, String to, long amount, int lockTime) {
        if (from == null || from.isEmpty() || to == null || to.isEmpty()) {
            throw new IllegalArgumentException("Channel participants cannot be null or empty");
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }

        String channelId = generateChannelId();
        PaymentChannel channel = new PaymentChannel(
                channelId, from, to, amount, 0L, 0L, lockTime,
                null, 0L, 0L, PaymentChannel.DEFAULT_DISPUTE_PERIOD
        );
        // 初始状态：from 注资 amount，balance2 为 0
        channel.setBalance1(amount);
        channel.setBalance2(0);
        channel.setNonce(0);
        channel.open(0L);

        channels.put(channelId, channel);
        LOG.info("Opened payment channel: channelId={}, from={}, to={}, amount={}",
                channelId, from, to, amount);
        return channel;
    }

    /**
     * 提交链下更新（双方已签名）。
     *
     * <p>验证并提交一个双方都已签名的 {@link ChannelUpdate} 到通道。
     * 验证步骤：
     * <ol>
     *   <li>验证通道存在且状态为 OPEN</li>
     *   <li>验证 nonce 递增（update.nonce > channel.nonce）</li>
     *   <li>验证余额守恒（balance1 + balance2 == channel.getTotalBalance()）</li>
     *   <li>验证双方 Ed25519 签名有效</li>
     *   <li>更新通道余额和 nonce</li>
     *   <li>存储为 pendingUpdate（最新链下状态）</li>
     * </ol></p>
     *
     * <p>链下更新不产生链上交易。</p>
     *
     * @param channelId  通道 ID
     * @param balance1   参与方一新余额
     * @param balance2   参与方二新余额
     * @param sig1       参与方一签名（64 字节）
     * @param sig2       参与方二签名（64 字节）
     * @param pubkey1    参与方一公钥（32 字节）
     * @param pubkey2    参与方二公钥（32 字节）
     * @return 已确认的 ChannelUpdate
     * @throws IllegalArgumentException 如果通道不存在、状态不合法、nonce 未递增、
     *                                  余额不守恒或签名无效
     */
    public ChannelUpdate submitUpdate(String channelId, long balance1, long balance2,
                                      byte[] sig1, byte[] sig2,
                                      byte[] pubkey1, byte[] pubkey2) {
        PaymentChannel channel = getChannel(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found: " + channelId);
        }
        if (channel.getState() != PaymentChannel.State.OPEN) {
            throw new IllegalArgumentException(
                    "Channel is not OPEN: " + channel.getState());
        }

        // 获取当前 nonce，新 update 的 nonce 应为当前 nonce + 1
        long newNonce = channel.getNonce() + 1;

        ChannelUpdate update = new ChannelUpdate(
                channelId, newNonce, balance1, balance2, sig1, sig2,
                System.currentTimeMillis()
        );

        // 验证 nonce 递增
        if (newNonce <= channel.getNonce()) {
            throw new IllegalArgumentException(
                    "Nonce must increase: newNonce=" + newNonce + ", current=" + channel.getNonce());
        }

        // 验证余额守恒
        if (!update.isBalanceConserved(channel.getTotalBalance())) {
            throw new IllegalArgumentException(
                    "Balance conservation violated: totalExpected=" + channel.getTotalBalance()
                            + ", actualTotal=" + (balance1 + balance2));
        }

        // 验证双方签名
        if (!update.verifySignatures(pubkey1, pubkey2)) {
            throw new IllegalArgumentException("Signature verification failed");
        }

        // 更新通道余额和 nonce（通过 PaymentChannel.update 方法）
        channel.update(balance1, balance2, newNonce);

        // 存储为最新链下状态
        pendingUpdates.put(channelId, update);

        LOG.info("Submitted channel update: channelId={}, nonce={}, balance1={}, balance2={}",
                channelId, newNonce, balance1, balance2);
        return update;
    }

    // ==================== Off-chain Payment Flow ====================

    /**
     * 发起链下支付（一方发起，需对方确认）。
     *
     * <p>参与方一向参与方二支付 amountFrom1To2 的金额：
     * <ol>
     *   <li>获取当前通道余额</li>
     *   <li>计算新余额（扣减发送方，增加接收方）</li>
     *   <li>验证余额非负</li>
     *   <li>构造 ChannelUpdate，nonce = channel.nonce + 1</li>
     *   <li>用发送方私钥签名（对 Keccak-256 哈希签名）</li>
     *   <li>返回待接收方签名的 update</li>
     * </ol></p>
     *
     * <p>此操作不产生链上交易。返回的 update 只有发送方签名，
     * 需要接收方调用 {@link #confirmPayment} 签名后才生效。</p>
     *
     * @param channelId        通道 ID
     * @param amountFrom1To2   参与方一向参与方二支付的金额
     * @param senderPrikey     发送方（参与方一）私钥（32 字节）
     * @param senderPubkey     发送方公钥（32 字节）
     * @param receiverPubkey   接收方（参与方二）公钥（32 字节）
     * @return 待接收方签名的 ChannelUpdate（只有 signature1）
     * @throws IllegalArgumentException 如果通道不存在、状态不合法、余额不足
     */
    public ChannelUpdate initiatePayment(String channelId, long amountFrom1To2,
                                         byte[] senderPrikey, byte[] senderPubkey,
                                         byte[] receiverPubkey) {
        PaymentChannel channel = getChannel(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found: " + channelId);
        }
        if (channel.getState() != PaymentChannel.State.OPEN) {
            throw new IllegalArgumentException(
                    "Channel is not OPEN: " + channel.getState());
        }
        if (amountFrom1To2 <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive: " + amountFrom1To2);
        }

        long currentBalance1 = channel.getBalance1();
        long currentBalance2 = channel.getBalance2();

        // 计算新余额
        long newBalance1 = currentBalance1 - amountFrom1To2;
        long newBalance2 = currentBalance2 + amountFrom1To2;

        if (newBalance1 < 0) {
            throw new IllegalArgumentException(
                    "Insufficient balance: currentBalance1=" + currentBalance1
                            + ", payment=" + amountFrom1To2);
        }

        long newNonce = channel.getNonce() + 1;
        ChannelUpdate update = new ChannelUpdate(
                channelId, newNonce, newBalance1, newBalance2,
                null, null, System.currentTimeMillis()
        );

        // 发送方签名
        byte[] messageHash = HashUtil.keccak256(update.getMessageToSign());
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(senderPrikey);
        byte[] signature = privateKey.sign(messageHash);
        update.setSignature1(signature);

        // 存储 pendingUpdate（尚未完全确认）
        pendingUpdates.put(channelId, update);

        LOG.info("Initiated payment: channelId={}, amount={}, newNonce={}, balance1={}, balance2={}",
                channelId, amountFrom1To2, newNonce, newBalance1, newBalance2);
        return update;
    }

    /**
     * 确认链下支付（接收方签名）。
     *
     * <p>接收方验证发送方的签名和余额守恒后，用自己的私钥签名，
     * 使 update 成为双方确认的链下状态，并提交到通道：
     * <ol>
     *   <li>验证 update 的发送方签名（signature1）</li>
     *   <li>验证余额守恒</li>
     *   <li>验证 nonce 递增</li>
     *   <li>接收方签名（signature2）</li>
     *   <li>通过 {@link #submitUpdate} 提交到通道</li>
     *   <li>返回已确认的 update</li>
     * </ol></p>
     *
     * @param update             待确认的 ChannelUpdate（已含 signature1）
     * @param receiverPrikey     接收方（参与方二）私钥（32 字节）
     * @param receiverPubkey     接收方公钥（32 字节）
     * @return 已确认的 ChannelUpdate（含双方签名）
     * @throws IllegalArgumentException 如果发送方签名无效、余额不守恒或 nonce 非法
     */
    public ChannelUpdate confirmPayment(ChannelUpdate update,
                                         byte[] receiverPrikey, byte[] receiverPubkey) {
        if (update == null) {
            throw new IllegalArgumentException("Update cannot be null");
        }
        if (!update.hasSignature1()) {
            throw new IllegalArgumentException("Update must have signature1 from sender");
        }

        PaymentChannel channel = getChannel(update.getChannelId());
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found: " + update.getChannelId());
        }
        if (channel.getState() != PaymentChannel.State.OPEN) {
            throw new IllegalArgumentException(
                    "Channel is not OPEN: " + channel.getState());
        }

        // 验证余额守恒
        if (!update.isBalanceConserved(channel.getTotalBalance())) {
            throw new IllegalArgumentException(
                    "Balance conservation violated: totalExpected=" + channel.getTotalBalance()
                            + ", actualTotal=" + (update.getBalance1() + update.getBalance2()));
        }

        // 验证 nonce 递增
        if (update.getNonce() <= channel.getNonce()) {
            throw new IllegalArgumentException(
                    "Nonce must increase: updateNonce=" + update.getNonce()
                            + ", current=" + channel.getNonce());
        }

        // 接收方签名
        byte[] messageHash = HashUtil.keccak256(update.getMessageToSign());
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(receiverPrikey);
        byte[] signature2 = privateKey.sign(messageHash);
        update.setSignature2(signature2);

        // 提交到通道（更新通道状态）
        channel.update(update.getBalance1(), update.getBalance2(), update.getNonce());

        // 存储为最新已确认的链下状态
        pendingUpdates.put(update.getChannelId(), update);

        LOG.info("Confirmed payment: channelId={}, nonce={}, balance1={}, balance2={}",
                update.getChannelId(), update.getNonce(),
                update.getBalance1(), update.getBalance2());
        return update;
    }

    // ==================== Query Methods ====================

    /**
     * 获取通道的最新链下状态。
     *
     * @param channelId 通道 ID
     * @return 最新的 ChannelUpdate，如果无更新则返回 null
     */
    public ChannelUpdate getLatestUpdate(String channelId) {
        return pendingUpdates.get(channelId);
    }

    /**
     * 获取通道信息。
     *
     * @param channelId 通道 ID
     * @return PaymentChannel，如果不存在则返回 null
     */
    public PaymentChannel getChannel(String channelId) {
        return channels.get(channelId);
    }

    /**
     * 获取所有通道。
     *
     * @return 通道映射（channelId -> PaymentChannel），不可修改
     */
    public java.util.Map<String, PaymentChannel> getAllChannels() {
        return java.util.Collections.unmodifiableMap(channels);
    }

    // ==================== Private Helpers ====================

    /**
     * 生成全局唯一的通道 ID。
     *
     * <p>使用 UUID + 随机字节数据生成，确保唯一性。</p>
     *
     * @return 通道 ID 字符串
     */
    private String generateChannelId() {
        byte[] randomBytes = new byte[16];
        RANDOM.nextBytes(randomBytes);
        return UUID.randomUUID().toString().replace("-", "")
                + Hex.encodeHexString(randomBytes).substring(0, 8);
    }
}
