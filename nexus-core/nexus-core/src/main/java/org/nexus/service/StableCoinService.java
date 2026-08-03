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

import org.nexus.keystore.util.JsonUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.core.TransactionPool;
import org.nexus.core.payment.StableCoinPosition;
import org.nexus.crypto.CryptoException;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 稳定币业务服务。
 *
 * <p>负责 NEX 稳定币系统的铸造（{@code MINT_STABLECOIN}）、赎回
 * （{@code REDEEM_STABLECOIN}）、抵押率查询和价格查询。
 * 用户通过抵押 NEX 代币铸造稳定币，抵押率须满足最低要求。</p>
 *
 * <p>铸造交易 payload 为 {@link StableCoinPosition#toJson()} 的 UTF-8 字节，
 * 赎回交易 payload 包含赎回金额和仓位 ID。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class StableCoinService {

    /** 交易版本号，固定为 1。 */
    private static final int TX_VERSION = Transaction.DEFAULT_TRANSACTION_VERSION;

    /** 默认 gasPrice，按最小单位计费。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

    /** 稳定币符号。 */
    @Value("${nexus.stablecoin.symbol:NEX}")
    private String symbol;

    /** 最低抵押率（百分比，如 150 表示 150%）。 */
    @Value("${nexus.stablecoin.min-ratio:150}")
    private int minCollateralRatio;

    @Autowired
    private TransactionPool txPool;

    /**
     * 铸造稳定币。
     *
     * <p>创建 {@link StableCoinPosition} 仓位对象并调用
     * {@link StableCoinPosition#mint(long, long, int)}，随后构造
     * {@code MINT_STABLECOIN} 类型交易并提交到交易池。</p>
     *
     * @param fromPubkey       抵押人公钥（十六进制字符串，32 字节）
     * @param collateralAmount 抵押 NEX 数量（最小单位）
     * @param mintAmount       铸造稳定币数量（最小单位）
     * @param prikey           抵押人私钥（十六进制字符串，32 字节）
     * @param nonce            交易 nonce
     * @return 统一响应结果，data 中包含 positionId、mintedAmount、collateralRatio 等
     */
    public APIResult mint(String fromPubkey, long collateralAmount, long mintAmount,
                          String prikey, long nonce) {
        try {
            if (fromPubkey == null || fromPubkey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
            }
            if (prikey == null || prikey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
            }

            // 计算地址（公钥哈希）用于仓位归属
            byte[] fromBytes = Hex.decodeHex(fromPubkey.toCharArray());
            if (fromBytes.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "fromPubkey must be " + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + fromBytes.length);
            }
            byte[] toBytes = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromBytes));
            String ownerHex = Hex.encodeHexString(toBytes);

            // 构造仓位并执行铸造
            String positionId = "pos_" + System.currentTimeMillis();
            StableCoinPosition position = new StableCoinPosition(positionId, ownerHex, 0L);
            position.mint(collateralAmount, mintAmount, minCollateralRatio);

            // 构造 MINT_STABLECOIN 交易
            byte[] payload = position.toJson().getBytes(StandardCharsets.UTF_8);
            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.MINT_STABLECOIN.ordinal(),
                    nonce,
                    fromBytes,
                    DEFAULT_GAS_PRICE,
                    collateralAmount,
                    payload,
                    toBytes,
                    emptySig
            );

            // 签名
            byte[] prikeyBytes = Hex.decodeHex(prikey.toCharArray());
            Ed25519PrivateKey privateKey = new Ed25519PrivateKey(prikeyBytes);
            byte[] signature = privateKey.sign(tx.getRawForSign());
            tx.signature = signature;

            txPool.add(tx);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("positionId", positionId);
            data.put("owner", ownerHex);
            data.put("collateralAmount", collateralAmount);
            data.put("mintedAmount", mintAmount);
            data.put("collateralRatio", position.getCollateralRatio());
            data.put("liquidationPrice", position.getLiquidationPrice());
            data.put("state", position.getState().name());
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid hex format: " + e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Mint validation failed: " + e.getMessage());
        } catch (CryptoException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Signing failed: " + e.getMessage());
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to mint stablecoin: " + e.getMessage());
        }
    }

    /**
     * 赎回稳定币。
     *
     * <p>构造 {@code REDEEM_STABLECOIN} 类型交易并提交到交易池。
     * payload 包含赎回金额和仓位所有者信息。</p>
     *
     * @param fromPubkey  赎回人公钥（十六进制字符串，32 字节）
     * @param redeemAmount 赎回稳定币数量（最小单位）
     * @param prikey      赎回人私钥（十六进制字符串，32 字节）
     * @param nonce       交易 nonce
     * @return 统一响应结果，data 中包含赎回结果
     */
    public APIResult redeem(String fromPubkey, long redeemAmount, String prikey, long nonce) {
        try {
            if (fromPubkey == null || fromPubkey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
            }
            if (prikey == null || prikey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
            }
            if (redeemAmount <= 0) {
                return APIResult.newFailResult(APIResult.FAIL, "redeemAmount must be greater than 0");
            }

            byte[] fromBytes = Hex.decodeHex(fromPubkey.toCharArray());
            if (fromBytes.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "fromPubkey must be " + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + fromBytes.length);
            }
            byte[] toBytes = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromBytes));

            // 构造赎回 payload
            Map<String, Object> redeemPayload = new LinkedHashMap<>();
            redeemPayload.put("owner", Hex.encodeHexString(toBytes));
            redeemPayload.put("redeemAmount", redeemAmount);
            redeemPayload.put("action", "REDEEM");
            byte[] payload = JsonUtils.toJson(redeemPayload).getBytes(StandardCharsets.UTF_8);

            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.REDEEM_STABLECOIN.ordinal(),
                    nonce,
                    fromBytes,
                    DEFAULT_GAS_PRICE,
                    redeemAmount,
                    payload,
                    toBytes,
                    emptySig
            );

            // 签名
            byte[] prikeyBytes = Hex.decodeHex(prikey.toCharArray());
            Ed25519PrivateKey privateKey = new Ed25519PrivateKey(prikeyBytes);
            byte[] signature = privateKey.sign(tx.getRawForSign());
            tx.signature = signature;

            txPool.add(tx);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("redeemAmount", redeemAmount);
            data.put("status", "PENDING");
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid hex format: " + e.getMessage());
        } catch (CryptoException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Signing failed: " + e.getMessage());
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to redeem stablecoin: " + e.getMessage());
        }
    }

    /**
     * 查询地址的抵押率和仓位信息。
     *
     * <p>骨架实现：返回零余额的模拟仓位。后续可从 {@link StateDB}
     * 查询用户真实仓位。</p>
     *
     * @param address NEX 地址
     * @return 统一响应结果，data 中包含 {@link StableCoinPosition} 对象
     */
    public APIResult getCollateralRatio(String address) {
        try {
            if (address == null || address.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "address is required");
            }

            // 骨架：返回模拟仓位
            StableCoinPosition position = new StableCoinPosition();
            position.setOwner(address);
            position.setCollateralAmount(0L);
            position.setMintedAmount(0L);
            position.setCollateralRatio(0);
            position.setState(StableCoinPosition.State.HEALTHY);

            return APIResult.newSuccess(position);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query collateral: " + e.getMessage());
        }
    }

    /**
     * 查询稳定币当前价格。
     *
     * <p>骨架实现：返回固定价格 1.00。后续可接入价格预言机获取最新价格。</p>
     *
     * @return 统一响应结果，data 中包含价格信息
     */
    public APIResult getPrice() {
        try {
            Map<String, Object> priceInfo = new LinkedHashMap<>();
            priceInfo.put("symbol", symbol);
            priceInfo.put("price", "1.00");
            priceInfo.put("source", "oracle");
            priceInfo.put("timestamp", System.currentTimeMillis() / 1000);

            return APIResult.newSuccess(priceInfo);
        } catch (Exception e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query price: " + e.getMessage());
        }
    }
}
