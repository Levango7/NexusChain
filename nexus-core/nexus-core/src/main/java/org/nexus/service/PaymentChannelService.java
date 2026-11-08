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

import org.nexus.ApiResult.APIResult;
import org.nexus.core.payment.PaymentChannel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 支付通道业务服务。
 *
 * <p>负责 NEX 网络中双向支付通道的开启、关闭与状态查询。
 * 支付扩展类型（0x10–0x1a）当前保留未启用：开启/关闭路由保留、
 * 入参校验保留，但不再构造 {@code CHANNEL_OPEN} / {@code CHANNEL_CLOSE}
 * 交易，统一返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class PaymentChannelService {

    /**
     * 开启支付通道。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code CHANNEL_OPEN} 交易。</p>
     *
     * @param from     发起方地址（NEX 地址格式）
     * @param to       对方地址（NEX 地址格式）
     * @param amount   注资金额（NEX 最小单位）
     * @param lockTime 通道锁定时间（区块高度），0 表示不设置
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult openChannel(String from, String to, long amount, int lockTime) {
        if (from == null || from.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "from address is required");
        }
        if (to == null || to.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "to address is required");
        }
        if (amount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 CHANNEL_OPEN 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 关闭支付通道。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code CHANNEL_CLOSE} 交易，也不会进入 CLOSING 半关闭状态。</p>
     *
     * @param channelId     通道 ID
     * @param finalBalance1 参与方一最终余额
     * @param finalBalance2 参与方二最终余额
     * @param nonce         最终状态 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult closeChannel(String channelId, long finalBalance1, long finalBalance2, long nonce) {
        if (channelId == null || channelId.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
        }
        if (finalBalance1 < 0 || finalBalance2 < 0) {
            return APIResult.newFailResult(APIResult.FAIL, "final balances must not be negative");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 CHANNEL_CLOSE 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 查询通道状态。
     *
     * <p>支付扩展类型当前保留未启用，本接口固定返回
     * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}，不再返回模拟状态。</p>
     *
     * @param channelId 通道 ID
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult getChannelState(String channelId) {
        try {
            if (channelId == null || channelId.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "channelId is required");
            }

            // 支付扩展（0x10–0x1a）保留未启用：不再返回模拟 OPEN 状态。
            return APIResult.paymentExtensionNotEnabled();
        } catch (RuntimeException e) {
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
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to list channels: " + e.getMessage());
        }
    }
}
