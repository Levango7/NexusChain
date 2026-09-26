package org.nexus.gateway.sandbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 启动时调用 {@link SandboxConfigValidator} 检查微信/支付宝渠道配置完整性，
 * 输出检查结果日志。
 *
 * <p>日志级别规则：</p>
 * <ul>
 *   <li>配置完整 → INFO 日志，报告各渠道模式（sandbox/real API）</li>
 *   <li>sandbox=true 但缺失密钥 → INFO 日志（dry-run 模式下属于预期行为）</li>
 *   <li>sandbox=false 但缺失密钥 → WARN 日志，列出缺失项（真实 API 模式下会导致调用失败）</li>
 * </ul>
 */
@Component
@Profile("sandbox")
public class SandboxStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SandboxStartupRunner.class);

    private final SandboxConfigValidator validator;

    /**
     * 构造器注入配置验证器。
     *
     * @param validator 渠道配置完整性验证器
     */
    public SandboxStartupRunner(SandboxConfigValidator validator) {
        this.validator = validator;
    }

    @Override
    public void run(ApplicationArguments args) {
        SandboxConfigValidator.ConfigCheckResult result = validator.validate();

        logWeChatResult(result);
        logAlipayResult(result);

        if (result.hasRealModeMissingItems()) {
            log.warn("[ConfigCheck] 真实 API 模式下存在缺失配置项，支付渠道调用将失败！"
                    + "缺失项: {}", result.getMissingItems());
        } else if (result.hasMissingItems()) {
            log.info("[ConfigCheck] sandbox/dry-run 模式下部分密钥未配置（预期行为），"
                    + "缺失项: {}", result.getMissingItems());
        } else {
            log.info("[ConfigCheck] 所有已启用渠道的配置项完整");
        }
    }

    /**
     * 输出微信渠道配置检查结果日志。
     *
     * @param result 配置检查结果
     */
    private void logWeChatResult(SandboxConfigValidator.ConfigCheckResult result) {
        if (!result.isWechatEnabled()) {
            log.info("[ConfigCheck] 微信渠道: 未启用");
            return;
        }

        String mode = result.isWechatSandbox() ? "sandbox (dry-run)" : "real API";
        if (result.getWechatMissingItems().isEmpty()) {
            log.info("[ConfigCheck] 微信渠道: {} 模式, 配置完整", mode);
        } else {
            if (result.isWechatSandbox()) {
                log.info("[ConfigCheck] 微信渠道: {} 模式, 缺失配置项（预期）: {}",
                        mode, result.getWechatMissingItems());
            } else {
                log.warn("[ConfigCheck] 微信渠道: {} 模式, 缺失配置项: {}",
                        mode, result.getWechatMissingItems());
            }
        }
    }

    /**
     * 输出支付宝渠道配置检查结果日志。
     *
     * @param result 配置检查结果
     */
    private void logAlipayResult(SandboxConfigValidator.ConfigCheckResult result) {
        if (!result.isAlipayEnabled()) {
            log.info("[ConfigCheck] 支付宝渠道: 未启用");
            return;
        }

        String mode = result.isAlipaySandbox() ? "sandbox (dry-run)" : "real API";
        if (result.getAlipayMissingItems().isEmpty()) {
            log.info("[ConfigCheck] 支付宝渠道: {} 模式, 配置完整", mode);
        } else {
            if (result.isAlipaySandbox()) {
                log.info("[ConfigCheck] 支付宝渠道: {} 模式, 缺失配置项（预期）: {}",
                        mode, result.getAlipayMissingItems());
            } else {
                log.warn("[ConfigCheck] 支付宝渠道: {} 模式, 缺失配置项: {}",
                        mode, result.getAlipayMissingItems());
            }
        }
    }
}