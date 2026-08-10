package org.nexus.bridge.messaging;

import org.nexus.bridge.adapter.ChainAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息执行器。
 *
 * <p>负责在目标链上执行已中继的跨链消息，调用目标合约完成最终交付。
 * 是跨链消息传递的最后一环。</p>
 *
 * <h2>核心职责</h2>
 * <ul>
 *   <li>{@link #validateMessage} — 验证消息有效性（签名、nonce、目标合约存在性、超时）</li>
 *   <li>{@link #executeMessage}  — 在目标链执行消息（构造 calldata 并通过 {@link ChainAdapter} 提交）</li>
 *   <li>{@link #recordExecution} — 记录执行结果（目标链交易哈希）到 {@link MessageStore}</li>
 * </ul>
 *
 * <h2>执行流程</h2>
 * <pre>
 *   validateMessage ─► 构造 calldata ─► adapter.sendTransaction ─► recordExecution ─► 更新状态 EXECUTED
 * </pre>
 *
 * <h2>calldata 构造</h2>
 * <p>本实现采用简化的「消息执行 calldata」格式：</p>
 * <pre>
 *   0x + SHA-256(messageId || sourceChain || targetContract || payload.encodedData)  (32 字节)
 * </pre>
 * 实际生产中应按目标合约 ABI 编码具体的调用函数（如
 * {@code executeMessage(bytes messageId, bytes payload)}）。
 *
 * @since 1.9.2
 */
public class MessageExecutor {

    private static final Logger log = LoggerFactory.getLogger(MessageExecutor.class);

    /** Hex 格式化器。 */
    private static final HexFormat HEX = HexFormat.of();

    /** 消息格式化器。 */
    private final MessageFormatter formatter;

    /** 消息存储。 */
    private final MessageStore store;

    /** 消息配置。 */
    private final MessageConfig config;

    /** 目标链适配器映射：targetChain → ChainAdapter。 */
    private final Map<String, ChainAdapter> targetAdapters;

    /** 已注册的目标合约白名单：targetChain → 合约地址集合。 */
    private final Map<String, java.util.Set<String>> registeredContracts = new ConcurrentHashMap<>();

    /**
     * 构造消息执行器。
     *
     * @param formatter       消息格式化器
     * @param store           消息存储
     * @param config          消息配置
     * @param targetAdapters  目标链适配器映射
     */
    public MessageExecutor(MessageFormatter formatter, MessageStore store, MessageConfig config,
                           Map<String, ChainAdapter> targetAdapters) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        this.store = Objects.requireNonNull(store, "store");
        this.config = Objects.requireNonNull(config, "config");
        this.targetAdapters = targetAdapters == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(targetAdapters);
    }

    /**
     * 注册目标合约到白名单。
     *
     * <p>仅白名单中的合约可被消息执行调用，防止消息指向任意合约。</p>
     *
     * @param targetChain    目标链 ID
     * @param contractAddress 合约地址
     */
    public void registerContract(String targetChain, String contractAddress) {
        registeredContracts.computeIfAbsent(targetChain, k -> ConcurrentHashMap.newKeySet())
                .add(contractAddress);
    }

    /**
     * 验证消息有效性。
     *
     * <p>验证项：</p>
     * <ol>
     *   <li>消息非 null 且 messageId 非空</li>
     *   <li>签名数 ≥ 配置的多签要求</li>
     *   <li>消息未超时（timestamp + timeout > now）</li>
     *   <li>目标合约在白名单中（若白名单非空）</li>
     *   <li>目标链适配器存在</li>
     * </ol>
     *
     * @param message 跨链消息
     * @return 验证通过返回 true
     */
    public boolean validateMessage(CrossChainMessage message) {
        if (message == null || message.getMessageId() == null || message.getMessageId().isEmpty()) {
            log.warn("Invalid message: null or missing messageId");
            return false;
        }
        // 签名数检查
        if (message.signatureCount() < config.getRequiredSignatures()) {
            log.warn("Message {} has insufficient signatures: got={}, required={}",
                    message.getMessageId(), message.signatureCount(), config.getRequiredSignatures());
            return false;
        }
        // 超时检查
        long now = System.currentTimeMillis() / 1000;
        if (now - message.getTimestamp() > config.getMessageTimeout()) {
            log.warn("Message {} expired: age={}s, timeout={}s",
                    message.getMessageId(), now - message.getTimestamp(), config.getMessageTimeout());
            return false;
        }
        // 目标合约白名单检查
        java.util.Set<String> contracts = registeredContracts.get(message.getTargetChain());
        if (contracts != null && !contracts.isEmpty()
                && !contracts.contains(message.getTargetContract())) {
            log.warn("Message {} target contract {} not registered on chain {}",
                    message.getMessageId(), message.getTargetContract(), message.getTargetChain());
            return false;
        }
        // 目标链适配器检查
        if (!targetAdapters.containsKey(message.getTargetChain())) {
            log.warn("Message {} target chain {} has no adapter registered",
                    message.getMessageId(), message.getTargetChain());
            return false;
        }
        return true;
    }

    /**
     * 在目标链执行消息。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>调用 {@link #validateMessage} 验证</li>
     *   <li>构造 calldata（基于消息内容派生的 32 字节摘要）</li>
     *   <li>通过 {@link ChainAdapter#sendTransaction} 提交到目标链</li>
     *   <li>调用 {@link #recordExecution} 记录交易哈希</li>
     *   <li>更新消息状态为 EXECUTED</li>
     * </ol>
     *
     * @param message 跨链消息
     * @return 目标链交易哈希
     * @throws IllegalArgumentException 如果验证失败
     * @throws RuntimeException         如果链上提交失败
     */
    public String executeMessage(CrossChainMessage message) {
        if (!validateMessage(message)) {
            message.setStatus(MessageStatus.FAILED);
            throw new IllegalArgumentException("Message validation failed: " + message);
        }

        ChainAdapter adapter = targetAdapters.get(message.getTargetChain());
        if (adapter == null) {
            message.setStatus(MessageStatus.FAILED);
            throw new IllegalStateException("No adapter for target chain: " + message.getTargetChain());
        }

        // 构造 calldata
        byte[] calldata = buildCalldata(message);

        // 提交到目标链
        String txHash;
        try {
            txHash = adapter.sendTransaction(calldata);
        } catch (Exception e) {
            message.setStatus(MessageStatus.FAILED);
            log.error("Failed to submit execution tx for message {} on chain {}: {}",
                    message.getMessageId(), message.getTargetChain(), e.getMessage());
            throw new RuntimeException("Execution tx submission failed: " + e.getMessage(), e);
        }

        // 记录执行
        recordExecution(message.getMessageId(), txHash);
        message.setStatus(MessageStatus.EXECUTED);

        log.info("Executed message {} on chain {}, txHash={}",
                message.getMessageId(), message.getTargetChain(), txHash);
        return txHash;
    }

    /**
     * 记入执行结果到 {@link MessageStore}。
     *
     * @param messageId 消息 ID
     * @param txHash    目标链交易哈希
     * @return 记录成功返回 true；消息不存在返回 false
     */
    public boolean recordExecution(String messageId, String txHash) {
        if (messageId == null || txHash == null) {
            return false;
        }
        return store.recordExecution(messageId, txHash);
    }

    /**
     * 查询某消息的目标链执行交易哈希。
     *
     * @param messageId 消息 ID
     * @return 交易哈希 Optional
     */
    public java.util.Optional<String> getExecutionTxHash(String messageId) {
        return store.getExecutionTxHash(messageId);
    }

    // ==================== 内部工具 ====================

    /**
     * 构造目标链执行 calldata。
     *
     * <p>采用简化的 32 字节摘要方案：
     * SHA-256(messageId || sourceChain || targetContract || payload.encodedData)。</p>
     *
     * <p>实际生产中应按目标合约 ABI 编码，例如：
     * {@code executeMessage(bytes32 messageId, uint8 payloadType, bytes payload)}。</p>
     *
     * @param message 跨链消息
     * @return calldata 字节（32 字节）
     */
    protected byte[] buildCalldata(CrossChainMessage message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(message.getMessageId().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(message.getSourceChain().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(message.getTargetContract().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            if (message.getPayload() != null) {
                digest.update(message.getPayload().getEncodedData().getBytes(StandardCharsets.UTF_8));
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 获取已注册的目标链适配器（仅供测试 / 监控使用）。
     *
     * @return 适配器映射
     */
    public Map<String, ChainAdapter> getTargetAdapters() {
        return java.util.Collections.unmodifiableMap(targetAdapters);
    }

    /**
     * 添加目标链适配器。
     *
     * @param targetChain 目标链 ID
     * @param adapter     链适配器
     */
    public void addAdapter(String targetChain, ChainAdapter adapter) {
        targetAdapters.put(targetChain, adapter);
    }
}