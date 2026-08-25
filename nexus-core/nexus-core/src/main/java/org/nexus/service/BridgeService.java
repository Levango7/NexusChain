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

import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.keystore.util.JsonUtils;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.nexus.ApiResult.APIResult;
import org.nexus.core.account.Transaction;
import org.nexus.core.TransactionPool;
import org.nexus.core.payment.BridgeTransaction;
import org.nexus.crypto.CryptoException;
import org.nexus.crypto.ed25519.Ed25519PrivateKey;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.keystore.crypto.RipemdUtility;
import org.nexus.keystore.crypto.SHA3Utility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨链桥业务服务。
 *
 * <p>负责 NEX 跨链桥的锁定（{@code BRIDGE_LOCK}）、铸造（{@code BRIDGE_MINT}）、
 * 销毁（{@code BRIDGE_BURN}）交易构造与提交。跨链流程为：源链锁定 ->
 * 目标链铸造，反向流程为：目标链销毁 -> 源链解锁。</p>
 *
 * <p>锁定和销毁交易由发起方使用私钥签名；铸造交易由验证人提供多签
 * （签名列表嵌入 payload），交易本身的 signature 字段使用占位零签名。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BridgeService {

    /** 交易版本号，固定为 1。 */
    private static final int TX_VERSION = Transaction.DEFAULT_TRANSACTION_VERSION;

    /** 默认 gasPrice，按最小单位计费。 */
    private static final long DEFAULT_GAS_PRICE = 1L;

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

    @Autowired
    private TransactionPool txPool;

    /**
     * 验证人注册中心（可选注入），用于旧版 mint 签名列表的公钥归属解析。
     * {@code required=false} 以兼容无 Spring 上下文的单元测试。
     */
    @Autowired(required = false)
    private ValidatorRegistry validatorRegistry;

    /**
     * 桥验证人公钥白名单（逗号分隔 hex），与 {@code BridgeRule} 同名配置对齐，
     * 作为旧版 mint 公钥解析的候选来源之一。
     */
    @Value("${nexus.bridge.validator-pubkeys:}")
    private String validatorPubkeysConfig;

    /** BRIDGE_MINT 消息哈希域分隔前缀（防止跨消息类型重放）。 */
    private static final String MINT_MSG_HASH_DOMAIN = "NEXUS-BRIDGE-MINT-v1";

    /**
     * 锁定资产。
     *
     * <p>在源链上锁定 NEX 代币，为跨链转账做准备。构造
     * {@link BridgeTransaction} 对象并调用 {@link BridgeTransaction#lock()}，
     * 随后构造 {@code BRIDGE_LOCK} 类型交易并提交到交易池。</p>
     *
     * @param fromPubkey  锁定发起方公钥（十六进制字符串，32 字节）
     * @param targetChain 目标链标识
     * @param recipient    目标链收款人地址
     * @param amount       锁定金额（NEX 最小单位）
     * @param prikey       发起方私钥（十六进制字符串，32 字节）
     * @param nonce        交易 nonce
     * @return 统一响应结果，data 中包含 bridgeTxId 等
     */
    public APIResult lock(String fromPubkey, String targetChain, String recipient,
                          long amount, String prikey, long nonce) {
        try {
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

            // 构造桥交易对象
            String bridgeTxId = "br_" + System.currentTimeMillis();
            long timelockExpiry = System.currentTimeMillis() / 1000 + timelockDuration;
            BridgeTransaction bridgeTx = new BridgeTransaction(
                    bridgeTxId, "NEX", targetChain, amount, recipient, minValidators, timelockExpiry);
            bridgeTx.lock();

            byte[] fromBytes = Hex.decodeHex(fromPubkey.toCharArray());
            if (fromBytes.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "fromPubkey must be " + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + fromBytes.length);
            }
            byte[] toBytes = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromBytes));

            byte[] payload = bridgeTx.toJson().getBytes(StandardCharsets.UTF_8);
            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.BRIDGE_LOCK.ordinal(),
                    nonce,
                    fromBytes,
                    DEFAULT_GAS_PRICE,
                    amount,
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
            data.put("bridgeTxId", bridgeTxId);
            data.put("from", fromPubkey);
            data.put("amount", amount);
            data.put("targetChain", targetChain);
            data.put("recipient", recipient);
            data.put("state", bridgeTx.getState().name());
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid hex format: " + e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Lock validation failed: " + e.getMessage());
        } catch (CryptoException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Signing failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to lock assets: " + e.getMessage());
        }
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
     * 在目标链上铸造资产（显式时间锁版本，包可见供单元测试构造确定性 payload）。
     */
    APIResult mint(String bridgeTxId, String sourceChain, String recipient,
                   long amount, long timelockExpiry,
                   List<String> validatorPubkeys, List<String> validatorSignatures) {
        try {
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

            byte[] messageHash = computeMintMessageHash(
                    bridgeTxId, sourceChain, recipient, amount, timelockExpiry);

            int n = validatorSignatures.size();
            byte[][] pubkeyBytes = new byte[n][];
            byte[][] sigBytes = new byte[n][];
            for (int i = 0; i < n; i++) {
                try {
                    pubkeyBytes[i] = Hex.decodeHex(validatorPubkeys.get(i).toCharArray());
                    sigBytes[i] = Hex.decodeHex(validatorSignatures.get(i).toCharArray());
                } catch (DecoderException e) {
                    return APIResult.newFailResult(APIResult.FAIL,
                            "invalid validator pubkey/signature hex at index " + i + ": " + e.getMessage());
                }
                if (pubkeyBytes[i].length != Transaction.PUBLIC_KEY_SIZE) {
                    return APIResult.newFailResult(APIResult.FAIL,
                            "validator pubkey at index " + i + " must be "
                                    + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + pubkeyBytes[i].length);
                }
                if (sigBytes[i].length != Transaction.SIGNATURE_SIZE) {
                    return APIResult.newFailResult(APIResult.FAIL,
                            "validator signature at index " + i + " must be "
                                    + Transaction.SIGNATURE_SIZE + " bytes, got " + sigBytes[i].length);
                }
                // fail-closed：提交前逐签名预验签，避免无效多签入池后被链上规则拒绝
                try {
                    if (!new Ed25519PublicKey(pubkeyBytes[i]).verify(messageHash, sigBytes[i])) {
                        return APIResult.newFailResult(APIResult.FAIL,
                                "validator signature at index " + i
                                        + " does not verify against its pubkey and messageHash");
                    }
                } catch (RuntimeException e) {
                    return APIResult.newFailResult(APIResult.FAIL,
                            "signature pre-verification error at index " + i + ": " + e.getMessage());
                }
            }

            // v1.9.4 格式：[8B timelock][1B sigCount][32B messageHash][N×(32B pubkey + 64B sig)]
            ByteBuffer payloadBuf = ByteBuffer.allocate(
                    8 + 1 + 32 + n * (Transaction.PUBLIC_KEY_SIZE + Transaction.SIGNATURE_SIZE));
            payloadBuf.putLong(timelockExpiry);
            payloadBuf.put((byte) n);
            payloadBuf.put(messageHash);
            for (int i = 0; i < n; i++) {
                payloadBuf.put(pubkeyBytes[i]);
                payloadBuf.put(sigBytes[i]);
            }

            return submitMintTransaction(bridgeTxId, sourceChain, recipient, amount,
                    payloadBuf.array(), n);
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to mint bridge assets: " + e.getMessage());
        }
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

        long timelockExpiry = System.currentTimeMillis() / 1000 + timelockDuration;
        byte[] messageHash = computeMintMessageHash(
                bridgeTxId, sourceChain, recipient, amount, timelockExpiry);

        List<String> candidatePubkeys = collectCandidateValidatorPubkeys();
        if (candidatePubkeys.isEmpty()) {
            return APIResult.newFailResult(APIResult.FAIL,
                    "cannot resolve validator public keys (no ValidatorRegistry active validators "
                            + "and nexus.bridge.validator-pubkeys unset); use mint(...) with explicit "
                            + "validatorPubkeys list");
        }

        List<String> resolvedPubkeys = new ArrayList<>();
        for (String sigHex : validatorSignatures) {
            byte[] sig;
            try {
                sig = Hex.decodeHex(sigHex.toCharArray());
            } catch (DecoderException e) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "invalid validator signature hex: " + e.getMessage());
            }
            String matched = null;
            for (String candidateHex : candidatePubkeys) {
                try {
                    byte[] candidate = Hex.decodeHex(candidateHex.toCharArray());
                    if (candidate.length == Transaction.PUBLIC_KEY_SIZE
                            && sig.length == Transaction.SIGNATURE_SIZE
                            && new Ed25519PublicKey(candidate).verify(messageHash, sig)) {
                        matched = candidateHex;
                        break;
                    }
                } catch (DecoderException | RuntimeException ignored) {
                    // 候选公钥格式异常则跳过
                }
            }
            if (matched == null) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "signature could not be attributed to any registered validator pubkey; "
                                + "use mint(...) with explicit validatorPubkeys list");
            }
            resolvedPubkeys.add(matched);
        }

        return mint(bridgeTxId, sourceChain, recipient, amount,
                timelockExpiry, resolvedPubkeys, validatorSignatures);
    }

    /**
     * 收集候选验证人公钥（小写 hex）：注册中心活跃验证人 ∪ 配置白名单。
     */
    private List<String> collectCandidateValidatorPubkeys() {
        List<String> candidates = new ArrayList<>();
        if (validatorRegistry != null) {
            try {
                for (Validator v : validatorRegistry.getActiveValidators()) {
                    if (v != null && v.getPublicKey() != null && !v.getPublicKey().isEmpty()) {
                        candidates.add(stripHexPrefix(v.getPublicKey()).toLowerCase());
                    }
                }
            } catch (RuntimeException ignored) {
                // 注册中心读取失败时仅使用配置白名单
            }
        }
        if (validatorPubkeysConfig != null && !validatorPubkeysConfig.isEmpty()) {
            for (String token : validatorPubkeysConfig.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    candidates.add(stripHexPrefix(trimmed).toLowerCase());
                }
            }
        }
        return candidates;
    }

    private static String stripHexPrefix(String hex) {
        if (hex == null) {
            return "";
        }
        return (hex.startsWith("0x") || hex.startsWith("0X")) ? hex.substring(2) : hex;
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
     * 解析收款人并提交 BRIDGE_MINT 交易到交易池。
     */
    private APIResult submitMintTransaction(String bridgeTxId, String sourceChain,
                                            String recipient, long amount,
                                            byte[] payload, int sigCount) {
        // tx.to 填充真实 recipient（processBridgeMint 用 pubKeyHashToHex(tx.to) 作为收款人）
        byte[] recipientHash;
        try {
            byte[] recipientBytes = Hex.decodeHex(recipient.toCharArray());
            // recipient 可能是完整 pubkey（64 hex=32B）或 pubkeyHash（40 hex=20B）
            if (recipientBytes.length == Transaction.PUBLIC_KEY_SIZE) {
                recipientHash = RipemdUtility.ripemd160(SHA3Utility.keccak256(recipientBytes));
            } else if (recipientBytes.length == Transaction.PUBLIC_KEY_HASH_SIZE) {
                recipientHash = recipientBytes;
            } else {
                return APIResult.newFailResult(APIResult.FAIL,
                        "recipient must be pubkey (64 hex) or pubkeyHash (40 hex), got "
                                + recipientBytes.length + " bytes");
            }
        } catch (RuntimeException | DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "invalid recipient hex: " + e.getMessage());
        }

        byte[] placeholderFrom = new byte[Transaction.PUBLIC_KEY_SIZE];
        byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

        Transaction tx = new Transaction(
                TX_VERSION,
                Transaction.Type.BRIDGE_MINT.ordinal(),
                0L,
                placeholderFrom,
                DEFAULT_GAS_PRICE,
                amount,
                payload,
                recipientHash,
                emptySig
        );

        txPool.add(tx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("bridgeTxId", bridgeTxId);
        data.put("amount", amount);
        data.put("recipient", recipient);
        data.put("sourceChain", sourceChain);
        data.put("sigCount", sigCount);
        data.put("state", BridgeTransaction.State.MINTED.name());
        data.put("txHash", tx.getHashHexString());

        return APIResult.newSuccess(data);
    }

    /**
     * 销毁目标链上的资产。
     *
     * <p>在目标链上销毁跨链铸造的资产，为反向解锁做准备。
     * 构造 {@code BRIDGE_BURN} 类型交易并提交到交易池。</p>
     *
     * @param fromPubkey  销毁发起方公钥（十六进制字符串，32 字节）
     * @param targetChain 目标链标识
     * @param amount      销毁金额
     * @param prikey      发起方私钥（十六进制字符串，32 字节）
     * @param nonce       交易 nonce
     * @return 统一响应结果，data 中包含销毁结果
     */
    public APIResult burn(String fromPubkey, String targetChain, long amount,
                          String prikey, long nonce) {
        try {
            if (fromPubkey == null || fromPubkey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "fromPubkey is required");
            }
            if (amount <= 0) {
                return APIResult.newFailResult(APIResult.FAIL, "amount must be greater than 0");
            }
            if (prikey == null || prikey.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "prikey is required");
            }

            byte[] fromBytes = Hex.decodeHex(fromPubkey.toCharArray());
            if (fromBytes.length != Transaction.PUBLIC_KEY_SIZE) {
                return APIResult.newFailResult(APIResult.FAIL,
                        "fromPubkey must be " + Transaction.PUBLIC_KEY_SIZE + " bytes, got " + fromBytes.length);
            }
            byte[] toBytes = RipemdUtility.ripemd160(SHA3Utility.keccak256(fromBytes));

            // PLAN-004 同类修复：burn payload 改二进制格式（对齐 BridgeRule 解析约定）：
            // 前 8 字节 = 时间戳，第 9 字节 = 签名数。burn 为单用户自签（tx.signature
            // 由 Ed25519 验签），非验证人多签——签名数填 0，BridgeRule 不再要求多签。
            ByteBuffer payloadBuf = ByteBuffer.allocate(8 + 1);
            payloadBuf.putLong(System.currentTimeMillis() / 1000);
            payloadBuf.put((byte) 0);
            byte[] payload = payloadBuf.array();

            byte[] emptySig = new byte[Transaction.SIGNATURE_SIZE];

            Transaction tx = new Transaction(
                    TX_VERSION,
                    Transaction.Type.BRIDGE_BURN.ordinal(),
                    nonce,
                    fromBytes,
                    DEFAULT_GAS_PRICE,
                    amount,
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
            data.put("amount", amount);
            data.put("targetChain", targetChain);
            data.put("state", BridgeTransaction.State.BURNED.name());
            data.put("txHash", tx.getHashHexString());

            return APIResult.newSuccess(data);
        } catch (DecoderException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Invalid hex format: " + e.getMessage());
        } catch (CryptoException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Signing failed: " + e.getMessage());
        } catch (RuntimeException e) {
            return APIResult.newFailResult(APIResult.FAIL, "Failed to burn bridge assets: " + e.getMessage());
        }
    }

    /**
     * 查询桥交易状态。
     *
     * <p>骨架实现：返回基于 txHash 的模拟桥交易状态。后续可从
     * 桥交易数据库查询真实状态。</p>
     *
     * @param txHash 交易哈希或桥交易 ID
     * @return 统一响应结果，data 中包含 {@link BridgeTransaction} 对象
     */
    public APIResult getStatus(String txHash) {
        try {
            if (txHash == null || txHash.isEmpty()) {
                return APIResult.newFailResult(APIResult.FAIL, "txHash is required");
            }

            // 骨架：返回模拟桥交易状态
            BridgeTransaction bridgeTx = new BridgeTransaction();
            bridgeTx.setBridgeTxId(txHash);
            bridgeTx.setState(BridgeTransaction.State.LOCKED);

            return APIResult.newSuccess(bridgeTx);
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
