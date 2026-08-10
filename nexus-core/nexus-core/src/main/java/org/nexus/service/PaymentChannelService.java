/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * * The java-nexuscore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-nexuscore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-nexuscore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.nexus.service;

import org.nexus.keystore.util.JsonUtils;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.core.TransactionPool;
import org.nexus.core.payment.PaymentChannel;
import org.nexus.db.StateDB;
import org.nexus.keystore.wallet.KeystoreAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 支付通道业务服务。
 *
 * <p>负责 NEX 网络中双向支付通道的开启、关闭与状态查询。
 * 通道的链下状态通过 {@code CHANNEL_OPEN} / {@code CHANNEL_CLOSE}
 * 交易类型上链确认，由本服务构造交易并提交到 {@link TransactionPool}。</p>
 *
 * <p>本服务为骨架实现：交易签名由钱包层在提交前完成
 * （此处使用零签名占位），通道状态的持久化查询为模拟数据，
 * 后续可接入 {@link StateDB} 进行真实查询。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class PaymentChannelService {

    /** 交易版本号，固定为 1。 */
    private static final int TX_VERSION = Transaction.DEFAULT_TRANSACTION_VERSION;

    /** 默认 gasPrice，按最小单位计费。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

    /** 默认争议期（区块数）。 */
    private static final int DEFAULT_DISPUTE_PERIOD = PaymentChannel.DEFAULT_DISPUTE_PERIOD;

    @Autowired
    private TransactionPool txPool;

    @Autowired
    private StateDB stateDB;

    /**
     * 开启支付通道。
     *
     * <p>创建 {@link PaymentChannel} 对象并调用 {@link PaymentChannel#open(long)}，
     * 随后构造 {@code CHANNEL_OPEN} 类型交易（payload 为
     * {@link PaymentChannel#toJson()} 的 UTF-8 字节）并提交到交易池。</p>
     *
     * @param from     发起方地址（NEX 地址格式）
     * @param to       对方地址（NEX 地址格式）
     * @param amount   注资金额（NEX 最小单位）
     * @param lockTime 通道锁定时间（区块高度），0 表示不设置
     * @return 统一响应结果，data 中包含新通道信息（channelId、state 等）
     */
    public APIResult openChannel(String from, String to, long amount, int lockTime) {
        try {
            if (from == null || from.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "from address is required");
            }
            if (to == null || to.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "to address is required");
            }
            if (amount <= 0) {
                return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
            }

            // 构造通道对象
            PaymentChannel channel = new PaymentChannel();
            String channelId = "ch_" + System.currentTimeMillis();
            channel.setChannelId(channelId);
            channel.setParticipant1(from);
            channel.setParticipant2(to);
            channel.setBalance1(amount);
            channel.setBalance2(0L);
            channel.setNonce(0L);
            channel.setLockTime(lockTime);
            channel.setDisputePeriod(DEFAULT_DISPUTE_PERIOD);

            // 开启通道（使用当前最佳区块高度，骨架实现以 0 占位）
            long blockHeight = safeBestHeight();
            channel.open(blockHeight);

            // 构造 CHANNEL_OPEN 交易
            byte[] payload = channel.toJson().getBytes(StandardCharsets.UTF_8);
            byte[] fromKey = KeystoreAction.addressToPubkeyHash(from);
            byte[] toKey = KeystoreAction.addressToPubkeyHash(to);
            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.CHANNEL_OPEN.ordinal(),
                    0L,
                    fromKey,
                    DEFAULT_GAS_PRICE,
                    amount,
                    payload,
                    toKey,
                    emptySig
            );

            txPool.add(tx);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("channelId", channelId);
            data.put("from", from);
            data.put("to", to);
            data.put("amount", amount);
            data.put("lockTime", lockTime);
            data.put("state", channel.getState().name());
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (IllegalStateException | IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to open channel: " + e.getMessage());
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to open channel: " + e.getMessage());
        }
    }

    /**
     * 关闭支付通道。
     *
     * <p>根据通道最终余额构造 {@code CHANNEL_CLOSE} 类型交易并提交到交易池。</p>
     *
     * @param channelId     通道 ID
     * @param finalBalance1 参与方一最终余额
     * @param finalBalance2 参与方二最终余额
     * @param nonce         最终状态 nonce
     * @return 统一响应结果，data 中包含关闭后的通道状态
     */
    public APIResult closeChannel(String channelId, long finalBalance1, long finalBalance2, long nonce) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            // 构造关闭状态 payload
            Map<String, Object> closePayload = new LinkedHashMap<>();
            closePayload.put("channelId", channelId);
            closePayload.put("finalBalance1", finalBalance1);
            closePayload.put("finalBalance2", finalBalance2);
            closePayload.put("nonce", nonce);
            closePayload.put("action", "CLOSE");

            byte[] payload = JsonUtils.toJson(closePayload).getBytes(StandardCharsets.UTF_8);

            // 关闭交易 from/to 为占位（零公钥哈希），实际由钱包层签名时填充
            byte[] placeholderFrom = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
            byte[] placeholderTo = new byte[Transaction.PUBLIC_KEY_HASH_SIZE];
            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.CHANNEL_CLOSE.ordinal(),
                    nonce,
                    placeholderFrom,
                    DEFAULT_GAS_PRICE,
                    finalBalance1 + finalBalance2,
                    payload,
                    placeholderTo,
                    emptySig
            );

            txPool.add(tx);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("channelId", channelId);
            data.put("finalBalance1", finalBalance1);
            data.put("finalBalance2", finalBalance2);
            data.put("nonce", nonce);
            data.put("state", PaymentChannel.State.CLOSING.name());
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to close channel: " + e.getMessage());
        }
    }

    /**
     * 查询通道状态。
     *
     * <p>骨架实现：返回基于 channelId 的模拟通道数据。
     * 后续可从 {@link StateDB} 查询通道的持久化状态。</p>
     *
     * @param channelId 通道 ID
     * @return 统一响应结果，data 中包含 {@link PaymentChannel} 对象
     */
    public APIResult getChannelState(String channelId) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            // 骨架：返回模拟通道状态
            PaymentChannel channel = new PaymentChannel();
            channel.setChannelId(channelId);
            channel.setState(PaymentChannel.State.OPEN);
            channel.setBalance1(0L);
            channel.setBalance2(0L);
            channel.setNonce(0L);
            channel.setOpenBlockHeight(safeBestHeight());

            return APIResult.newSuccess(channel);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query channel state: " + e.getMessage());
        }
    }

    /**
     * 查询地址关联的通道列表。
     *
     * <p>骨架实现：返回空列表。后续可从 {@link StateDB} 索引中
     * 查询地址参与的所有通道。</p>
     *
     * @param address NEX 地址
     * @return 统一响应结果，data 中包含通道列表
     */
    public APIResult listChannelsByAddress(String address) {
        try {
            if (address == null || address.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "address is required");
            }

            // 骨架：返回空列表
            List<PaymentChannel> channels = new ArrayList<>();
            return APIResult.newSuccess(channels);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to list channels: " + e.getMessage());
        }
    }

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
        } catch (Exception e) {
            return 0L;
        }
    }
}
