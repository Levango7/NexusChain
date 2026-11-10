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
import org.nexus.keystore.crypto.SHA3Utility;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨链桥业务服务。
 *
 * <p>负责 NEX 跨链桥的锁定（{@code BRIDGE_LOCK}）、铸造（{@code BRIDGE_MINT}）、
 * 销毁（{@code BRIDGE_BURN}）路由。跨链流程为：源链锁定 -> 目标链铸造，
 * 反向流程为：目标链销毁 -> 源链解锁。</p>
 *
 * <p>支付扩展类型（0x10–0x1a）当前保留未启用：写路由保留、入参校验保留，
 * 但不再构造任何扩展类型交易，统一返回
 * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BridgeService {

    /** 默认验证人签名数量阈值。 */
    @Value("${nexus.bridge.min-validators:3}")
    private int minValidators;

    /** 时间锁持续时间（秒）。 */
    @Value("${nexus.bridge.timelock-duration:3600}")
    private long timelockDuration;

    /** 单笔跨链交易金额上限（NEX 最小单位）。 */
    @Value("${nexus.bridge.single-tx-limit:1000000000}")
    private long singleTxLimit;

    /** 每日跨链交易总额上限（NEX 最小单位）。 */
    @Value("${nexus.bridge.daily-limit:10000000000}")
    private long dailyLimit;

    /** BRIDGE_MINT 消息哈希域分隔前缀（防止跨消息类型重放）。 */
    private static final String MINT_MSG_HASH_DOMAIN = "NEXUS-BRIDGE-MINT-v1";

    /**
     * 锁定资产。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code BRIDGE_LOCK} 交易。</p>
     *
     * @param fromPubkey  锁定发起方公钥（十六进制字符串，32 字节）
     * @param targetChain 目标链标识
     * @param recipient    目标链收款人地址
     * @param amount       锁定金额（NEX 最小单位）
     * @param prikey       发起方私钥（十六进制字符串，32 字节）
     * @param nonce        交易 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult lock(String fromPubkey, String targetChain, String recipient,
                          long amount, String prikey, long nonce) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (targetChain == null || targetChain.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "targetChain is required");
        }
        if (recipient == null || recipient.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "recipient is required");
        }
        if (amount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
        }
        if (amount > singleTxLimit) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "amount " + amount + " exceeds single transaction limit " + singleTxLimit);
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 BRIDGE_LOCK 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 计算规范化的 BRIDGE_MINT 消息哈希（域分隔）。
     *
     * <p>对 {@code bridgeTxId / sourceChain / recipient / amount / timelock}
     * 做定界编码后取 keccak256。验证人对该哈希做 Ed25519 签名，签名随
     * payload 上链并由 {@code BridgeRule} 逐签名验签。域分隔前缀防止
     * 跨消息类型重放。</p>
     *
     * @param bridgeTxId     桥交易 ID
     * @param sourceChain    源链标识（null 视为空串）
     * @param recipient      收款人地址（hex 字符串原样参与哈希）
     * @param amount         铸造金额（NEX 最小单位）
     * @param timelockExpiry 时间锁到期时间戳（秒）
     * @return 32 字节消息哈希
     */
    public static byte[] computeMintMessageHash(String bridgeTxId, String sourceChain,
                                                String recipient, long amount,
                                                long timelockExpiry) {
        String chain = sourceChain == null ? "" : sourceChain;
        ByteBuffer buf = ByteBuffer.allocate(
                MINT_MSG_HASH_DOMAIN.length() + 1
                        + bridgeTxId.length() + 1
                        + chain.length() + 1
                        + recipient.length() + 1
                        + 8 + 8);
        buf.put(MINT_MSG_HASH_DOMAIN.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
        buf.put(bridgeTxId.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
        buf.put(chain.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
        buf.put(recipient.getBytes(StandardCharsets.UTF_8)).put((byte) 0);
        buf.putLong(amount);
        buf.putLong(timelockExpiry);
        return SHA3Utility.keccak256(buf.array());
    }

    /**
     * 在目标链上铸造资产（v1.9.4 格式）。
     *
     * <p>验证人在确认源链锁定交易后，在目标链上铸造对应资产。构造
     * {@code BRIDGE_MINT} 类型交易，按 v1.9.4 安全修复后的二进制格式组装
     * payload 并提交到交易池：
     * <pre>[8B timelock][1B sigCount][32B messageHash][N×(32B pubkey + 64B sig)]</pre>
     * 其中 messageHash 为 {@link #computeMintMessageHash} 的域分隔哈希，
     * 验证人签名内容即该哈希。提交前逐签名预验签（fail-closed）。</p>
     *
     * @param bridgeTxId          桥交易 ID
     * @param sourceChain         源链标识
     * @param recipient           收款人地址
     * @param amount              铸造金额
     * @param validatorPubkeys    验证人公钥列表（64 hex = 32 字节，与签名一一对应）
     * @param validatorSignatures 验证人签名列表（128 hex = 64 字节，对 messageHash 的签名）
     * @return 统一响应结果，data 中包含铸造结果
     */
    public APIResult mint(String bridgeTxId, String sourceChain, String recipient,
                          long amount, List<String> validatorPubkeys,
                          List<String> validatorSignatures) {
        long timelockExpiry = System.currentTimeMillis() / 1000 + timelockDuration;
        return mint(bridgeTxId, sourceChain, recipient, amount,
                timelockExpiry, validatorPubkeys, validatorSignatures);
    }

    /**
     * 在目标链上铸造资产（显式时间锁版本，保留签名供兼容调用）。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留入参校验，
     * 但不再组装多签 payload、不再构造 {@code BRIDGE_MINT} 交易。</p>
     */
    APIResult mint(String bridgeTxId, String sourceChain, String recipient,
                   long amount, long timelockExpiry,
                   List<String> validatorPubkeys, List<String> validatorSignatures) {
        APIResult validation = validateMintRequest(bridgeTxId, recipient, amount, validatorSignatures);
        if (validation != null) {
            return validation;
        }
        if (validatorPubkeys == null || validatorPubkeys.size() != validatorSignatures.size()) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "validator pubkeys (" + (validatorPubkeys == null ? 0 : validatorPubkeys.size())
                            + ") must pair one-to-one with signatures ("
                            + validatorSignatures.size() + ")");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 BRIDGE_MINT 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 旧版入口：仅提供验证人签名列表。
     *
     * <p>公钥从 {@link ValidatorRegistry} 活跃验证人 ∪ 配置白名单
     * （{@code nexus.bridge.validator-pubkeys}）中解析：逐一用候选公钥对
     * 规范化 messageHash 验签匹配。任一签名无法归属到候选公钥时返回失败
     * （fail-closed），提示调用方改用带公钥列表的
     * {@link #mint(String, String, String, long, List, List)} 重载。</p>
     *
     * @param bridgeTxId          桥交易 ID
     * @param sourceChain         源链标识
     * @param recipient           收款人地址
     * @param amount              铸造金额
     * @param validatorSignatures 验证人签名列表（十六进制字符串）
     * @return 统一响应结果，data 中包含铸造结果
     */
    public APIResult mint(String bridgeTxId, String sourceChain, String recipient,
                          long amount, List<String> validatorSignatures) {
        APIResult validation = validateMintRequest(bridgeTxId, recipient, amount, validatorSignatures);
        if (validation != null) {
            return validation;
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再解析验证人公钥、不再构造 BRIDGE_MINT 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * mint 公共入参校验；通过返回 null，否则返回失败结果。
     */
    private APIResult validateMintRequest(String bridgeTxId, String recipient,
                                          long amount, List<String> validatorSignatures) {
        if (bridgeTxId == null || bridgeTxId.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "bridgeTxId is required");
        }
        if (recipient == null || recipient.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "recipient is required");
        }
        if (amount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
        }
        if (validatorSignatures == null || validatorSignatures.size() < minValidators) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "Not enough validator signatures: " +
                            (validatorSignatures == null ? 0 : validatorSignatures.size()) +
                            " < " + minValidators);
        }
        return null;
    }

    /**
     * 销毁目标链上的资产。
     *
     * <p>支付扩展类型（0x10–0x1a）保留未启用：保留路由与入参校验，
     * 但不再构造 {@code BRIDGE_BURN} 交易。</p>
     *
     * @param fromPubkey  销毁发起方公钥（十六进制字符串，32 字节）
     * @param targetChain 目标链标识
     * @param amount      销毁金额
     * @param prikey      发起方私钥（十六进制字符串，32 字节）
     * @param nonce       交易 nonce
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult burn(String fromPubkey, String targetChain, long amount,
                          String prikey, long nonce) {
        // v2.3.0 兼容入口：未提供 bridgeTxId 时使用旧格式 payload（无尾部）。
        return burn(fromPubkey, targetChain, amount, prikey, nonce, null);
    }

    /**
     * 销毁资产（v2.3.0 生命周期键统一版本）。
     *
     * <p>payload 格式：
     * <pre>[8B timestamp][1B flags][2B idLen][bridgeTxId UTF-8]</pre>
     * 携带与 lock 阶段一致的生命周期统一 ID，使
     * {@code PaymentTransactionProcessor.processBridgeBurn} 能直接命中
     * 同一条 {@link BridgeTransaction} 记录并驱动状态机 MINTED → BURNED。
     * {@code bridgeTxId} 为 {@code null}/空时回退旧格式（9 字节，无尾部）。</p>
     *
     * @param fromPubkey 销毁发起方公钥（hex）
     * @param targetChain 目标链标识
     * @param amount      销毁金额
     * @param prikey      发起方私钥（hex）
     * @param nonce       交易 nonce
     * @param bridgeTxId  生命周期统一桥交易 ID（可空，空则旧格式）
     * @return 统一响应结果
     */
    public APIResult burn(String fromPubkey, String targetChain, long amount,
                          String prikey, long nonce, String bridgeTxId) {
        if (fromPubkey == null || fromPubkey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
        }
        if (amount <= 0) {
            return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
        }
        if (prikey == null || prikey.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
        }

        // 支付扩展（0x10–0x1a）保留未启用：不再构造 BRIDGE_BURN 交易。
        return APIResult.paymentExtensionNotEnabled();
    }

    /**
     * 查询桥交易状态。
     *
     * <p>支付扩展类型当前保留未启用，本接口固定返回
     * {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}，不再返回模拟桥交易状态。</p>
     *
     * @param txHash 交易哈希或桥交易 ID
     * @return 统一响应结果；支付扩展未启用时返回 {@link APIResult#PAYMENT_EXTENSION_NOT_ENABLED}
     */
    public APIResult getStatus(String txHash) {
        try {
            if (txHash == null || txHash.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "txHash is required");
            }

            // 支付扩展（0x10–0x1a）保留未启用：不再返回模拟 LOCKED 状态。
            return APIResult.paymentExtensionNotEnabled();
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query bridge status: " + e.getMessage());
        }
    }

    /**
     * 查询当前桥交易限额。
     *
     * <p>返回单笔限额、每日限额、时间锁持续时间和最低验证人数等配置信息。
     * 骨架实现：每日已用额度返回 0。</p>
     *
     * @return 统一响应结果，data 中包含限额配置信息
     */
    public APIResult getLimit() {
        try {
            Map<String, Object> limitInfo = new LinkedHashMap<>();
            limitInfo.put("singleTxLimit", singleTxLimit);
            limitInfo.put("dailyLimit", dailyLimit);
            limitInfo.put("timelockDuration", timelockDuration);
            limitInfo.put("minValidators", minValidators);
            // 骨架：每日已用额度返回 0
            limitInfo.put("dailyUsed", 0L);
            limitInfo.put("dailyRemaining", dailyLimit);

            return APIResult.newSuccess(limitInfo);
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to query bridge limits: " + e.getMessage());
        }
    }
}
