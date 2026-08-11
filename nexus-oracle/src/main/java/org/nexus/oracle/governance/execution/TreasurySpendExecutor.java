package org.nexus.oracle.governance.execution;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.Treasury;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.TreasurySpendEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 国库转账治理执行器。
 *
 * <p>当 {@link Proposal.Type#TREASURY_SPEND} 类型提案通过后，由
 * {@link GovernanceExecutionDispatcher} 分发至此执行器触发国库转账。
 *
 * <p>执行流程：
 * <ol>
 *   <li>解析提案 payload：{@code { "targetAddress": "0x...", "amount": "1000000",
 *       "token": "USDT", "chain": "ethereum" }}</li>
 *   <li>校验国库余额充足</li>
 *   <li>调用 {@link Treasury#spend} 执行链上转账</li>
 *   <li>记录转账哈希到审计日志</li>
 *   <li>发出 {@link TreasurySpendEvent} Spring 事件</li>
 *   <li>记录执行结果到提案</li>
 *   <li>回写提案状态为 {@link ProposalState#EXECUTED} 或 {@link ProposalState#EXECUTION_FAILED}</li>
 *   <li>发出 {@link GovernanceExecutionCompletedEvent} 事件</li>
 * </ol>
 *
 * <p><b>GOV-P1-01</b>：状态变更通过 {@link Proposal#transitionTo(ProposalState)} 进行，
 * 校验转换合法性。
 *
 * <p><b>GOV-P2-01</b>：异常信息经 {@link ErrorMessageSanitizer} 脱敏后存入
 * {@code executionResult}，完整异常仅记录到日志。
 *
 * <p><b>GOV-P2-04</b>：BigDecimal 金额校验精度（{@code scale <= 18}）与范围
 * （{@code 0 < amount < 1e30}），防止 DoS。
 *
 * <p><b>GOV-P2-05</b>：{@code targetAddress} 校验以太坊地址格式
 * （{@code 0x + 40 hex 字符}）。
 *
 * @since 2.0.0
 */
@Slf4j
@Component
public class TreasurySpendExecutor {

    /** 以太坊地址正则：0x + 40 hex 字符（GOV-P2-05） */
    private static final Pattern ETHEREUM_ADDRESS_PATTERN =
            Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** 金额最大精度（scale），以太坊 wei 精度为 18（GOV-P2-04） */
    private static final int MAX_AMOUNT_SCALE = 18;

    /** 金额上限：1e30（GOV-P2-04） */
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1e30");

    private final Treasury treasury;
    private final ApplicationEventPublisher eventPublisher;
    private final GovernanceAuditLog auditLog;

    /**
     * 构造执行器。
     *
     * @param treasury       国库服务
     * @param eventPublisher Spring 事件发布器
     * @param auditLog       治理审计日志
     */
    public TreasurySpendExecutor(Treasury treasury, ApplicationEventPublisher eventPublisher,
                                 GovernanceAuditLog auditLog) {
        this.treasury = Objects.requireNonNull(treasury, "treasury must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    /**
     * 执行国库转账。
     *
     * <p>解析 payload、校验余额、执行转账、发布事件、回写提案状态与执行结果。
     * 任何异常均被捕获并回写为 {@link ProposalState#EXECUTION_FAILED}，
     * 不向上抛出（保证调度器稳定性）。
     *
     * @param proposal 已通过的国库支出提案
     * @return 执行结果对象（包含 success、txHash、errorMessage 等字段）
     */
    @Transactional
    public ExecutionResult execute(Proposal proposal) {
        if (proposal == null) {
            return ExecutionResult.failure("Proposal must not be null");
        }
        String proposalId = proposal.getProposalId();
        ProposalState previousState = proposal.getState();
        log.info("Treasury spend execution started: proposalId={}", proposalId);

        try {
            // 1. 解析 payload
            Map<String, Object> params = proposal.getParameters();
            if (params == null) {
                throw new IllegalArgumentException("Missing payload parameters for TREASURY_SPEND proposal");
            }
            String targetAddress = asString(params.get("targetAddress"));
            String amountStr = asString(params.get("amount"));
            String token = asString(params.get("token"));
            String chain = asString(params.get("chain"));

            if (targetAddress == null || targetAddress.isBlank()) {
                throw new IllegalArgumentException("Missing 'targetAddress' in treasury spend payload");
            }
            // GOV-P2-05: 校验 targetAddress 为合法以太坊地址
            validateEthereumAddress(targetAddress);

            if (amountStr == null || amountStr.isBlank()) {
                throw new IllegalArgumentException("Missing 'amount' in treasury spend payload");
            }
            BigDecimal amount;
            try {
                amount = new BigDecimal(amountStr);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid 'amount' format: " + amountStr, e);
            }
            // GOV-P2-04: 校验 BigDecimal 精度与范围
            validateAmount(amount);

            // 2. 校验国库余额充足
            BigDecimal balance = treasury.balance();
            if (balance.compareTo(amount) < 0) {
                throw new IllegalStateException("Insufficient treasury balance: required=" + amount
                        + ", available=" + balance);
            }

            // 3. 调用 Treasury.spend 执行转账
            boolean spent = treasury.spend(amount, targetAddress, proposalId);
            if (!spent) {
                throw new IllegalStateException("Treasury.spend returned false for proposalId=" + proposalId);
            }

            // 4. 生成转账哈希（占位：实际从 Treasury 返回或链上回执获取）
            String txHash = generateTxHash(proposalId, targetAddress, amount, token, chain);

            // 5. 发出 TreasurySpendEvent
            TreasurySpendEvent spendEvent = new TreasurySpendEvent(
                    proposalId, targetAddress, amount, token, chain, txHash, proposal);
            eventPublisher.publishEvent(spendEvent);

            // 6. 记录执行结果到提案
            Map<String, Object> executionResult = new LinkedHashMap<>();
            executionResult.put("success", true);
            executionResult.put("targetAddress", targetAddress);
            executionResult.put("amount", amountStr);
            executionResult.put("token", token);
            executionResult.put("chain", chain);
            executionResult.put("txHash", txHash);
            executionResult.put("spentAt", java.time.Instant.now().toString());
            proposal.setExecutionResult(executionResult);

            // 7. 回写提案状态为 EXECUTED（GOV-P1-01: transitionTo 校验合法性）
            proposal.transitionTo(ProposalState.EXECUTED);

            // 8. 记录审计日志
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("targetAddress", targetAddress);
            auditDetails.put("amount", amountStr);
            auditDetails.put("token", token);
            auditDetails.put("chain", chain);
            auditDetails.put("txHash", txHash);
            auditLog.record(proposalId, "TREASURY_SPEND", proposal.getProposer(),
                    previousState, ProposalState.EXECUTED, true, auditDetails);

            // 9. 发出 GovernanceExecutionCompletedEvent
            eventPublisher.publishEvent(new GovernanceExecutionCompletedEvent(
                    proposalId, "TREASURY_SPEND", true,
                    ProposalState.EXECUTED, null, proposal));

            log.info("Treasury spend executed successfully: proposalId={}, amount={}, to={}, txHash={}",
                    proposalId, amount, targetAddress, txHash);
            return ExecutionResult.success(txHash);

        } catch (Exception e) {
            // GOV-P2-01: 完整异常仅记录到日志（DEBUG 级别）
            log.error("Treasury spend execution failed: proposalId={}, error={}",
                    proposalId, e.getMessage(), e);
            log.debug("Treasury spend full exception details: proposalId={}", proposalId, e);

            // GOV-P2-01: 异常信息脱敏后存入 executionResult
            String sanitizedMessage = ErrorMessageSanitizer.sanitizeErrorMessage(e);

            // 回写失败状态与结果（GOV-P1-01: transitionTo 校验合法性）
            proposal.transitionTo(ProposalState.EXECUTION_FAILED);
            Map<String, Object> failureResult = new LinkedHashMap<>();
            failureResult.put("success", false);
            failureResult.put("errorMessage", sanitizedMessage);
            failureResult.put("failedAt", java.time.Instant.now().toString());
            proposal.setExecutionResult(failureResult);

            // 记录失败审计（脱敏后的信息）
            auditLog.record(proposalId, "TREASURY_SPEND", proposal.getProposer(),
                    previousState, ProposalState.EXECUTION_FAILED, false,
                    Map.of("errorMessage", sanitizedMessage));

            // 发出完成事件（脱敏后的信息）
            eventPublisher.publishEvent(new GovernanceExecutionCompletedEvent(
                    proposalId, "TREASURY_SPEND", false,
                    ProposalState.EXECUTION_FAILED, sanitizedMessage, proposal));

            return ExecutionResult.failure(sanitizedMessage);
        }
    }

    /**
     * 校验以太坊地址格式（GOV-P2-05）。
     *
     * <p>合法格式：{@code 0x} 前缀 + 40 个十六进制字符（共 42 字符），
     * 对应以太坊 20 字节地址。
     *
     * @param targetAddress 待校验地址
     * @throws IllegalArgumentException 如果地址格式不合法
     */
    private static void validateEthereumAddress(String targetAddress) {
        if (!ETHEREUM_ADDRESS_PATTERN.matcher(targetAddress).matches()) {
            throw new IllegalArgumentException(
                    "Invalid Ethereum address format: " + targetAddress
                            + " (expected: 0x + 40 hex chars, e.g. 0x1234...5678)");
        }
    }

    /**
     * 校验 BigDecimal 金额精度与范围（GOV-P2-04）。
     *
     * <p>校验规则：
     * <ul>
     *   <li>{@code amount > 0}（必须为正数）</li>
     *   <li>{@code amount.scale() <= 18}（以太坊 wei 精度上限）</li>
     *   <li>{@code amount < 1e30}（上限，防止溢出与 DoS）</li>
     * </ul>
     *
     * @param amount 待校验金额
     * @throws IllegalArgumentException 如果金额不合法
     */
    private static void validateAmount(BigDecimal amount) {
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive: " + amount);
        }
        if (amount.scale() > MAX_AMOUNT_SCALE) {
            throw new IllegalArgumentException(
                    "Amount scale too large: " + amount.scale()
                            + " (max allowed: " + MAX_AMOUNT_SCALE + ", wei precision)");
        }
        if (amount.compareTo(MAX_AMOUNT) >= 0) {
            throw new IllegalArgumentException(
                    "Amount exceeds maximum allowed (1e30): " + amount);
        }
    }

    /**
     * 生成转账哈希（占位实现，GOV-P0-03 修复）。
     *
     * <p>使用 SHA-256 生成 256 位哈希，输出为 {@code 0x + 64 hex 字符}（标准以太坊交易哈希格式）。
     * 哈希输入：{@code proposalId + targetAddress + amount + token + chain + timestamp + nonce}。
     *
     * <p><b>注意</b>：这是占位哈希，真实部署应从链上交易回执中获取真实哈希。
     * 此处使用 SHA-256 替代原先的 {@code String.hashCode()}（32 位，碰撞概率高），
     * 将碰撞空间从 2^32 提升到 2^256，满足安全要求。
     *
     * @param proposalId    提案 ID
     * @param targetAddress 收款地址
     * @param amount        金额
     * @param token         代币标识
     * @param chain         目标链
     * @return 转账哈希字符串（0x + 64 hex 字符）
     */
    private String generateTxHash(String proposalId, String targetAddress, BigDecimal amount,
                                  String token, String chain) {
        String raw = String.join("|",
                String.valueOf(proposalId),
                String.valueOf(targetAddress),
                String.valueOf(amount),
                String.valueOf(token),
                String.valueOf(chain),
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(System.nanoTime()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return "0x" + HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * 安全类型转换：Object → String。
     *
     * @param value 原始值
     * @return 字符串表示；{@code null} 输入返回 {@code null}
     */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 执行结果值对象。
     *
     * <p>不可变，描述一次国库转账执行的结果。
     */
    public static final class ExecutionResult {
        /** 执行是否成功 */
        private final boolean success;
        /** 转账哈希（成功时填充） */
        private final String txHash;
        /** 失败原因（失败时填充） */
        private final String errorMessage;

        private ExecutionResult(boolean success, String txHash, String errorMessage) {
            this.success = success;
            this.txHash = txHash;
            this.errorMessage = errorMessage;
        }

        /**
         * 构造成功结果。
         *
         * @param txHash 转账哈希
         * @return 成功结果实例
         */
        public static ExecutionResult success(String txHash) {
            return new ExecutionResult(true, txHash, null);
        }

        /**
         * 构造失败结果。
         *
         * @param errorMessage 失败原因
         * @return 失败结果实例
         */
        public static ExecutionResult failure(String errorMessage) {
            return new ExecutionResult(false, null, errorMessage);
        }

        /** @return 执行是否成功 */
        public boolean isSuccess() {
            return success;
        }

        /** @return 转账哈希 */
        public String getTxHash() {
            return txHash;
        }

        /** @return 失败原因 */
        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return "ExecutionResult{success=" + success + ", txHash='" + txHash
                    + "', errorMessage='" + errorMessage + "'}";
        }
    }
}
