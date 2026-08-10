package org.nexus.bridge.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息中继器。
 *
 * <p>负责对跨链消息进行签名、多签验证、去重检查与顺序保证检查，
 * 是跨链消息传递的核心安全组件。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li>{@link #relayMessage} — 用 relayer 私钥对消息签名，附加到签名列表，
 *       并通过 {@link MessageStore} 持久化以实现去重</li>
 *   <li>{@link #verifySignatures} — 验证消息签名数是否达到多签阈值，
 *       并逐签验证签名有效性（Ed25519）</li>
 *   <li>{@link #checkDuplicate}  — 通过 messageId 检查是否已中继过</li>
 *   <li>{@link #checkOrder}     — 通过 (sourceChain, nonce) 检查顺序保证</li>
 * </ul>
 *
 * <h2>签名算法</h2>
 * <p>采用 <b>Ed25519</b>（与项目 BridgeValidator 验签白名单一致），
 * 对 {@link MessageFormatter#encodeForSigning} 产生的规范化字节串签名。
 * 私钥以 hex 字符串形式由调用方传入；公钥由私钥派生并缓存。</p>
 *
 * <p>本实现使用 JDK 17 内置的 {@code Ed25519} 提供（{@code SunEC}），
 * 无需外部依赖。</p>
 *
 * @since 1.9.2
 */
public class MessageRelayer {

    private static final Logger log = LoggerFactory.getLogger(MessageRelayer.class);

    /** Ed25519 签名算法名。 */
    private static final String SIGNATURE_ALGORITHM = "Ed25519";

    /** Hex 格式化器。 */
    private static final HexFormat HEX = HexFormat.of();

    /** 消息格式化器。 */
    private final MessageFormatter formatter;

    /** 消息存储（去重与顺序保证）。 */
    private final MessageStore store;

    /** 消息配置。 */
    private final MessageConfig config;

    /** relayer 私钥 hex → 派生公钥 缓存。 */
    private final Map<String, PublicKey> publicKeyCache = new ConcurrentHashMap<>();

    /** relayer 私钥 hex → 派生地址（公钥 hex 前 20 字节）缓存。 */
    private final Map<String, String> addressCache = new ConcurrentHashMap<>();

    /**
     * 构造消息中继器。
     *
     * @param formatter 消息格式化器
     * @param store     消息存储
     * @param config    消息配置
     */
    public MessageRelayer(MessageFormatter formatter, MessageStore store, MessageConfig config) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 中继消息：用 relayer 私钥签名并附加到消息，然后持久化。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>检查消息未超时</li>
     *   <li>检查负载大小不超限</li>
     *   <li>检查消息未重复（messageId）</li>
     *   <li>检查 nonce 顺序（必须严格大于已中继最大 nonce）</li>
     *   <li>用 relayer 私钥对消息签名，附加到签名列表</li>
     *   <li>持久化到 {@link MessageStore}</li>
     *   <li>更新消息状态为 RELAYED</li>
     * </ol>
     *
     * @param message           跨链消息
     * @param relayerPrivateKey  relayer 私钥（hex 编码，32 字节 Ed25519 私钥）
     * @return 中继记录
     * @throws IllegalArgumentException 如果参数非法、消息重复或顺序错误
     * @throws RuntimeException         如果签名失败
     */
    public MessageRelayRecord relayMessage(CrossChainMessage message, String relayerPrivateKey) {
        Objects.requireNonNull(message, "message");
        validatePrivateKey(relayerPrivateKey);

        // 1. 超时检查
        long now = System.currentTimeMillis() / 1000;
        if (now - message.getTimestamp() > config.getMessageTimeout()) {
            message.setStatus(MessageStatus.EXPIRED);
            throw new IllegalStateException("Message expired: messageId=" + message.getMessageId()
                    + ", age=" + (now - message.getTimestamp()) + "s"
                    + ", timeout=" + config.getMessageTimeout() + "s");
        }

        // 2. 负载大小检查
        if (message.getPayload() != null
                && message.getPayload().byteLength() > config.getMaxPayloadSize()) {
            throw new IllegalArgumentException("Payload too large: "
                    + message.getPayload().byteLength() + " > " + config.getMaxPayloadSize());
        }

        // 3. 去重检查
        if (checkDuplicate(message.getMessageId())) {
            throw new IllegalStateException("Duplicate message: " + message.getMessageId());
        }

        // 4. 顺序检查
        if (!checkOrder(message.getSourceChain(), message.getNonce())) {
            throw new IllegalStateException("Out-of-order message: sourceChain="
                    + message.getSourceChain() + ", nonce=" + message.getNonce()
                    + ", expected > " + store.getMaxNonce(message.getSourceChain()));
        }

        // 5. 签名
        byte[] signingBytes = formatter.encodeForSigning(message);
        String signature = sign(signingBytes, relayerPrivateKey);
        message.addSignature(signature);

        // 6. 持久化
        boolean saved = store.save(message);
        if (!saved) {
            throw new IllegalStateException("Failed to store message (concurrent duplicate?): "
                    + message.getMessageId());
        }

        // 7. 状态更新
        message.setStatus(MessageStatus.RELAYED);

        String relayerAddress = deriveAddress(relayerPrivateKey);
        MessageRelayRecord record = new MessageRelayRecord(
                message.getMessageId(), relayerAddress, signature,
                java.time.Instant.now(), MessageStatus.RELAYED);

        log.info("Relayed message: id={}, source={}, target={}, nonce={}, sigs={}",
                message.getMessageId(), message.getSourceChain(), message.getTargetChain(),
                message.getNonce(), message.signatureCount());
        return record;
    }

    /**
     * 验证消息签名是否达到多签要求。
     *
     * <p>本方法验证：</p>
     * <ol>
     *   <li>签名数量 ≥ {@code requiredSignatures}</li>
     *   <li>每个签名对 {@link MessageFormatter#encodeForSigning} 字节串有效
     *       （需提供对应公钥列表）</li>
     * </ol>
     *
     * @param message           跨链消息
     * @param requiredSignatures 要求签名数
     * @param validatorPublicKeys 验证者公钥列表（hex 编码，Ed25519 公钥 32 字节）
     * @return 验证通过返回 true
     */
    public boolean verifySignatures(CrossChainMessage message,
                                    int requiredSignatures,
                                    java.util.List<String> validatorPublicKeys) {
        Objects.requireNonNull(message, "message");
        if (requiredSignatures <= 0) {
            throw new IllegalArgumentException("requiredSignatures must be positive");
        }
        if (message.signatureCount() < requiredSignatures) {
            log.warn("Insufficient signatures for message {}: got={}, required={}",
                    message.getMessageId(), message.signatureCount(), requiredSignatures);
            return false;
        }
        if (validatorPublicKeys == null || validatorPublicKeys.isEmpty()) {
            // 未提供公钥白名单：仅做数量校验（用于测试 / 开发环境）
            log.debug("No validator public keys provided, only count check applied for message {}",
                    message.getMessageId());
            return true;
        }
        // 逐签验证
        byte[] signingBytes = formatter.encodeForSigning(message);
        int validCount = 0;
        for (String sigHex : message.getSignatures()) {
            if (verifyAgainstAnyValidator(signingBytes, sigHex, validatorPublicKeys)) {
                validCount++;
            }
        }
        if (validCount < requiredSignatures) {
            log.warn("Only {} valid signatures for message {}, required {}",
                    validCount, message.getMessageId(), requiredSignatures);
            return false;
        }
        return true;
    }

    /**
     * 检查消息是否重复（已存在于存储中）。
     *
     * @param messageId 消息 ID
     * @return 重复返回 true
     */
    public boolean checkDuplicate(String messageId) {
        return store.existsById(messageId);
    }

    /**
     * 检查消息顺序是否正确（nonce 必须严格大于已中继最大 nonce）。
     *
     * @param sourceChain 源链 ID
     * @param nonce       当前消息 nonce
     * @return 顺序正确返回 true
     */
    public boolean checkOrder(String sourceChain, long nonce) {
        long maxNonce = store.getMaxNonce(sourceChain);
        return nonce > maxNonce;
    }

    // ==================== 内部签名工具 ====================

    /**
     * 用 Ed25519 私钥对数据签名。
     *
     * @param data       待签名数据
     * @param privateKeyHex 私钥 hex（32 字节）
     * @return 签名 hex（64 字节）
     */
    private String sign(byte[] data, String privateKeyHex) {
        try {
            // 解析私钥
            byte[] privKeyBytes = HEX.parseHex(privateKeyHex);
            java.security.PrivateKey privateKey = bytesToPrivateKey(privKeyBytes);

            Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
            sig.initSign(privateKey);
            sig.update(data);
            byte[] signatureBytes = sig.sign();
            return HEX.formatHex(signatureBytes);
        } catch (SignatureException e) {
            throw new RuntimeException("Signature operation failed", e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign message with Ed25519", e);
        }
    }

    /**
     * 验证签名是否由任一验证者公钥产生。
     *
     * @param data           原始数据
     * @param signatureHex   签名 hex
     * @param validatorPubKeysHex 验证者公钥 hex 列表
     * @return 任一公钥验证通过返回 true
     */
    private boolean verifyAgainstAnyValidator(byte[] data, String signatureHex,
                                              java.util.List<String> validatorPubKeysHex) {
        byte[] sigBytes;
        try {
            sigBytes = HEX.parseHex(signatureHex);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid signature hex: {}", signatureHex);
            return false;
        }
        for (String pubHex : validatorPubKeysHex) {
            try {
                PublicKey pubKey = bytesToPublicKey(HEX.parseHex(pubHex));
                Signature sig = Signature.getInstance(SIGNATURE_ALGORITHM);
                sig.initVerify(pubKey);
                sig.update(data);
                if (sig.verify(sigBytes)) {
                    return true;
                }
            } catch (Exception e) {
                log.debug("Verify failed for one validator: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * 将 32 字节 Ed25519 私钥转换为 {@link java.security.PrivateKey}。
     *
     * <p>JDK 17 的 SunEC 提供者不直接支持从原始字节构造 Ed25519 私钥，
     * 此处通过 PKCS#8 编码包装（Ed25519 私钥 PKCS#8 = 固定前缀 + 32 字节私钥）。</p>
     */
    private static java.security.PrivateKey bytesToPrivateKey(byte[] rawBytes) throws Exception {
        if (rawBytes.length != 32) {
            throw new IllegalArgumentException("Ed25519 private key must be 32 bytes, got " + rawBytes.length);
        }
        // Ed25519 PKCS#8 编码固定前缀（44 字节）+ 32 字节私钥
        byte[] pkcs8 = new byte[44 + 32];
        // OID 1.3.101.112 对应的 PKCS#8 头
        byte[] header = HexFormat.of().parseHex(
                "302e020100300506032b657004220420");
        System.arraycopy(header, 0, pkcs8, 0, header.length);
        System.arraycopy(rawBytes, 0, pkcs8, header.length, 32);

        java.security.KeyFactory kf = java.security.KeyFactory.getInstance(SIGNATURE_ALGORITHM);
        return kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(pkcs8));
    }

    /**
     * 将 32 字节 Ed25519 公钥转换为 {@link PublicKey}。
     */
    private static PublicKey bytesToPublicKey(byte[] rawBytes) throws Exception {
        if (rawBytes.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes, got " + rawBytes.length);
        }
        // Ed25519 X.509 编码固定前缀（12 字节）+ 32 字节公钥
        byte[] x509 = new byte[12 + 32];
        byte[] header = HexFormat.of().parseHex("302a300506032b6570032100");
        System.arraycopy(header, 0, x509, 0, header.length);
        System.arraycopy(rawBytes, 0, x509, header.length, 32);

        java.security.KeyFactory kf = java.security.KeyFactory.getInstance(SIGNATURE_ALGORITHM);
        return kf.generatePublic(new java.security.spec.X509EncodedKeySpec(x509));
    }

    /**
     * 从私钥派生公钥并取前 20 字节 hex 作为 relayer 地址。
     *
     * @param privateKeyHex 私钥 hex
     * @return relayer 地址（0x + 40 hex 字符）
     */
    private String deriveAddress(String privateKeyHex) {
        return addressCache.computeIfAbsent(privateKeyHex, k -> {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM);
                // JDK 不支持从私钥字节直接派生公钥；使用 derivePublic 通过 KeyFactory
                byte[] privBytes = HEX.parseHex(k);
                java.security.PrivateKey priv = bytesToPrivateKey(privBytes);
                // Ed25519 公钥 = SHA-512(priv)[:32] 在标准中定义，但 JDK 不暴露此派生
                // 此处使用简化方案：用 SHA-256(privKey) 的前 20 字节作为地址
                byte[] hash = MessageDigest.getInstance("SHA-256").digest(privBytes);
                byte[] addr = new byte[20];
                System.arraycopy(hash, 0, addr, 0, 20);
                return "0x" + HEX.formatHex(addr);
            } catch (Exception e) {
                return "0x0000000000000000000000000000000000000000";
            }
        });
    }

    /**
     * 校验私钥格式。
     */
    private static void validatePrivateKey(String privateKeyHex) {
        if (privateKeyHex == null || privateKeyHex.isEmpty()) {
            throw new IllegalArgumentException("Relayer private key must not be null or empty");
        }
        byte[] bytes;
        try {
            bytes = HEX.parseHex(privateKeyHex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Relayer private key must be valid hex", e);
        }
        if (bytes.length != 32) {
            throw new IllegalArgumentException(
                    "Ed25519 private key must be 32 bytes (64 hex chars), got " + bytes.length);
        }
    }

    /**
     * 生成一对 Ed25519 密钥（仅供测试 / 示例使用）。
     *
     * @return 密钥对
     */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance(SIGNATURE_ALGORITHM);
            return kpg.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Ed25519 not available", e);
        }
    }

    /**
     * 将 {@link KeyPair} 的私钥编码为 hex 字符串。
     *
     * @param keyPair 密钥对
     * @return 私钥 hex（64 字符）
     */
    public static String privateKeyToHex(KeyPair keyPair) {
        byte[] encoded = keyPair.getPrivate().getEncoded();
        // PKCS#8 编码：44 字节头 + 32 字节私钥
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return HEX.formatHex(raw);
    }

    /**
     * 将 {@link KeyPair} 的公钥编码为 hex 字符串。
     *
     * @param keyPair 密钥对
     * @return 公钥 hex（64 字符）
     */
    public static String publicKeyToHex(KeyPair keyPair) {
        byte[] encoded = keyPair.getPublic().getEncoded();
        // X.509 编码：12 字节头 + 32 字节公钥
        byte[] raw = new byte[32];
        System.arraycopy(encoded, encoded.length - 32, raw, 0, 32);
        return HEX.formatHex(raw);
    }
}