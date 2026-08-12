package org.nexus.oracle.governance.execution;

import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 验证者集变更执行器（NexFinality 治理→验证者集连接轴）。
 *
 * <p>消费 {@link Proposal.Type#VALIDATOR_SET_CHANGE} 提案，通过 {@link ValidatorSetPort}
 * 调用底层共识层完成验证者新增/移除。oracle 模块只依赖端口，不直接引用 nexus-core。</p>
 *
 * <p>fail-closed：参数缺失或端口未注入时标记 EXECUTION_FAILED，不静默忽略。</p>
 */
@Component
public class ValidatorSetExecutor {

    private static final Logger log = LoggerFactory.getLogger(ValidatorSetExecutor.class);

    private final ValidatorSetPort validatorSetPort;
    private final GovernanceAuditLog auditLog;

    public ValidatorSetExecutor(@Autowired(required = false) ValidatorSetPort validatorSetPort,
                                GovernanceAuditLog auditLog) {
        this.validatorSetPort = validatorSetPort;
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    /**
     * 执行验证者集变更。
     *
     * @param proposal VALIDATOR_SET_CHANGE 提案
     * @return 执行结果
     */
    @Transactional
    public ExecutionResult execute(Proposal proposal) {
        if (proposal == null) {
            return ExecutionResult.failure("Proposal must not be null");
        }
        if (proposal.getType() != Proposal.Type.VALIDATOR_SET_CHANGE) {
            return ExecutionResult.failure("Proposal type must be VALIDATOR_SET_CHANGE");
        }
        if (validatorSetPort == null) {
            log.error("ValidatorSetPort not injected; cannot execute validator set change proposalId={}",
                    proposal.getProposalId());
            return ExecutionResult.failure("validator set port not available");
        }

        String proposalId = proposal.getProposalId();
        ProposalState previousState = proposal.getState();
        log.info("Validator set change execution started: proposalId={}", proposalId);

        try {
            Map<String, Object> params = proposal.getParameters();
            if (params == null) {
                throw new IllegalArgumentException("Missing payload parameters");
            }

            String action = asString(params.get("action"));
            String validatorAddress = asString(params.get("validatorAddress"));
            if (action == null || (!action.equals("add") && !action.equals("remove"))) {
                throw new IllegalArgumentException("action must be 'add' or 'remove'");
            }
            if (validatorAddress == null || validatorAddress.isBlank()) {
                throw new IllegalArgumentException("validatorAddress is required");
            }

            String publicKey = asString(params.get("publicKey"));
            BigDecimal stakeAmount = asBigDecimal(params.get("stakeAmount"));
            if ("add".equals(action) && (publicKey == null || stakeAmount == null)) {
                throw new IllegalArgumentException("add action requires publicKey and stakeAmount");
            }

            ValidatorSetPort.ExecutionResult result = validatorSetPort.apply(proposal);
            if (!result.success()) {
                throw new IllegalStateException("Validator set change failed: " + result.message());
            }

            Map<String, Object> executionResult = new LinkedHashMap<>();
            executionResult.put("success", true);
            executionResult.put("action", action);
            executionResult.put("validatorAddress", validatorAddress);
            executionResult.put("executedAt", java.time.Instant.now().toString());
            proposal.setExecutionResult(executionResult);
            proposal.transitionTo(ProposalState.EXECUTED);

            auditLog.record(proposalId, proposal.getType().name(), proposal.getProposer(),
                    previousState, ProposalState.EXECUTED, true,
                    Map.of("action", action, "validatorAddress", validatorAddress));

            log.info("Validator set change executed: proposalId={}, action={}, validator={}",
                    proposalId, action, validatorAddress);
            return ExecutionResult.success(validatorAddress, action);

        } catch (Exception e) {
            log.error("Validator set change failed: proposalId={}, error={}", proposalId, e.getMessage(), e);
            proposal.transitionTo(ProposalState.EXECUTION_FAILED);
            Map<String, Object> failureResult = new LinkedHashMap<>();
            failureResult.put("success", false);
            failureResult.put("errorMessage", ErrorMessageSanitizer.sanitizeErrorMessage(e));
            failureResult.put("failedAt", java.time.Instant.now().toString());
            proposal.setExecutionResult(failureResult);
            auditLog.record(proposalId, proposal.getType().name(), proposal.getProposer(),
                    previousState, ProposalState.EXECUTION_FAILED, false,
                    Map.of("error", ErrorMessageSanitizer.sanitizeErrorMessage(e)));
            return ExecutionResult.failure("Validator set change failed: " + e.getMessage());
        }
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal asBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal) return (BigDecimal) o;
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 执行结果。
     */
    public static final class ExecutionResult {
        private final boolean success;
        private final String validatorAddress;
        private final String action;
        private final String errorMessage;

        private ExecutionResult(boolean success, String validatorAddress, String action, String errorMessage) {
            this.success = success;
            this.validatorAddress = validatorAddress;
            this.action = action;
            this.errorMessage = errorMessage;
        }

        public static ExecutionResult success(String validatorAddress, String action) {
            return new ExecutionResult(true, validatorAddress, action, null);
        }

        public static ExecutionResult failure(String errorMessage) {
            return new ExecutionResult(false, null, null, errorMessage);
        }

        public boolean isSuccess() { return success; }
        public String getValidatorAddress() { return validatorAddress; }
        public String getAction() { return action; }
        public String getErrorMessage() { return errorMessage; }
    }
}
