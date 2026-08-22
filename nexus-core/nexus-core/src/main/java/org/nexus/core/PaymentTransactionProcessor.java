package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * NexusChain 支付扩展交易处理器。
 *
 * <p>在区块确认后，根据交易类型分发到对应的处理逻辑，维护支付扩展模块的
 * 链上状态记录。该处理器覆盖以下交易类型：</p>
 *
 * <ul>
 *   <li>{@code CHANNEL_OPEN} - 创建支付通道状态记录（{@link PaymentChannel}），开启通道</li>
 *   <li>{@code CHANNEL_CLOSE} - 结算并关闭支付通道</li>
 *   <li>{@code BATCH_TRANSFER} - 解析 payload 并执行批量转账记录</li>
 *   <li>{@code MINT_STABLECOIN} - 创建/更新稳定币仓位（{@link StableCoinPosition}）</li>
 *   <li>{@code BRIDGE_LOCK} - 创建跨链桥交易记录（{@link BridgeTransaction}）</li>
 *   <li>其他类型 - 仅记录日志</li>
 * </ul>
 *
 * <p>状态记录以通道 ID / 仓位 ID / 桥交易 ID 为键存储于内存 Map 中，
 * 供后续查询与状态机驱动使用。实际生产环境可替换为持久化存储。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class PaymentTransactionProcessor {

    private static final Logger logger = LoggerFactory.getLogger(PaymentTransactionProcessor.class);

    private final PaymentStateStore stateStore;

    public PaymentTransactionProcessor(PaymentStateStore stateStore) {
        this.stateStore = stateStore;
    }

    /**
     * 处理单笔支付扩展交易。
     *
     * <p>根据交易类型分发到对应的处理逻辑。所有异常被捕获并记录日志，
     * 不会中断后续交易的处理流程。</p>
     *
     * @param tx          待处理的交易
     * @param blockHeight 交易所在区块高度
     */
    public void processTransaction(Transaction tx, long blockHeight) {
        if (tx == null) {
            return;
        }
        try {
            if (tx.type == Transaction.Type.CHANNEL_OPEN.ordinal()) {
                processChannelOpen(tx, blockHeight);
            } else if (tx.type == Transaction.Type.CHANNEL_UPDATE.ordinal()) {
                processChannelUpdate(tx, blockHeight);
            } else if (tx.type == Transaction.Type.CHANNEL_CLOSE.ordinal()) {
                processChannelClose(tx, blockHeight);
            } else if (tx.type == Transaction.Type.BATCH_TRANSFER.ordinal()) {
                processBatchTransfer(tx, blockHeight);
            } else if (tx.type == Transaction.Type.MINT_STABLECOIN.ordinal()) {
                processMintStableCoin(tx, blockHeight);
            } else if (tx.type == Transaction.Type.REDEEM_STABLECOIN.ordinal()) {
                processRedeemStableCoin(tx, blockHeight);
            } else if (tx.type == Transaction.Type.BRIDGE_LOCK.ordinal()) {
                processBridgeLock(tx, blockHeight);
            } else if (tx.type == Transaction.Type.BRIDGE_MINT.ordinal()) {
                processBridgeMint(tx, blockHeight);
            } else if (tx.type == Transaction.Type.BRIDGE_BURN.ordinal()) {
                processBridgeBurn(tx, blockHeight);
            } else if (tx.type == Transaction.Type.IDENTITY_REGISTER.ordinal()) {
                logger.info("IDENTITY_REGISTER processed at height={}, tx={}", blockHeight, tx.getHashHexString());
            } else if (tx.type == Transaction.Type.SUBSCRIPTION_AUTH.ordinal()) {
                logger.info("SUBSCRIPTION_AUTH processed at height={}, tx={}", blockHeight, tx.getHashHexString());
            } else {
                logger.debug("Transaction type {} processed (no payment extension handler) at height={}, tx={}",
                        tx.getTypeName(), blockHeight, tx.getHashHexString());
            }
        } catch (RuntimeException e) {
            logger.error("Failed to process transaction type={} tx={} at height={}: {}",
                    tx.getTypeName(), tx.getHashHexString(), blockHeight, e.getMessage(), e);
        }
    }

    // ==================== 支付通道 ====================

    /**
     * 处理 CHANNEL_OPEN 交易：创建支付通道状态记录并开启通道。
     *
     * <p>通道 ID 由交易哈希派生。参与方地址通过 from/to 公钥哈希生成。
     * 通道初始余额为交易金额全部分配给发起方。</p>
     *
     * @param tx          CHANNEL_OPEN 交易
     * @param blockHeight 当前区块高度
     */
    private void processChannelOpen(Transaction tx, long blockHeight) {
        String channelId = tx.getHashHexString();
        String participant1 = pubKeyHashToHex(tx.from);
        String participant2 = pubKeyHashToHex(tx.to);

        PaymentChannel channel = new PaymentChannel();
        channel.setChannelId(channelId);
        channel.setParticipant1(participant1);
        channel.setParticipant2(participant2);
        channel.setBalance1(tx.amount);
        channel.setBalance2(0L);
        channel.setNonce(0L);
        channel.open(blockHeight);

        stateStore.putChannel(channelId, channel);
        logger.info("CHANNEL_OPEN: created channel={} amount={} at height={}",
                channelId, tx.amount, blockHeight);
    }

    /**
     * 处理 CHANNEL_UPDATE 交易：更新通道余额与 nonce。
     *
     * <p>payload 中包含双方最新余额与新 nonce。payload 约定为 UTF-8 JSON：
     * {@code {"balance1":<long>,"balance2":<long>,"nonce":<long>}}。</p>
     *
     * @param tx          CHANNEL_UPDATE 交易
     * @param blockHeight 当前区块高度
     */
    private void processChannelUpdate(Transaction tx, long blockHeight) {
        String channelId = tx.getHashHexString();
        PaymentChannel channel = stateStore.getChannel(channelId);
        if (channel == null) {
            logger.warn("CHANNEL_UPDATE: channel not found for tx={}, skipping", channelId);
            return;
        }
        // payload 格式: balance1(8) + balance2(8) + nonce(8)，big-endian
        if (tx.payload == null || tx.payload.length < 24) {
            logger.warn("CHANNEL_UPDATE: invalid payload for channel={}", channelId);
            return;
        }
        long balance1 = readLongBE(tx.payload, 0);
        long balance2 = readLongBE(tx.payload, 8);
        long newNonce = readLongBE(tx.payload, 16);
        try {
            channel.update(balance1, balance2, newNonce);
            logger.info("CHANNEL_UPDATE: updated channel={} balance1={} balance2={} nonce={} at height={}",
                    channelId, balance1, balance2, newNonce, blockHeight);
        } catch (RuntimeException e) {
            logger.warn("CHANNEL_UPDATE: failed to update channel={}: {}", channelId, e.getMessage());
        }
    }

    /**
     * 处理 CHANNEL_CLOSE 交易：结算并关闭支付通道。
     *
     * <p>若通道存在，先进入关闭请求状态（争议期），再尝试关闭。
     * 关闭后通道状态记录保留以便查询历史。</p>
     *
     * @param tx          CHANNEL_CLOSE 交易
     * @param blockHeight 当前区块高度
     */
    private void processChannelClose(Transaction tx, long blockHeight) {
        String channelId = tx.getHashHexString();
        PaymentChannel channel = stateStore.getChannel(channelId);
        if (channel == null) {
            logger.warn("CHANNEL_CLOSE: channel not found for tx={}, skipping", channelId);
            return;
        }
        try {
            if (channel.getState() == PaymentChannel.State.OPEN) {
                channel.requestClose(blockHeight);
            }
            channel.close(blockHeight);
            logger.info("CHANNEL_CLOSE: settled channel={} at height={}", channelId, blockHeight);
        } catch (RuntimeException e) {
            logger.warn("CHANNEL_CLOSE: failed to close channel={}: {}", channelId, e.getMessage());
        }
    }

    // ==================== 批量转账 ====================

    /**
     * 处理 BATCH_TRANSFER 交易：解析 payload 中的转账项并执行记录。
     *
     * <p>使用 {@link BatchTransferPayload#parse(byte[])} 解析转账项列表，
     * 逐笔记录转账结果。</p>
     *
     * @param tx          BATCH_TRANSFER 交易
     * @param blockHeight 当前区块高度
     */
    private void processBatchTransfer(Transaction tx, long blockHeight) {
        List<BatchTransferPayload.TransferItem> items = BatchTransferPayload.parse(tx.payload);
        if (items.isEmpty()) {
            logger.warn("BATCH_TRANSFER: empty payload for tx={}", tx.getHashHexString());
            return;
        }
        List<String> results = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            BatchTransferPayload.TransferItem item = items.get(i);
            // 记录每笔转账（实际转账在账户层完成）
            results.add(item.getAddress() + ":" + item.getAmount());
        }
        logger.info("BATCH_TRANSFER: processed {} transfers at height={}, tx={}",
                items.size(), blockHeight, tx.getHashHexString());
        if (logger.isDebugEnabled()) {
            logger.debug("BATCH_TRANSFER items: {}", results);
        }
    }

    // ==================== 稳定币 ====================

    /**
     * 处理 MINT_STABLECOIN 交易：创建/更新稳定币仓位。
     *
     * <p>payload 前 8 字节为抵押物数量。若仓位已存在则追加抵押并铸造，
     * 否则创建新仓位。仓位 ID 由发起方地址派生。</p>
     *
     * @param tx          MINT_STABLECOIN 交易
     * @param blockHeight 当前区块高度
     */
    private void processMintStableCoin(Transaction tx, long blockHeight) {
        String owner = pubKeyHashToHex(tx.from);
        String positionId = "pos-" + owner;
        long collateral = 0;
        if (tx.payload != null && tx.payload.length >= 8) {
            collateral = readLongBE(tx.payload, 0);
        }
        StableCoinPosition position = stateStore.getPosition(positionId);
        if (position == null) {
            position = new StableCoinPosition(positionId, owner, blockHeight);
            stateStore.putPosition(positionId, position);
        }
        try {
            // 最低抵押率 150%
            position.mint(collateral, tx.amount, 150);
            position.setLastUpdateBlock(blockHeight);
            logger.info("MINT_STABLECOIN: minted {} with collateral {} at height={}, position={}",
                    tx.amount, collateral, blockHeight, positionId);
        } catch (RuntimeException e) {
            logger.warn("MINT_STABLECOIN: failed to mint position={}: {}", positionId, e.getMessage());
        }
    }

    /**
     * 处理 REDEEM_STABLECOIN 交易：赎回稳定币。
     *
     * @param tx          REDEEM_STABLECOIN 交易
     * @param blockHeight 当前区块高度
     */
    private void processRedeemStableCoin(Transaction tx, long blockHeight) {
        String owner = pubKeyHashToHex(tx.from);
        String positionId = "pos-" + owner;
        StableCoinPosition position = stateStore.getPosition(positionId);
        if (position == null) {
            logger.warn("REDEEM_STABLECOIN: position not found for owner={}, skipping", owner);
            return;
        }
        try {
            position.redeem(tx.amount);
            position.setLastUpdateBlock(blockHeight);
            logger.info("REDEEM_STABLECOIN: redeemed {} at height={}, position={}",
                    tx.amount, blockHeight, positionId);
        } catch (RuntimeException e) {
            logger.warn("REDEEM_STABLECOIN: failed to redeem position={}: {}", positionId, e.getMessage());
        }
    }

    // ==================== 跨链桥 ====================

    /**
     * 处理 BRIDGE_LOCK 交易：创建跨链桥交易记录并锁定源链资产。
     *
     * <p>桥交易 ID 由交易哈希派生。payload 包含目标链标识和收款人信息。
     * 创建后调用 {@link BridgeTransaction#lock()} 锁定资产。</p>
     *
     * @param tx          BRIDGE_LOCK 交易
     * @param blockHeight 当前区块高度
     */
    private void processBridgeLock(Transaction tx, long blockHeight) {
        String bridgeTxId = tx.getHashHexString();
        String recipient = pubKeyHashToHex(tx.to);
        // payload 中包含目标链标识和收款人信息，此处简化解析
        String targetChain = "unknown";
        if (tx.payload != null && tx.payload.length > 0) {
            // 尝试从 payload 提取目标链标识（前 4 字节作为链 ID）
            targetChain = "chain-" + readIntBE(tx.payload, 0);
        }
        BridgeTransaction bridgeTx = new BridgeTransaction(
                bridgeTxId, "nexus", targetChain, tx.amount, recipient, 3, 0L);
        try {
            bridgeTx.lock();
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
            logger.info("BRIDGE_LOCK: locked {} to {} recipient={} at height={}, bridgeTx={}",
                    tx.amount, targetChain, recipient, blockHeight, bridgeTxId);
        } catch (RuntimeException e) {
            logger.warn("BRIDGE_LOCK: failed to lock bridgeTx={}: {}", bridgeTxId, e.getMessage());
        }
    }

    /**
     * 处理 BRIDGE_MINT 交易：在目标链铸造对应资产。
     *
     * @param tx          BRIDGE_MINT 交易
     * @param blockHeight 当前区块高度
     */
    private void processBridgeMint(Transaction tx, long blockHeight) {
        String bridgeTxId = tx.getHashHexString();
        // BRIDGE_MINT 通常引用原始 LOCK 交易，此处以自身哈希记录
        BridgeTransaction bridgeTx = stateStore.getBridgeTx(bridgeTxId);
        if (bridgeTx == null) {
            bridgeTx = new BridgeTransaction(
                    bridgeTxId, "nexus", "nexus", tx.amount,
                    pubKeyHashToHex(tx.to), 3, 0L);
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
        }
        logger.info("BRIDGE_MINT: minted {} at height={}, bridgeTx={}",
                tx.amount, blockHeight, bridgeTxId);
    }

    /**
     * 处理 BRIDGE_BURN 交易：在目标链销毁对应资产。
     *
     * @param tx          BRIDGE_BURN 交易
     * @param blockHeight 当前区块高度
     */
    private void processBridgeBurn(Transaction tx, long blockHeight) {
        String bridgeTxId = tx.getHashHexString();
        BridgeTransaction bridgeTx = stateStore.getBridgeTx(bridgeTxId);
        if (bridgeTx == null) {
            bridgeTx = new BridgeTransaction(
                    bridgeTxId, "nexus", "nexus", tx.amount,
                    pubKeyHashToHex(tx.from), 3, 0L);
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
        }
        try {
            if (bridgeTx.getState() == BridgeTransaction.State.MINTED) {
                bridgeTx.burn();
            }
            logger.info("BRIDGE_BURN: burned {} at height={}, bridgeTx={}",
                    tx.amount, blockHeight, bridgeTxId);
        } catch (RuntimeException e) {
            logger.warn("BRIDGE_BURN: failed to burn bridgeTx={}: {}", bridgeTxId, e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 将公钥哈希字节数组转为十六进制字符串。
     *
     * @param bytes 公钥哈希字节数组
     * @return 十六进制字符串，如果输入为 null 则返回空字符串
     */
    private static String pubKeyHashToHex(byte[] bytes) {
        if (bytes == null) {
            return "";
        }
        return Hex.encodeHexString(bytes);
    }

    /**
     * 从字节数组指定偏移处读取 8 字节 big-endian long 值。
     *
     * @param data   字节数组
     * @param offset 起始偏移
     * @return 解析的 long 值
     */
    private static long readLongBE(byte[] data, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * 从字节数组指定偏移处读取 4 字节 big-endian int 值。
     *
     * @param data   字节数组
     * @param offset 起始偏移
     * @return 解析的 int 值
     */
    private static int readIntBE(byte[] data, int offset) {
        int value = 0;
        for (int i = 0; i < 4; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    // ==================== 查询方法 ====================

    /**
     * 获取支付通道状态记录。
     *
     * @param channelId 通道 ID
     * @return 通道状态记录，不存在则返回 null
     */
    public PaymentChannel getChannel(String channelId) {
        return stateStore.getChannel(channelId);
    }

    /**
     * 获取稳定币仓位记录。
     *
     * @param positionId 仓位 ID
     * @return 仓位记录，不存在则返回 null
     */
    public StableCoinPosition getPosition(String positionId) {
        return stateStore.getPosition(positionId);
    }

    /**
     * 获取跨链桥交易记录。
     *
     * @param bridgeTxId 桥交易 ID
     * @return 桥交易记录，不存在则返回 null
     */
    public BridgeTransaction getBridgeTransaction(String bridgeTxId) {
        return stateStore.getBridgeTx(bridgeTxId);
    }
}
