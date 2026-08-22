package org.nexus.core.payment;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.util.encoders.Hex;
import org.nexus.crypto.ed25519.Ed25519PublicKey;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 跨链桥交易模型类。
 *
 * <p>表示 NEX 跨链桥系统中的一条跨链交易记录。跨链桥支持将 NEX 代币
 * 从源链锁定并在目标链上铸造，或反向销毁并解锁。</p>
 *
 * <p>跨链流程：
 * <ol>
 *   <li>{@link State#PENDING} - 交易已创建，等待锁定</li>
 *   <li>{@link State#LOCKED} - 源链资产已锁定</li>
 *   <li>{@link State#VALIDATING} - 验证人签名已提交，正在验证</li>
 *   <li>{@link State#MINTED} - 目标链已铸造对应资产</li>
 *   <li>{@link State#BURNED} - 目标链已销毁对应资产</li>
 *   <li>{@link State#UNLOCKED} - 源链已解锁原始资产</li>
 *   <li>{@link State#FAILED} - 交易失败</li>
 *   <li>{@link State#EXPIRED} - 交易超时过期</li>
 * </ol>
 * </p>
 *
 * <p>签名验证使用 Ed25519 算法，验证人公钥和签名均以十六进制字符串形式存储。
 * 只有当签名数量达到 {@code validatorThreshold} 且所有签名验证通过时，
 * 才能执行铸造操作。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class BridgeTransaction {

    /**
     * 桥交易状态枚举。
     */
    public enum State {
        /** 交易已创建，等待锁定源链资产。 */
        PENDING,
        /** 源链资产已锁定。 */
        LOCKED,
        /** 验证人签名已提交，正在验证。 */
        VALIDATING,
        /** 目标链已铸造对应资产。 */
        MINTED,
        /** 目标链已销毁对应资产。 */
        BURNED,
        /** 源链已解锁原始资产，交易完成。 */
        UNLOCKED,
        /** 交易失败。 */
        FAILED,
        /** 交易超时过期。 */
        EXPIRED
    }

    /** JSON 序列化/反序列化器。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 桥交易唯一标识符。 */
    private String bridgeTxId;

    /** 源链标识。 */
    private String sourceChain;

    /** 目标链标识。 */
    private String targetChain;

    /** 跨链转账金额（单位：NEX 最小单位）。 */
    private long amount;

    /** 目标链收款人地址。 */
    private String recipient;

    /** 参与签名的验证人公钥列表（十六进制字符串）。 */
    private List<String> validators;

    /** 验证人签名列表（十六进制字符串，与 validators 一一对应）。 */
    private List<String> signatures;

    /** 交易当前状态。 */
    private State state;

    /** 交易创建时间戳（秒）。 */
    private long timestamp;

    /** 时间锁到期时间戳（秒），到期后可执行解锁操作。 */
    private long timelockExpiry;

    /** 验证人签名数量阈值，达到此数量方可铸造。 */
    private int validatorThreshold;

    /** 交易失败原因（仅在 FAILED 状态下有值）。 */
    private String failReason;

    /** 标记签名是否已通过验证（运行时状态，不参与序列化）。 */
    @JsonIgnore
    private boolean signaturesVerified;

    /**
     * 默认构造函数，供 JSON 反序列化使用。
     */
    public BridgeTransaction() {
        this.validators = new ArrayList<>();
        this.signatures = new ArrayList<>();
        this.state = State.PENDING;
    }

    /**
     * 创建新桥交易构造函数。
     *
     * @param bridgeTxId        桥交易唯一标识符
     * @param sourceChain       源链标识
     * @param targetChain       目标链标识
     * @param amount             跨链转账金额
     * @param recipient          目标链收款人地址
     * @param validatorThreshold 验证人签名数量阈值
     * @param timelockExpiry     时间锁到期时间戳（秒）
     */
    public BridgeTransaction(String bridgeTxId, String sourceChain, String targetChain,
                             long amount, String recipient, int validatorThreshold,
                             long timelockExpiry) {
        this.bridgeTxId = bridgeTxId;
        this.sourceChain = sourceChain;
        this.targetChain = targetChain;
        this.amount = amount;
        this.recipient = recipient;
        this.validatorThreshold = validatorThreshold;
        this.timelockExpiry = timelockExpiry;
        this.validators = new ArrayList<>();
        this.signatures = new ArrayList<>();
        this.state = State.PENDING;
    }

    /**
     * 全参数构造函数。
     *
     * @param bridgeTxId        桥交易唯一标识符
     * @param sourceChain        源链标识
     * @param targetChain        目标链标识
     * @param amount             跨链转账金额
     * @param recipient          目标链收款人地址
     * @param validators         验证人公钥列表
     * @param signatures         验证人签名列表
     * @param state              交易状态
     * @param timestamp          创建时间戳
     * @param timelockExpiry     时间锁到期时间戳
     * @param validatorThreshold 验证人签名数量阈值
     */
    public BridgeTransaction(String bridgeTxId, String sourceChain, String targetChain,
                             long amount, String recipient, List<String> validators,
                             List<String> signatures, State state, long timestamp,
                             long timelockExpiry, int validatorThreshold) {
        this.bridgeTxId = bridgeTxId;
        this.sourceChain = sourceChain;
        this.targetChain = targetChain;
        this.amount = amount;
        this.recipient = recipient;
        this.validators = validators != null ? new ArrayList<>(validators) : new ArrayList<>();
        this.signatures = signatures != null ? new ArrayList<>(signatures) : new ArrayList<>();
        this.state = state;
        this.timestamp = timestamp;
        this.timelockExpiry = timelockExpiry;
        this.validatorThreshold = validatorThreshold;
    }

    // ==================== 核心业务方法 ====================

    /**
     * 锁定源链资产。
     *
     * <p>将交易状态从 {@link State#PENDING} 转为 {@link State#LOCKED}，
     * 并记录当前时间戳。</p>
     *
     * @throws IllegalStateException 如果当前状态不是 PENDING
     */
    public void lock() {
        if (state != State.PENDING) {
            throw new IllegalStateException(
                    "Can only lock from PENDING state, current: " + state);
        }
        this.timestamp = System.currentTimeMillis() / 1000;
        this.state = State.LOCKED;
    }

    /**
     * 提交验证人签名。
     *
     * <p>将交易状态从 {@link State#LOCKED} 转为 {@link State#VALIDATING}。
     * 验证签名数量是否达到 {@code validatorThreshold}，并将签名和对应公钥
     * 存入交易记录。</p>
     *
     * @param sigs    签名列表（十六进制字符串）
     * @param pubkeys 对应的验证人公钥列表（十六进制字符串）
     * @throws IllegalStateException    如果当前状态不是 LOCKED
     * @throws IllegalArgumentException 如果签名和公钥列表为空或长度不匹配
     * @throws IllegalStateException    如果签名数量未达到阈值
     */
    public void submitValidatorSignatures(List<String> sigs, List<String> pubkeys) {
        if (state != State.LOCKED) {
            throw new IllegalStateException(
                    "Can only submit signatures from LOCKED state, current: " + state);
        }
        if (sigs == null || pubkeys == null) {
            throw new IllegalArgumentException("Signatures and pubkeys must not be null");
        }
        if (sigs.size() != pubkeys.size()) {
            throw new IllegalArgumentException(
                    "Signatures count " + sigs.size() + " does not match pubkeys count " + pubkeys.size());
        }
        if (sigs.isEmpty()) {
            throw new IllegalArgumentException("Signatures list must not be empty");
        }

        this.signatures = new ArrayList<>(sigs);
        this.validators = new ArrayList<>(pubkeys);

        if (!hasEnoughSignatures()) {
            throw new IllegalStateException(
                    "Not enough signatures: " + signatures.size() + " < threshold " + validatorThreshold);
        }

        this.state = State.VALIDATING;
    }

    /**
     * 验证所有签名对消息的有效性。
     *
     * <p>使用 Ed25519 算法验证每个验证人公钥对消息的签名。只有所有签名
     * 均验证通过时才返回 true。验证结果会被缓存到 {@code signaturesVerified}
     * 字段，供后续 {@link #mint()} 方法检查。</p>
     *
     * @param message 待验证的消息字节数组
     * @return 如果所有签名验证通过则返回 true，否则返回 false
     */
    public boolean verifySignatures(byte[] message) {
        if (signatures == null || validators == null || signatures.size() != validators.size()) {
            this.signaturesVerified = false;
            return false;
        }
        if (signatures.isEmpty()) {
            this.signaturesVerified = false;
            return false;
        }
        if (message == null) {
            this.signaturesVerified = false;
            return false;
        }

        for (int i = 0; i < signatures.size(); i++) {
            try {
                byte[] sigBytes = Hex.decode(signatures.get(i));
                byte[] pubKeyBytes = Hex.decode(validators.get(i));
                Ed25519PublicKey publicKey = new Ed25519PublicKey(pubKeyBytes);
                if (!publicKey.verify(message, sigBytes)) {
                    this.signaturesVerified = false;
                    return false;
                }
            } catch (RuntimeException e) {
                this.signaturesVerified = false;
                return false;
            }
        }

        this.signaturesVerified = true;
        return true;
    }

    /**
     * 在目标链上铸造对应资产。
     *
     * <p>将交易状态从 {@link State#VALIDATING} 转为 {@link State#MINTED}。
     * 要求所有签名已通过验证（需先调用 {@link #verifySignatures(byte[])}）。</p>
     *
     * @throws IllegalStateException 如果当前状态不是 VALIDATING
     * @throws IllegalStateException 如果签名尚未验证或未通过
     * @throws IllegalStateException 如果签名数量未达到阈值
     */
    public void mint() {
        if (state != State.VALIDATING) {
            throw new IllegalStateException(
                    "Can only mint from VALIDATING state, current: " + state);
        }
        if (!signaturesVerified) {
            throw new IllegalStateException(
                    "Signatures have not been verified. Call verifySignatures() first.");
        }
        if (!hasEnoughSignatures()) {
            throw new IllegalStateException(
                    "Not enough signatures: " + signatures.size() + " < threshold " + validatorThreshold);
        }
        this.state = State.MINTED;
    }

    /**
     * 在目标链上销毁对应资产。
     *
     * <p>将交易状态从 {@link State#MINTED} 转为 {@link State#BURNED}。</p>
     *
     * @throws IllegalStateException 如果当前状态不是 MINTED
     */
    public void burn() {
        if (state != State.MINTED) {
            throw new IllegalStateException(
                    "Can only burn from MINTED state, current: " + state);
        }
        this.state = State.BURNED;
    }

    /**
     * 解锁源链原始资产。
     *
     * <p>将交易状态从 {@link State#BURNED} 转为 {@link State#UNLOCKED}。
     * 要求时间锁已到期（当前时间 >= timelockExpiry）。</p>
     *
     * @throws IllegalStateException 如果当前状态不是 BURNED
     * @throws IllegalStateException 如果时间锁尚未到期
     */
    public void unlock() {
        if (state != State.BURNED) {
            throw new IllegalStateException(
                    "Can only unlock from BURNED state, current: " + state);
        }
        long currentTime = System.currentTimeMillis() / 1000;
        if (!isTimelockExpired(currentTime)) {
            throw new IllegalStateException(
                    "Timelock not expired: current=" + currentTime + ", expiry=" + timelockExpiry);
        }
        this.state = State.UNLOCKED;
    }

    /**
     * 标记交易失败。
     *
     * <p>将交易状态置为 {@link State#FAILED}，可从任意状态调用。</p>
     *
     * @param reason 失败原因描述
     */
    public void fail(String reason) {
        this.failReason = reason;
        this.state = State.FAILED;
    }

    /**
     * 标记交易过期。
     *
     * <p>将交易状态从 {@link State#PENDING} 转为 {@link State#EXPIRED}。
     * 调用方需确保交易确实已超过超时时间。</p>
     *
     * @throws IllegalStateException 如果当前状态不是 PENDING
     */
    public void expire() {
        if (state != State.PENDING) {
            throw new IllegalStateException(
                    "Can only expire from PENDING state, current: " + state);
        }
        this.state = State.EXPIRED;
    }

    // ==================== 验证方法 ====================

    /**
     * 检查签名数量是否达到阈值。
     *
     * @return 如果签名数量 >= validatorThreshold 则返回 true
     */
    public boolean hasEnoughSignatures() {
        return signatures != null && signatures.size() >= validatorThreshold;
    }

    /**
     * 检查时间锁是否已到期。
     *
     * @param currentBlockHeight 当前区块高度或时间戳
     * @return 如果 currentBlockHeight >= timelockExpiry 则返回 true
     */
    public boolean isTimelockExpired(long currentBlockHeight) {
        return currentBlockHeight >= timelockExpiry;
    }

    /**
     * 检查指定公钥是否为参与签名的验证人。
     *
     * @param pubkey 验证人公钥（十六进制字符串）
     * @return 如果该公钥在 validators 列表中则返回 true
     */
    public boolean isValidator(String pubkey) {
        return validators != null && validators.contains(pubkey);
    }

    /**
     * 检查是否满足铸造条件。
     *
     * <p>铸造条件：状态为 {@link State#VALIDATING} 且签名数量达到阈值。</p>
     *
     * @return 如果满足铸造条件则返回 true
     */
    public boolean canMint() {
        return state == State.VALIDATING && hasEnoughSignatures();
    }

    /**
     * 检查是否满足解锁条件。
     *
     * <p>解锁条件：状态为 {@link State#BURNED} 且时间锁已到期。</p>
     *
     * @param currentBlockHeight 当前区块高度或时间戳
     * @return 如果满足解锁条件则返回 true
     */
    public boolean canUnlock(long currentBlockHeight) {
        return state == State.BURNED && isTimelockExpired(currentBlockHeight);
    }

    // ==================== 序列化方法 ====================

    /**
     * 将桥交易序列化为 JSON 字符串。
     *
     * <p>输出使用 UTF-8 编码的 JSON 格式，包含所有持久化字段
     * （{@code signaturesVerified} 为运行时状态，不参与序列化）。</p>
     *
     * @return JSON 字符串
     * @throws UncheckedIOException 如果序列化失败
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(new IOException("Failed to serialize BridgeTransaction", e));
        }
    }

    /**
     * 从 JSON 字符串反序列化桥交易。
     *
     * <p>输入应为 UTF-8 编码的 JSON 字符串。</p>
     *
     * @param json JSON 字符串
     * @return 反序列化的桥交易对象
     * @throws UncheckedIOException 如果反序列化失败
     */
    public static BridgeTransaction fromJson(String json) {
        try {
            return MAPPER.readValue(json.getBytes(StandardCharsets.UTF_8), BridgeTransaction.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to deserialize BridgeTransaction", e);
        }
    }

    // ==================== Getter / Setter ====================

    /**
     * 获取桥交易唯一标识符。
     * @return 桥交易 ID
     */
    public String getBridgeTxId() {
        return bridgeTxId;
    }

    /**
     * 设置桥交易唯一标识符。
     * @param bridgeTxId 桥交易 ID
     */
    public void setBridgeTxId(String bridgeTxId) {
        this.bridgeTxId = bridgeTxId;
    }

    /**
     * 获取源链标识。
     * @return 源链标识
     */
    public String getSourceChain() {
        return sourceChain;
    }

    /**
     * 设置源链标识。
     * @param sourceChain 源链标识
     */
    public void setSourceChain(String sourceChain) {
        this.sourceChain = sourceChain;
    }

    /**
     * 获取目标链标识。
     * @return 目标链标识
     */
    public String getTargetChain() {
        return targetChain;
    }

    /**
     * 设置目标链标识。
     * @param targetChain 目标链标识
     */
    public void setTargetChain(String targetChain) {
        this.targetChain = targetChain;
    }

    /**
     * 获取跨链转账金额。
     * @return 转账金额
     */
    public long getAmount() {
        return amount;
    }

    /**
     * 设置跨链转账金额。
     * @param amount 转账金额
     */
    public void setAmount(long amount) {
        this.amount = amount;
    }

    /**
     * 获取目标链收款人地址。
     * @return 收款人地址
     */
    public String getRecipient() {
        return recipient;
    }

    /**
     * 设置目标链收款人地址。
     * @param recipient 收款人地址
     */
    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    /**
     * 获取验证人公钥列表。
     * @return 验证人公钥列表（不可修改）
     */
    public List<String> getValidators() {
        return validators != null ? Collections.unmodifiableList(validators) : null;
    }

    /**
     * 设置验证人公钥列表。
     * @param validators 验证人公钥列表
     */
    public void setValidators(List<String> validators) {
        this.validators = validators != null ? new ArrayList<>(validators) : null;
    }

    /**
     * 获取验证人签名列表。
     * @return 签名列表（不可修改）
     */
    public List<String> getSignatures() {
        return signatures != null ? Collections.unmodifiableList(signatures) : null;
    }

    /**
     * 设置验证人签名列表。
     * @param signatures 签名列表
     */
    public void setSignatures(List<String> signatures) {
        this.signatures = signatures != null ? new ArrayList<>(signatures) : null;
    }

    /**
     * 获取交易当前状态。
     * @return 交易状态
     */
    public State getState() {
        return state;
    }

    /**
     * 设置交易当前状态。
     * @param state 交易状态
     */
    public void setState(State state) {
        this.state = state;
    }

    /**
     * 获取交易创建时间戳。
     * @return 时间戳（秒）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置交易创建时间戳。
     * @param timestamp 时间戳（秒）
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    /**
     * 获取时间锁到期时间戳。
     * @return 时间锁到期时间戳（秒）
     */
    public long getTimelockExpiry() {
        return timelockExpiry;
    }

    /**
     * 设置时间锁到期时间戳。
     * @param timelockExpiry 时间锁到期时间戳（秒）
     */
    public void setTimelockExpiry(long timelockExpiry) {
        this.timelockExpiry = timelockExpiry;
    }

    /**
     * 获取验证人签名数量阈值。
     * @return 签名数量阈值
     */
    public int getValidatorThreshold() {
        return validatorThreshold;
    }

    /**
     * 设置验证人签名数量阈值。
     * @param validatorThreshold 签名数量阈值
     */
    public void setValidatorThreshold(int validatorThreshold) {
        this.validatorThreshold = validatorThreshold;
    }

    /**
     * 获取交易失败原因。
     * @return 失败原因，如果未失败则返回 null
     */
    public String getFailReason() {
        return failReason;
    }

    /**
     * 设置交易失败原因。
     * @param failReason 失败原因
     */
    public void setFailReason(String failReason) {
        this.failReason = failReason;
    }

    @Override
    public String toString() {
        return "BridgeTransaction{" +
                "bridgeTxId='" + bridgeTxId + '\'' +
                ", sourceChain='" + sourceChain + '\'' +
                ", targetChain='" + targetChain + '\'' +
                ", amount=" + amount +
                ", recipient='" + recipient + '\'' +
                ", validators=" + validators +
                ", signatures=" + signatures +
                ", state=" + state +
                ", timestamp=" + timestamp +
                ", timelockExpiry=" + timelockExpiry +
                ", validatorThreshold=" + validatorThreshold +
                ", failReason='" + failReason + '\'' +
                '}';
    }
}
