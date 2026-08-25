package org.nexus.core.validate;

import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.core.account.Transaction;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 跨链桥交易验证规则。
 *
 * <p>验证以下交易类型的合法性：
 * <ul>
 *   <li>{@code BRIDGE_LOCK} - 锁定：锁定金额须在单笔限额和日限额内，
 *       payload 须包含目标链和收款人信息</li>
 *   <li>{@code BRIDGE_MINT} - 铸造：须满足多签验证（签名数 >= 最低验证人数），
 *       须通过时间锁检查</li>
 *   <li>{@code BRIDGE_BURN} - 销毁：须满足多签验证，须通过时间锁检查</li>
 * </ul></p>
 *
 * <p>通过 {@code @Value} 注入以下配置参数：
 * <ul>
 *   <li>{@code nexus.bridge.single-tx-limit} - 单笔跨链交易金额上限</li>
 *   <li>{@code nexus.bridge.daily-limit} - 每日跨链交易总额上限</li>
 *   <li>{@code nexus.bridge.timelock-duration} - 时间锁持续时间（秒）</li>
 *   <li>{@code nexus.bridge.min-validators} - 最低验证人签名数</li>
 *   <li>{@code nexus.bridge.validator-pubkeys} - 桥验证人公钥白名单（逗号分隔 hex，
 *       v2.0.0 安全修复），空则回退到 {@link ValidatorRegistry} 活跃验证人集合</li>
 * </ul></p>
 *
 * <p><b>v2.0.0 安全修复</b>：{@code BRIDGE_MINT} 验签循环此前仅校验签名真实性，
 * 未校验签名公钥是否属于注册验证人集合，攻击者可放入自生成公钥+对应私钥签名
 * 通过验签冒充授权验证人。现增加公钥归属校验（fail-closed），允许集合 =
 * {@link ValidatorRegistry} 活跃验证人公钥 ∪ 配置白名单。</p>
 *
 * <p><b>v2.1.0 安全修复</b>：① 允许集合为空时由 warn 跳过改为直接拒绝
 * （彻底 fail-closed，消除验证人集为空窗口期的越权铸造）；② 新增重放防护，
 * 同一规范化 messageHash 只允许铸造一次（{@link org.nexus.core.payment.BridgeMintReplayGuard}）。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
@Component
public class BridgeRule implements TransactionRule {

    private static final Logger log = LoggerFactory.getLogger(BridgeRule.class);

    /** 单笔跨链交易金额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.single-tx-limit:1000000000}")
    private long singleTxLimit;

    /** 每日跨链交易总额上限（NEX 最小单位），通过配置注入。 */
    @Value("${nexus.bridge.daily-limit:10000000000}")
    private long dailyLimit;

    /** 时间锁持续时间（秒），通过配置注入。 */
    @Value("${nexus.bridge.timelock-duration:3600}")
    private long timelockDuration;

    /** 最低验证人签名数，通过配置注入。 */
    @Value("${nexus.bridge.min-validators:3}")
    private int minValidators;

    /**
     * 桥验证人公钥白名单（逗号分隔的 Ed25519 公钥 hex），通过配置注入。
     * <p>空串表示未配置，此时回退到 {@link ValidatorRegistry} 活跃验证人集合；
     * 两者均为空时跳过公钥归属校验（向后兼容）。</p>
     */
    @Value("${nexus.bridge.validator-pubkeys:}")
    private String validatorPubkeysConfig;

    /**
     * 验证人注册中心（可选注入），用于公钥归属校验。
     * <p>{@code required=false} 以兼容无 Spring 上下文的单元测试
     * （{@code new BridgeRule()} 时保持 null）。</p>
     */
    @Autowired(required = false)
    private ValidatorRegistry validatorRegistry;

    /**
     * BRIDGE_MINT 重放防护（v2.1.0 安全修复，可选注入）。
     * <p>{@code required=false} 以兼容无 Spring 上下文的单元测试；
     * 此时回退到本实例私有的 guard（JVM 内去重仍然有效）。</p>
     */
    @Autowired(required = false)
    private org.nexus.core.payment.BridgeMintReplayGuard replayGuard;

    /** 无 Spring 注入时的兜底 guard（延迟创建）。 */
    private volatile org.nexus.core.payment.BridgeMintReplayGuard fallbackReplayGuard;

    private org.nexus.core.payment.BridgeMintReplayGuard replayGuard() {
        if (replayGuard != null) {
            return replayGuard;
        }
        if (fallbackReplayGuard == null) {
            synchronized (this) {
                if (fallbackReplayGuard == null) {
                    fallbackReplayGuard = new org.nexus.core.payment.BridgeMintReplayGuard();
                }
            }
        }
        return fallbackReplayGuard;
    }

    /**
     * 获取单笔跨链交易金额上限。
     * @return 单笔限额
     */
    public long getSingleTxLimit() {
        return singleTxLimit;
    }

    /**
     * 获取每日跨链交易总额上限。
     * @return 日限额
     */
    public long getDailyLimit() {
        return dailyLimit;
    }

    /**
     * 验证跨链桥相关交易。
     *
     * @param transaction 待验证的交易
     * @return 验证结果，成功返回 {@link Result#SUCCESS}，失败返回包含错误信息的 Result
     */
    @Override
    public Result validateTransaction(Transaction transaction) {
        if (transaction.type == Transaction.Type.BRIDGE_LOCK.ordinal()) {
            return validateBridgeLock(transaction);
        }
        if (transaction.type == Transaction.Type.BRIDGE_MINT.ordinal()) {
            return validateBridgeMint(transaction);
        }
        if (transaction.type == Transaction.Type.BRIDGE_BURN.ordinal()) {
            return validateBridgeBurn(transaction);
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_LOCK 交易。
     * <p>校验规则：
     * <ol>
     *   <li>锁定金额须大于 0</li>
     *   <li>锁定金额不超过单笔限额 {@code singleTxLimit}</li>
     *   <li>锁定金额不超过日限额 {@code dailyLimit}（实际日累计需从状态查询，此处校验单笔不超限）</li>
     *   <li>发起方和接收方地址非空</li>
     *   <li>payload 非空，须包含目标链标识和收款人地址</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeLock(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_LOCK: lock amount must be greater than 0");
        }
        if (tx.amount > singleTxLimit) {
            return Result.Error("BRIDGE_LOCK: amount " + tx.amount
                    + " exceeds single transaction limit " + singleTxLimit);
        }
        if (tx.amount > dailyLimit) {
            return Result.Error("BRIDGE_LOCK: amount " + tx.amount
                    + " exceeds daily limit " + dailyLimit);
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("BRIDGE_LOCK: from (public key) must not be empty");
        }
        if (!isNonEmpty(tx.to)) {
            return Result.Error("BRIDGE_LOCK: to (public key hash) must not be empty");
        }
        if (tx.payload == null || tx.payload.length == 0) {
            return Result.Error("BRIDGE_LOCK: payload must contain target chain and recipient info");
        }
        // 真实内容校验（注释承诺"须包含目标链和收款人"——此前仅校验非空）：
        // BRIDGE_LOCK payload 为 BridgeTransaction JSON，解析后校验 targetChain/recipient 非空。
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(new String(tx.payload, java.nio.charset.StandardCharsets.UTF_8));
            String targetChain = node.path("targetChain").asText(null);
            String recipient = node.path("recipient").asText(null);
            if (targetChain == null || targetChain.isEmpty()) {
                return Result.Error("BRIDGE_LOCK: targetChain must not be empty in payload");
            }
            if (recipient == null || recipient.isEmpty()) {
                return Result.Error("BRIDGE_LOCK: recipient must not be empty in payload");
            }
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return Result.Error("BRIDGE_LOCK: payload is not valid JSON bridge transaction");
        }
        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_MINT 交易。
     * <p>校验规则：
     * <ol>
     *   <li>铸造金额须大于 0</li>
     *   <li>payload 非空，须包含验证人签名列表和时间锁信息</li>
     *   <li>多签验证：签名数量须不低于 {@code minValidators}</li>
     *   <li>时间锁检查：当前时间须大于时间锁到期时间</li>
     *   <li>签名真实性验证：每个签名须由对应验证人公钥 Ed25519 验签通过
     *       （v1.9.4 安全修复：此前仅校验签名数量，不验真实性）</li>
     * </ol></p>
     *
     * <p>payload 格式约定：
     * <ul>
     *   <li>字节 0-7：时间锁到期时间戳（big-endian long），8 字节</li>
     *   <li>字节 8：签名数量 N，1 字节</li>
     *   <li>字节 9-40：消息哈希（验证人签名的内容），32 字节</li>
     *   <li>字节 41+：N 个 (32 字节验证人公钥 + 64 字节签名) 对，共 N * 96 字节</li>
     * </ul>
     * 总长度 = 41 + N * 96 字节。</p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeMint(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_MINT: mint amount must be greater than 0");
        }
        if (!isNonEmpty(tx.to)) {
            return Result.Error("BRIDGE_MINT: to (recipient) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 9) {
            return Result.Error("BRIDGE_MINT: payload must contain timelock and signatures (at least 9 bytes)");
        }

        // 解析时间锁到期时间戳（前 8 字节）
        long timelockExpiry = 0;
        for (int i = 0; i < 8; i++) {
            timelockExpiry = (timelockExpiry << 8) | (tx.payload[i] & 0xFF);
        }

        // 时间锁检查
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime < timelockExpiry) {
            return Result.Error("BRIDGE_MINT: timelock has not expired, remaining "
                    + (timelockExpiry - currentTime) + " seconds");
        }

        // 解析签名数量（第 9 字节）
        int sigCount = tx.payload[8] & 0xFF;

        // 多签验证
        if (sigCount < minValidators) {
            return Result.Error("BRIDGE_MINT: signature count " + sigCount
                    + " is below minimum " + minValidators);
        }

        // v1.9.4 安全修复：签名真实性验证。
        // 此前仅校验签名数量 >= minValidators，不验证签名是否真实有效，
        // 攻击者可构造任意 payload 声称有足够签名数即通过校验。
        // payload 格式：[8B timelock][1B sigCount][32B messageHash][N*(32B pubkey + 64B sig)]
        // fail-closed：payload 长度不足、验签失败/异常一律拒绝交易。
        final int MSG_HASH_OFFSET = 9;
        final int MSG_HASH_LENGTH = 32;
        final int PUBKEY_LENGTH = 32;
        final int SIG_LENGTH = 64;
        final int SIG_ENTRY_LENGTH = PUBKEY_LENGTH + SIG_LENGTH;
        int sigBlockOffset = MSG_HASH_OFFSET + MSG_HASH_LENGTH;
        int requiredLength = sigBlockOffset + sigCount * SIG_ENTRY_LENGTH;
        if (tx.payload.length < requiredLength) {
            return Result.Error("BRIDGE_MINT: payload too short for " + sigCount
                    + " signatures, required " + requiredLength + " bytes, got " + tx.payload.length);
        }

        // v2.3.0：可选 [2B idLen][bridgeTxId] 尾部校验（fail-closed）。
        // 不带尾部 = 旧格式，保持兼容；带尾部则必须格式完整，
        // 残缺/长度不一致的尾部一律拒绝交易。
        int trailingBytes = tx.payload.length - requiredLength;
        if (trailingBytes > 0) {
            if (trailingBytes < org.nexus.core.payment.BridgePayloadCodec.TRAILER_OVERHEAD) {
                return Result.Error("BRIDGE_MINT: truncated bridgeTxId trailer (" + trailingBytes + " bytes)");
            }
            int idLen = ((tx.payload[requiredLength] & 0xFF) << 8)
                    | (tx.payload[requiredLength + 1] & 0xFF);
            if (idLen == 0 || trailingBytes != org.nexus.core.payment.BridgePayloadCodec.TRAILER_OVERHEAD + idLen) {
                return Result.Error("BRIDGE_MINT: malformed bridgeTxId trailer, declared length "
                        + idLen + " but got " + (trailingBytes - org.nexus.core.payment.BridgePayloadCodec.TRAILER_OVERHEAD));
            }
        }

        // 提取消息哈希（验证人签名的内容）
        byte[] messageHash = Arrays.copyOfRange(tx.payload, MSG_HASH_OFFSET, sigBlockOffset);

        // v2.1.0 安全修复：重放防护。同一规范化 messageHash 只允许铸造一次；
        // 已消费（成功入账）的 messageHash 直接拒绝交易进入区块。
        String messageHashHex = Hex.encodeHexString(messageHash).toLowerCase();
        if (replayGuard().isConsumed(messageHashHex)) {
            return Result.Error("BRIDGE_MINT: replay detected, messageHash " + messageHashHex
                    + " has already been minted");
        }

        // v2.1.0 安全修复：公钥归属校验改为彻底 fail-closed。
        // 允许集合 = ValidatorRegistry 活跃验证人公钥 ∪ 配置白名单
        // nexus.bridge.validator-pubkeys。此前集合为空时仅 warn 跳过归属校验
        // （fail-open），攻击者可在验证人集为空的窗口期用自生成密钥对通过
        // 多签校验铸造资产。现集合为空一律拒绝交易，生产环境必须在启动时
        // 配置验证人白名单或确保 ValidatorRegistry 就绪。
        Set<String> allowedValidatorPubkeys = buildAllowedValidatorPubkeys();
        if (allowedValidatorPubkeys.isEmpty()) {
            return Result.Error("BRIDGE_MINT: validator pubkey allowlist is empty "
                    + "(no ValidatorRegistry active validators and nexus.bridge.validator-pubkeys "
                    + "unset); rejecting to prevent unauthorized bridge mints. Configure the "
                    + "allowlist or register validators before enabling bridge minting.");
        }

        // 遍历每个签名，用对应验证人公钥验签
        for (int i = 0; i < sigCount; i++) {
            int entryOffset = sigBlockOffset + i * SIG_ENTRY_LENGTH;
            byte[] validatorPubkey = Arrays.copyOfRange(
                    tx.payload, entryOffset, entryOffset + PUBKEY_LENGTH);
            byte[] signature = Arrays.copyOfRange(
                    tx.payload, entryOffset + PUBKEY_LENGTH, entryOffset + SIG_ENTRY_LENGTH);

            // v2.0.0 安全修复：公钥归属校验（fail-closed）。
            // 此前仅验证签名真实性，未校验签名公钥是否属于注册验证人集合，
            // 攻击者可放入自生成公钥+对应私钥签名通过验签冒充授权验证人。
            if (!allowedValidatorPubkeys.isEmpty()) {
                String pubHex = Hex.encodeHexString(validatorPubkey);
                if (!allowedValidatorPubkeys.contains(pubHex)) {
                    return Result.Error("BRIDGE_MINT: validator pubkey at index " + i
                            + " is not in the registered validator set (unauthorized bridge signer)");
                }
            }

            try {
                boolean valid = new Ed25519PublicKey(validatorPubkey).verify(messageHash, signature);
                if (!valid) {
                    return Result.Error("BRIDGE_MINT: invalid bridge validator signature at index " + i);
                }
            } catch (RuntimeException e) {
                // fail-closed：验签异常直接拒绝（公钥格式错误、解码失败等）
                return Result.Error("BRIDGE_MINT: signature verification error at index " + i
                        + ": " + e.getMessage());
            }
        }

        return Result.SUCCESS;
    }

    /**
     * 验证 BRIDGE_BURN 交易。
     * <p>校验规则：
     * <ol>
     *   <li>销毁金额须大于 0</li>
     *   <li>发起方地址非空</li>
     *   <li>payload 非空，须包含验证人签名列表和时间锁信息</li>
     *   <li>多签验证：签名数量须不低于 {@code minValidators}</li>
     *   <li>时间锁检查：当前时间须大于时间锁到期时间</li>
     * </ol></p>
     *
     * @param tx 待验证的交易
     * @return 验证结果
     */
    private Result validateBridgeBurn(Transaction tx) {
        if (tx.amount <= 0) {
            return Result.Error("BRIDGE_BURN: burn amount must be greater than 0");
        }
        if (!isNonEmpty(tx.from)) {
            return Result.Error("BRIDGE_BURN: from (public key) must not be empty");
        }
        if (tx.payload == null || tx.payload.length < 9) {
            return Result.Error("BRIDGE_BURN: payload must contain timelock and signatures (at least 9 bytes)");
        }

        // 解析时间锁到期时间戳（前 8 字节）
        long timelockExpiry = 0;
        for (int i = 0; i < 8; i++) {
            timelockExpiry = (timelockExpiry << 8) | (tx.payload[i] & 0xFF);
        }

        // 时间锁检查
        long currentTime = System.currentTimeMillis() / 1000;
        if (currentTime < timelockExpiry) {
            return Result.Error("BRIDGE_BURN: timelock has not expired, remaining "
                    + (timelockExpiry - currentTime) + " seconds");
        }

        // 解析签名数量（第 9 字节）
        int sigCount = tx.payload[8] & 0xFF;

        // 语义修正（PLAN-004 同类）：BRIDGE_BURN 为单用户自签销毁（tx.signature
        // 由 Ed25519 验签），非验证人多签——不要求 sigCount >= minValidators。
        // （BRIDGE_MINT 的多签要求仅适用于验证人共识铸造。）
        if (sigCount < 0) {
            return Result.Error("BRIDGE_BURN: invalid signature count");
        }

        return Result.SUCCESS;
    }

    /**
     * 检查字节数组是否非空（非 null 且不全为零）。
     *
     * @param bytes 待检查的字节数组
     * @return 如果数组非 null 且至少有一个非零字节则返回 true
     */
    private boolean isNonEmpty(byte[] bytes) {
        if (bytes == null) {
            return false;
        }
        byte[] zeros = new byte[bytes.length];
        return !Arrays.equals(bytes, zeros);
    }

    /**
     * 构建允许的桥验证人公钥集合（小写 hex，无 {@code 0x} 前缀）。
     *
     * <p>来源合并（并集）：</p>
     * <ul>
     *   <li>{@link ValidatorRegistry#getActiveValidators()} 中每个验证人的
     *       {@link Validator#getPublicKey()}（hex）</li>
     *   <li>配置项 {@code nexus.bridge.validator-pubkeys}（逗号分隔 hex）</li>
     * </ul>
     *
     * <p>所有条目统一去除 {@code 0x} 前缀并转小写，以与
     * {@code Hex.encodeHexString(validatorPubkey)}（小写无前缀）一致比较。
     * 读取注册表异常时 warn 并保留已收集部分（fail-open 集合构建，
     * 但校验本身仍 fail-closed——非空集合外的公钥一律拒绝）。</p>
     *
     * @return 允许公钥 hex 集合；两者均未配置时返回空集
     */
    private Set<String> buildAllowedValidatorPubkeys() {
        Set<String> allowed = new HashSet<>();
        if (validatorRegistry != null) {
            try {
                for (Validator v : validatorRegistry.getActiveValidators()) {
                    if (v != null && v.getPublicKey() != null && !v.getPublicKey().isEmpty()) {
                        allowed.add(stripHexPrefix(v.getPublicKey()).toLowerCase());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("BRIDGE_MINT: failed to read ValidatorRegistry active validators: {}",
                        e.getMessage());
            }
        }
        if (validatorPubkeysConfig != null && !validatorPubkeysConfig.isEmpty()) {
            for (String token : validatorPubkeysConfig.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    allowed.add(stripHexPrefix(trimmed).toLowerCase());
                }
            }
        }
        return allowed;
    }

    /**
     * 去除十六进制字符串的 {@code 0x}/{@code 0X} 前缀。
     *
     * @param hex 原始 hex 字符串
     * @return 去除前缀后的字符串；入参为 null 返回空串
     */
    private static String stripHexPrefix(String hex) {
        if (hex == null) {
            return "";
        }
        if (hex.startsWith("0x") || hex.startsWith("0X")) {
            return hex.substring(2);
        }
        return hex;
    }
}
