package org.nexus.oracle.governance.execution;

import lombok.extern.slf4j.Slf4j;
import org.nexus.oracle.governance.Proposal;
import org.nexus.oracle.governance.ProposalState;
import org.nexus.oracle.governance.event.GovernanceExecutionCompletedEvent;
import org.nexus.oracle.governance.event.SoftwareUpgradeEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * 软件升级治理执行器。
 *
 * <p>当 {@link Proposal.Type#SOFTWARE_UPGRADE} 类型提案通过后，由
 * {@link GovernanceExecutionDispatcher} 分发至此执行器触发升级流程。
 *
 * <p>执行流程：
 * <ol>
 *   <li>解析提案 payload：{@code { "target": "gateway|bridge|signing|wallet",
 *       "version": "2.1.0", "config": {...} }}</li>
 *   <li>记录当前版本到审计日志（{@link GovernanceAuditLog}）</li>
 *   <li>更新目标服务的版本配置（按 {@code config-update-mode} 配置）</li>
 *   <li>发出 {@link SoftwareUpgradeEvent} Spring 事件</li>
 *   <li>记录执行结果到提案（{@code executionResult} 字段）</li>
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
 * <p><b>GOV-P2-03</b>：{@code updateVersionConfig} 按
 * {@code governance.execution.upgrade.config-update-mode} 配置执行：
 * <ul>
 *   <li>{@code NONE} — 仅日志记录</li>
 *   <li>{@code NACOS} — Nacos 配置更新（占位，需接入 Nacos 客户端）</li>
 *   <li>{@code FILE} — 写入本地配置文件</li>
 *   <li>{@code EVENT} — 发出 {@link SoftwareUpgradeEvent}（默认，由外部监听器处理）</li>
 * </ul>
 *
 * @since 2.0.0
 */
@Slf4j
@Component
public class SoftwareUpgradeExecutor {

    /** 支持的升级目标服务 */
    private static final java.util.Set<String> SUPPORTED_TARGETS = java.util.Set.of(
            "gateway", "bridge", "signing", "wallet");

    /** 配置更新模式枚举（GOV-P2-03） */
    public enum ConfigUpdateMode {
        /** 仅日志记录，不更新配置 */
        NONE,
        /** Nacos 配置中心更新（占位） */
        NACOS,
        /** 写入本地配置文件 */
        FILE,
        /** 发出 Spring 事件由外部监听器处理（默认） */
        EVENT
    }

    private final ApplicationEventPublisher eventPublisher;
    private final GovernanceAuditLog auditLog;

    /** 配置更新模式（GOV-P2-03），默认 EVENT */
    @Value("${governance.execution.upgrade.config-update-mode:EVENT}")
    private String configUpdateModeConfig;

    /** 本地配置文件目录（FILE 模式使用） */
    @Value("${governance.execution.upgrade.config-dir:./nexus-upgrade-configs}")
    private String configDir;

    /**
     * 构造执行器。
     *
     * @param eventPublisher Spring 事件发布器
     * @param auditLog       治理审计日志
     */
    public SoftwareUpgradeExecutor(ApplicationEventPublisher eventPublisher, GovernanceAuditLog auditLog) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.auditLog = Objects.requireNonNull(auditLog, "auditLog must not be null");
    }

    /**
     * 执行软件升级。
     *
     * <p>解析提案 payload、写入版本配置、发布事件、回写提案状态与执行结果。
     * 任何异常均被捕获并回写为 {@link ProposalState#EXECUTION_FAILED}，
     * 不向上抛出（保证调度器稳定性）。
     *
     * @param proposal 已通过的软件升级提案
     * @return 执行结果对象（包含 success、target、version、errorMessage 等字段）
     */
    @Transactional
    public ExecutionResult execute(Proposal proposal) {
        if (proposal == null) {
            return ExecutionResult.failure("Proposal must not be null");
        }
        String proposalId = proposal.getProposalId();
        ProposalState previousState = proposal.getState();
        log.info("Software upgrade execution started: proposalId={}", proposalId);

        try {
            // 1. 解析 payload
            Map<String, Object> params = proposal.getParameters();
            if (params == null) {
                throw new IllegalArgumentException("Missing payload parameters for SOFTWARE_UPGRADE proposal");
            }
            String target = asString(params.get("target"));
            String version = asString(params.get("version"));
            Object config = params.get("config");

            if (target == null || target.isBlank()) {
                throw new IllegalArgumentException("Missing 'target' in upgrade payload");
            }
            if (version == null || version.isBlank()) {
                throw new IllegalArgumentException("Missing 'version' in upgrade payload");
            }
            if (!SUPPORTED_TARGETS.contains(target)) {
                throw new IllegalArgumentException("Unsupported upgrade target: " + target
                        + ", supported: " + SUPPORTED_TARGETS);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> configMap = config instanceof Map ? (Map<String, Object>) config : null;

            // 2. 记录当前版本到审计日志
            Map<String, Object> auditDetails = new LinkedHashMap<>();
            auditDetails.put("target", target);
            auditDetails.put("newVersion", version);
            auditDetails.put("config", configMap);

            // 3. 更新目标服务的版本配置（GOV-P2-03: 按 config-update-mode 配置）
            updateVersionConfig(target, version, configMap);

            // 4. 发出 SoftwareUpgradeEvent
            SoftwareUpgradeEvent upgradeEvent = new SoftwareUpgradeEvent(
                    proposalId, target, version, configMap, proposal);
            eventPublisher.publishEvent(upgradeEvent);

            // 5. 记录执行结果到提案
            Map<String, Object> executionResult = new LinkedHashMap<>();
            executionResult.put("success", true);
            executionResult.put("target", target);
            executionResult.put("version", version);
            executionResult.put("upgradedAt", java.time.Instant.now().toString());
            proposal.setExecutionResult(executionResult);

            // 6. 回写提案状态为 EXECUTED（GOV-P1-01: transitionTo 校验合法性）
            proposal.transitionTo(ProposalState.EXECUTED);

            // 7. 记录审计日志
            auditLog.record(proposalId, "SOFTWARE_UPGRADE", proposal.getProposer(),
                    previousState, ProposalState.EXECUTED, true, auditDetails);

            // 8. 发出 GovernanceExecutionCompletedEvent
            eventPublisher.publishEvent(new GovernanceExecutionCompletedEvent(
                    proposalId, "SOFTWARE_UPGRADE", true,
                    ProposalState.EXECUTED, null, proposal));

            log.info("Software upgrade executed successfully: proposalId={}, target={}, version={}",
                    proposalId, target, version);
            return ExecutionResult.success(target, version);

        } catch (Exception e) {
            // GOV-P2-01: 完整异常仅记录到日志（DEBUG 级别）
            log.error("Software upgrade execution failed: proposalId={}, error={}",
                    proposalId, e.getMessage(), e);
            log.debug("Software upgrade full exception details: proposalId={}", proposalId, e);

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
            auditLog.record(proposalId, "SOFTWARE_UPGRADE", proposal.getProposer(),
                    previousState, ProposalState.EXECUTION_FAILED, false,
                    Map.of("errorMessage", sanitizedMessage));

            // 发出完成事件（脱敏后的信息）
            eventPublisher.publishEvent(new GovernanceExecutionCompletedEvent(
                    proposalId, "SOFTWARE_UPGRADE", false,
                    ProposalState.EXECUTION_FAILED, sanitizedMessage, proposal));

            return ExecutionResult.failure(sanitizedMessage);
        }
    }

    /**
     * 更新目标服务的版本配置（GOV-P2-03）。
     *
     * <p>按 {@code governance.execution.upgrade.config-update-mode} 配置执行：
     * <ul>
     *   <li>{@code NONE} — 仅日志记录</li>
     *   <li>{@code NACOS} — Nacos 配置更新（占位，日志警告未实现）</li>
     *   <li>{@code FILE} — 写入本地配置文件 {@code <config-dir>/<target>-version.properties}</li>
     *   <li>{@code EVENT} — 仅日志记录（事件由主流程发出）</li>
     * </ul>
     *
     * @param target  目标服务
     * @param version 目标版本
     * @param config  附带配置
     */
    private void updateVersionConfig(String target, String version, Map<String, Object> config) {
        ConfigUpdateMode mode = parseConfigUpdateMode(configUpdateModeConfig);
        log.info("Updating version config: mode={}, target={}, version={}", mode, target, version);

        switch (mode) {
            case NONE:
                log.info("Config update mode NONE: skip config update for target={}", target);
                break;

            case NACOS:
                // GOV-P2-03: Nacos 配置更新占位
                // 实际实现需注入 NacosConfigService，此处仅日志警告
                log.warn("Config update mode NACOS: not yet implemented (target={}, version={}). "
                        + "Please integrate Nacos client or use EVENT mode with external listener.", target, version);
                break;

            case FILE:
                writeConfigToFile(target, version, config);
                break;

            case EVENT:
                // EVENT 模式：SoftwareUpgradeEvent 已在主流程发出，
                // 此处仅记录日志，由外部监听器处理实际配置更新
                log.info("Config update mode EVENT: SoftwareUpgradeEvent published, "
                        + "external listener should handle config update for target={}", target);
                break;

            default:
                log.warn("Unknown config update mode: {}, fallback to NONE", mode);
        }
    }

    /**
     * 解析配置更新模式（容错处理）。
     *
     * @param config 原始配置字符串
     * @return 解析后的模式；无法解析返回默认 {@code EVENT}
     */
    private ConfigUpdateMode parseConfigUpdateMode(String config) {
        if (config == null || config.isBlank()) {
            return ConfigUpdateMode.EVENT;
        }
        try {
            return ConfigUpdateMode.valueOf(config.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid config-update-mode '{}', fallback to EVENT", config);
            return ConfigUpdateMode.EVENT;
        }
    }

    /**
     * 将版本配置写入本地文件（GOV-P2-03 FILE 模式）。
     *
     * <p>写入路径：{@code <config-dir>/<target>-version.properties}
     *
     * @param target  目标服务
     * @param version 目标版本
     * @param config  附带配置
     */
    private void writeConfigToFile(String target, String version, Map<String, Object> config) {
        Path dir = Paths.get(configDir);
        Path file = dir.resolve(target + "-version.properties");

        Properties props = new Properties();
        props.setProperty("target", target);
        props.setProperty("version", version);
        props.setProperty("updatedAt", java.time.Instant.now().toString());
        if (config != null) {
            for (Map.Entry<String, Object> entry : config.entrySet()) {
                props.setProperty("config." + entry.getKey(),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
            }
        }

        try {
            Files.createDirectories(dir);
            try (var out = Files.newBufferedWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                props.store(out, "NexusChain software upgrade config for " + target);
            }
            log.info("Version config written to file: {}", file);
        } catch (IOException e) {
            log.error("Failed to write version config file: {}", file, e);
            throw new IllegalStateException("Failed to write version config file: " + file, e);
        }
    }

    /**
     * 设置配置更新模式（测试 / 运维用，GOV-P2-03）。
     *
     * @param configUpdateModeConfig 配置更新模式字符串
     */
    public void setConfigUpdateModeConfig(String configUpdateModeConfig) {
        this.configUpdateModeConfig = configUpdateModeConfig;
    }

    /**
     * 设置本地配置文件目录（测试 / 运维用，GOV-P2-03）。
     *
     * @param configDir 配置目录路径
     */
    public void setConfigDir(String configDir) {
        this.configDir = configDir;
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
     * <p>不可变，描述一次软件升级执行的结果。
     */
    public static final class ExecutionResult {
        /** 执行是否成功 */
        private final boolean success;
        /** 目标服务（成功时填充） */
        private final String target;
        /** 目标版本（成功时填充） */
        private final String version;
        /** 失败原因（失败时填充） */
        private final String errorMessage;

        private ExecutionResult(boolean success, String target, String version, String errorMessage) {
            this.success = success;
            this.target = target;
            this.version = version;
            this.errorMessage = errorMessage;
        }

        /**
         * 构造成功结果。
         *
         * @param target  目标服务
         * @param version 目标版本
         * @return 成功结果实例
         */
        public static ExecutionResult success(String target, String version) {
            return new ExecutionResult(true, target, version, null);
        }

        /**
         * 构造失败结果。
         *
         * @param errorMessage 失败原因
         * @return 失败结果实例
         */
        public static ExecutionResult failure(String errorMessage) {
            return new ExecutionResult(false, null, null, errorMessage);
        }

        /** @return 执行是否成功 */
        public boolean isSuccess() {
            return success;
        }

        /** @return 目标服务 */
        public String getTarget() {
            return target;
        }

        /** @return 目标版本 */
        public String getVersion() {
            return version;
        }

        /** @return 失败原因 */
        public String getErrorMessage() {
            return errorMessage;
        }

        @Override
        public String toString() {
            return "ExecutionResult{success=" + success + ", target='" + target
                    + "', version='" + version + "', errorMessage='" + errorMessage + "'}";
        }
    }
}
