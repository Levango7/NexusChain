/*
 * Copyright (c) [2018]
 * This file is part of the java-nexuscore
 *
 * The java-nexuscore is free software: you can redistribute it and/or modify
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 稳定币业务服务。
 *
 * <p>负责 NEX 稳定币系统的铸造（{@code MINT_STABLECOIN}）、赎回
 * （{@code REDEEM_STABLECOIN}）、抵押率查询和价格查询。
 * 支付扩展类型（0x10–0x1a）当前保留未启用：铸造/赎回路由保留，
 * 但不再构造任何扩展类型交易，统一返回
 * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class StableCoinService {

    /** 稳定币符号。 */
    @Value("${nexus.stablecoin.symbol:NEX}")
    private String symbol;

    /** 最低抵押率（百分比，如 150 表示 150%）。 */
    @Value("${nexus.stablecoin.min-ratio:150}")
    private int minCollateralRatio;

    /** 稳定币锚定价格（如 USDT 锚定 USD 为 1.00）。 */
    @Value("${nexus.stablecoin.peg-price:1.00}")
    private String pegPrice;

    /** 价格来源标识（如 PEG / oracle / exchange）。 */
    @Value("${nexus.stablecoin.price-source:PEG}")
    private String priceSource;

    /**
     * 铸造稳定币。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code MINT_STABLECOIN} 交易。</p>
     *
     * @param fromPubkey       抵押人公钥（十六进制字符串，32 字节）
     * @param collateralAmount 抵押 NEX 数量（最小单位）
     * @param mintAmount       铸造稳定币数量（最小单位）
     * @param prikey           抵押人私钥（十六进制字符串，32 字节）
     * @param nonce            交易 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult mint(String fromPubkey, long collateralAmount, long mintAmount,
                          String prikey, long nonce) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }
        if (collateralAmount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "collateralAmount must be greater than 0");
        }
        if (mintAmount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "mintAmount must be greater than 0");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 MINT_STABLECOIN 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 赎回稳定币。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code REDEEM_STABLECOIN} 交易。</p>
     *
     * @param fromPubkey  赎回人公钥（十六进制字符串，32 字节）
     * @param redeemAmount 赎回稳定币数量（最小单位）
     * @param prikey      赎回人私钥（十六进制字符串，32 字节）
     * @param nonce       交易 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult redeem(String fromPubkey, long redeemAmount, String prikey, long nonce) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }
        if (redeemAmount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "redeemAmount must be greater than 0");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 REDEEM_STABLECOIN 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 查询地址的抵押率和仓位信息。
     *
     * <p>支付扩展类型当前保留未启用，本接口固定返回
     * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}，不再返回模拟仓位。</p>
     *
     * @param address NEX 地址
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult getCollateralRatio(String address) {
        try {
            if (address == null || address.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "address is required");
            }

            // 支付扩展（0x10–0x1a）保留未启用：不再返回模拟零仓位/HEALTHY 状态。
            return APIResult.paymentExtensionNotEnabled();
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query collateral: " + e.getMessage());
        }
    }

    /**
     * 查询稳定币当前价格。
     *
     * <p>返回锚定价格（peg price）。稳定币以抵押机制锚定法币价值，
     * 价格由 {@code nexus.stablecoin.peg-price} 配置决定（默认 1.00）；
     * 来源标识由 {@code nexus.stablecoin.price-source} 配置决定（默认 PEG）。
     * 若需接入实时价格预言机，将 price-source 配置为 oracle 并由外部
     * 喂价任务更新 peg-price 即可。</p>
     *
     * @return 统一响应结果，data 中包含价格信息
     */
    public APIResult getPrice() {
        try {
            Map<String, Object> priceInfo = new LinkedHashMap<>();
            priceInfo.put("symbol", symbol);
            priceInfo.put("price", pegPrice);
            priceInfo.put("source", priceSource);
            priceInfo.put("timestamp", System.currentTimeMillis() / 1000);

            return APIResult.newSuccess(priceInfo);
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query price: " + e.getMessage());
        }
    }
}
