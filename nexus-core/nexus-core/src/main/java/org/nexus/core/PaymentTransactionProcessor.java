package org.nexus.core;

import org.apache.commons.codec.binary.Hex;
import org.nexus.core.account.Transaction;
import org.nexus.core.payment.BatchTransferPayload;
import org.nexus.core.payment.BridgeLifecycleReplayGuard;
import org.nexus.core.payment.BridgeTransaction;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.core.payment.StableCoinPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** BRIDGE_MINT 重放防护（v2.1.0 安全修复）：以规范化 messageHash 为幂等键。 */
    private final org.nexus.core.payment.BridgeMintReplayGuard replayGuard;

    /** BRIDGE_LOCK / BRIDGE_BURN 生命周期重放防护（v2.2.0 安全修复）：规范化语义幂等键。 */
    private final BridgeLifecycleReplayGuard lifecycleReplayGuard;

    public PaymentTransactionProcessor(PaymentStateStore stateStore) {
        this(stateStore, new org.nexus.core.payment.BridgeMintReplayGuard(),
                new BridgeLifecycleReplayGuard());
    }

    /**
     * 兼容构造器（保留旧签名供无 Spring 上下文的手工装配使用）。
     */
    public PaymentTransactionProcessor(PaymentStateStore stateStore,
                                       org.nexus.core.payment.BridgeMintReplayGuard replayGuard) {
        this(stateStore, replayGuard, new BridgeLifecycleReplayGuard());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PaymentTransactionProcessor(PaymentStateStore stateStore,
                                       org.nexus.core.payment.BridgeMintReplayGuard replayGuard,
                                       BridgeLifecycleReplayGuard lifecycleReplayGuard) {
        this.stateStore = stateStore;
        this.replayGuard = replayGuard;
        this.lifecycleReplayGuard = lifecycleReplayGuard;
    }

    /**
     * 从持久化状态恢复已消费的桥重放键（v2.2.0 安全修复）。
     *
     * <p>节点重启后，内存守卫为空。若不清空重放计数并恢复已消费键，
     * 同一桥交易（LOCK/MINT/BURN）可能在重启后被重新入账，造成双花/重复结算。
     * 此处从 {@link PaymentStateStore}（JDBC 生产实现下为 {@code bridge_replay_keys} 表）
     * 载入各方向已消费幂等键并重新标记到守卫内存态。</p>
     *
     * <p>注意：非 Spring 环境下（如纯单元测试手工 {@code new}）不会触发本方法，
     * 守卫从空集启动，行为与重放防护的单测预期一致。</p>
     */
    @PostConstruct
    public void restoreConsumedReplayKeys() {
        int restoredLock = 0;
        int restoredMint = 0;
        int restoredBurn = 0;
        for (String key : stateStore.getAllConsumedReplayKeys(BridgeLifecycleReplayGuard.KIND_LOCK)) {
            lifecycleReplayGuard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, key);
            restoredLock++;
        }
        for (String key : stateStore.getAllConsumedReplayKeys(BridgeLifecycleReplayGuard.KIND_MINT)) {
            replayGuard.markConsumed(key);
            restoredMint++;
        }
        for (String key : stateStore.getAllConsumedReplayKeys(BridgeLifecycleReplayGuard.KIND_BURN)) {
            lifecycleReplayGuard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, key);
            restoredBurn++;
        }
        if (restoredLock + restoredMint + restoredBurn > 0) {
            logger.info("BRIDGE replay guard: restored {} LOCK / {} MINT / {} BURN consumed keys from persistent store",
                    restoredLock, restoredMint, restoredBurn);
        }
    }

    /**
     * 桥重放防护统计快照（监控指标暴露）。
     *
     * <p>汇总 BRIDGE_MINT 与 BRIDGE_LOCK/BRIDGE_BURN 两个防护器的存量与拒绝计数，
     * 供运维轮询/埋点上报。</p>
     *
     * @return 不可修改的统计 Map
     */
    public Map<String, Object> bridgeReplayStats() {
        Map<String, Object> stats = new LinkedHashMap<>(lifecycleReplayGuard.stats());
        stats.put("consumedMint", replayGuard.size());
        return java.util.Collections.unmodifiableMap(stats);
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
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志并将异常向上抛出，
            // 由 processTransaction 主 catch 统一标记交易处理失败。
            logger.error("CHANNEL_UPDATE: failed to update channel={} at height={}: {}",
                    channelId, blockHeight, e.getMessage(), e);
            throw e;
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
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志并将异常向上抛出，
            // 由 processTransaction 主 catch 统一标记交易处理失败。
            logger.error("CHANNEL_CLOSE: failed to close channel={} at height={}: {}",
                    channelId, blockHeight, e.getMessage(), e);
            throw e;
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
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志并将异常向上抛出，
            // 由 processTransaction 主 catch 统一标记交易处理失败。
            logger.error("MINT_STABLECOIN: failed to mint position={} at height={}: {}",
                    positionId, blockHeight, e.getMessage(), e);
            throw e;
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
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志并将异常向上抛出，
            // 由 processTransaction 主 catch 统一标记交易处理失败。
            logger.error("REDEEM_STABLECOIN: failed to redeem position={} at height={}: {}",
                    positionId, blockHeight, e.getMessage(), e);
            throw e;
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
        // v2.2.0：与 BridgeRule.validateBridgeLock 对齐——payload 为 BridgeTransaction JSON，
        // 从中解析 targetChain / recipient 作为锁定意图的语义字段
        // （此前以 readIntBE 误读 JSON 前 4 字节，得到无意义的目标链标识）。
        String recipient = pubKeyHashToHex(tx.to);
        String targetChain = "unknown";
        boolean payloadParsed = false;
        if (tx.payload != null && tx.payload.length > 0) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new com.fasterxml.jackson.databind.ObjectMapper()
                                .readTree(new String(tx.payload,
                                        java.nio.charset.StandardCharsets.UTF_8));
                String tc = node.path("targetChain").asText(null);
                String rc = node.path("recipient").asText(null);
                if (tc != null && !tc.isEmpty() && rc != null && !rc.isEmpty()) {
                    targetChain = tc;
                    recipient = rc;
                    payloadParsed = true;
                }
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                logger.warn("BRIDGE_LOCK: payload is not valid JSON bridge transaction at height={}, tx={}",
                        blockHeight, tx.getHashHexString());
            }
        }
        if (!payloadParsed) {
            // 向后兼容旧观察：非 JSON payload 按原始指纹提取目标链
            if (tx.payload != null && tx.payload.length >= 4) {
                targetChain = "chain-" + readIntBE(tx.payload, 0);
            }
        }

        // v2.2.0 重放防护：以锁定意图的规范语义生成幂等键
        // （from + targetChain + recipient + amount），使同一锁定请求
        // 无论 nonce/txHash 如何变化都收敛到同一键。
        String bridgeTxId = BridgeLifecycleReplayGuard.computeLockKey(
                Hex.encodeHexString(tx.from), targetChain, recipient, tx.amount);

        if (lifecycleReplayGuard.isConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, bridgeTxId)) {
            lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_LOCK);
            logger.error("BRIDGE_LOCK: replay detected at height={}, lockKey={} (already locked), "
                    + "skipping duplicate tx={}", blockHeight, bridgeTxId, tx.getHashHexString());
            return;
        }

        BridgeTransaction bridgeTx = stateStore.getBridgeTx(bridgeTxId);
        if (bridgeTx != null) {
            BridgeTransaction.State s = bridgeTx.getState();
            if (bridgeLockAlreadyRecorded(s)) {
                lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_LOCK);
                logger.error("BRIDGE_LOCK: replay detected at height={}, bridgeTx={} (state={}), "
                        + "skipping duplicate tx={}", blockHeight, bridgeTxId, s,
                        tx.getHashHexString());
                return;
            }
            if (s == BridgeTransaction.State.FAILED || s == BridgeTransaction.State.EXPIRED) {
                // 重置失败/过期记录，允许重试本次锁定
                bridgeTx = new BridgeTransaction(
                        bridgeTxId, "nexus", targetChain, tx.amount, recipient, 3, 0L);
            }
        } else {
            bridgeTx = new BridgeTransaction(
                    bridgeTxId, "nexus", targetChain, tx.amount, recipient, 3, 0L);
        }
        try {
            bridgeTx.lock();
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
            if (lifecycleReplayGuard.markConsumed(BridgeLifecycleReplayGuard.KIND_LOCK, bridgeTxId)) {
                stateStore.putConsumedReplayKey(BridgeLifecycleReplayGuard.KIND_LOCK, bridgeTxId);
            }
            logger.info("BRIDGE_LOCK: locked {} to {} recipient={} at height={}, bridgeTx={}",
                    tx.amount, targetChain, recipient, blockHeight, bridgeTxId);
        } catch (RuntimeException e) {
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志，
            // 将桥交易标记为 FAILED 并持久化，避免区块状态与桥交易状态不一致。
            logger.error("BRIDGE_LOCK: failed to lock bridgeTx={} at height={}: {}",
                    bridgeTxId, blockHeight, e.getMessage(), e);
            bridgeTx.setState(BridgeTransaction.State.FAILED);
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
        }
    }

    /**
     * 判断桥交易是否已处于不可再次锁定的终态（锁定流程已成功推进）。
     */
    private static boolean bridgeLockAlreadyRecorded(BridgeTransaction.State s) {
        return s == BridgeTransaction.State.LOCKED
                || s == BridgeTransaction.State.VALIDATING
                || s == BridgeTransaction.State.MINTED
                || s == BridgeTransaction.State.BURNED
                || s == BridgeTransaction.State.UNLOCKED;
    }

    /**
     * 处理 BRIDGE_MINT 交易：在目标链铸造对应资产。
     *
     * <p>v2.1.0 安全修复：以 payload 中的规范化 messageHash（字节 9-40）为幂等键
     * 做应用层去重——同一 messageHash 只允许成功记录一次铸造。重复交易
     * （同多签、不同 nonce 导致 tx 哈希不同的重放）在此被跳过并标记 FAILED，
     * 与验证层 {@code BridgeRule} 的重放检查形成纵深防御。</p>
     *
     * @param tx          BRIDGE_MINT 交易
     * @param blockHeight 当前区块高度
     */
    private void processBridgeMint(Transaction tx, long blockHeight) {
        // 提取规范化 messageHash：payload = [8B timelock][1B sigCount][32B messageHash][...]
        if (tx.payload == null || tx.payload.length < 9 + 32) {
            logger.error("BRIDGE_MINT: payload too short to extract messageHash at height={}, tx={}",
                    blockHeight, tx.getHashHexString());
            return;
        }
        String messageHashHex = Hex.encodeHexString(
                java.util.Arrays.copyOfRange(tx.payload, 9, 41)).toLowerCase();

        // 应用层重放防护：已消费的 messageHash 不再重复入账
        if (!replayGuard.markConsumed(messageHashHex)) {
            logger.error("BRIDGE_MINT: replay detected at height={}, messageHash={} (already minted), "
                    + "skipping duplicate tx={}", blockHeight, messageHashHex, tx.getHashHexString());
            return;
        }

        // 以 messageHash 为桥交易 ID（即源链锁定交易的规范引用）
        String bridgeTxId = messageHashHex;
        BridgeTransaction bridgeTx = stateStore.getBridgeTx(bridgeTxId);
        if (bridgeTx == null) {
            bridgeTx = new BridgeTransaction(
                    bridgeTxId, "nexus", "nexus", tx.amount,
                    pubKeyHashToHex(tx.to), 3, 0L);
        }
        // 多签真实性/归属/重放校验已在验证层 BridgeRule 完成，此处仅记录最终状态
        bridgeTx.setState(BridgeTransaction.State.MINTED);
        stateStore.putBridgeTx(bridgeTxId, bridgeTx);
        logger.info("BRIDGE_MINT: minted {} at height={}, messageHash={}",
                tx.amount, blockHeight, bridgeTxId);
    }

    /**
     * 处理 BRIDGE_BURN 交易：在目标链销毁对应资产。
     *
     * @param tx          BRIDGE_BURN 交易
     * @param blockHeight 当前区块高度
     */
    private void processBridgeBurn(Transaction tx, long blockHeight) {
        // v2.2.0 重放防护 + 状态机一致性：
        // 1) 幂等键为销毁意图的规范语义（from + to + amount），与 nonce/txHash 无关；
        // 2) 仅当存在 MINTED 记录时允许销毁，无关联记录/非法状态下 fail-closed 拒绝，
        //    不再凭空创建"已销毁"记录或静默跳过。
        String bridgeTxId = BridgeLifecycleReplayGuard.computeBurnKey(
                Hex.encodeHexString(tx.from), Hex.encodeHexString(tx.to), tx.amount);

        if (lifecycleReplayGuard.isConsumed(BridgeLifecycleReplayGuard.KIND_BURN, bridgeTxId)) {
            lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_BURN);
            logger.warn("BRIDGE_BURN: replay detected at height={}, burnKey={} (already burned), "
                    + "skipping duplicate tx={}", blockHeight, bridgeTxId, tx.getHashHexString());
            return;
        }

        BridgeTransaction bridgeTx = stateStore.getBridgeTx(bridgeTxId);
        if (bridgeTx == null) {
            // fail-closed：无关联铸造记录时不予入账，杜绝凭空"已销毁"记录。
            lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_BURN);
            logger.error("BRIDGE_BURN: no minted bridge record for burnKey={} at height={}, "
                    + "rejecting (fail-closed), tx={}", bridgeTxId, blockHeight, tx.getHashHexString());
            return;
        }
        BridgeTransaction.State state = bridgeTx.getState();
        if (state == BridgeTransaction.State.BURNED || state == BridgeTransaction.State.UNLOCKED) {
            lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_BURN);
            logger.warn("BRIDGE_BURN: idempotent skip at height={}, bridgeTx={} already in state {}, "
                    + "tx={}", blockHeight, bridgeTxId, state, tx.getHashHexString());
            return;
        }
        try {
            if (state != BridgeTransaction.State.MINTED) {
                // 状态机一致性：仅 MINTED 可销毁。
                lifecycleReplayGuard.recordRejected(BridgeLifecycleReplayGuard.KIND_BURN);
                logger.error("BRIDGE_BURN: bridgeTx={} in state {} (expected MINTED) at height={}, "
                        + "rejecting burn, tx={}", bridgeTxId, state, blockHeight, tx.getHashHexString());
                return;
            }
            bridgeTx.burn();
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
            lifecycleReplayGuard.markConsumed(BridgeLifecycleReplayGuard.KIND_BURN, bridgeTxId);
            logger.info("BRIDGE_BURN: burned {} at height={}, bridgeTx={}",
                    tx.amount, blockHeight, bridgeTxId);
        } catch (RuntimeException e) {
            // B-02 修复：不再吞掉异常静默继续；记录 error 日志，
            // 将桥交易标记为 FAILED 并持久化，避免区块状态与桥交易状态不一致。
            logger.error("BRIDGE_BURN: failed to burn bridgeTx={} at height={}: {}",
                    bridgeTxId, blockHeight, e.getMessage(), e);
            bridgeTx.setState(BridgeTransaction.State.FAILED);
            stateStore.putBridgeTx(bridgeTxId, bridgeTx);
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
