package org.nexus.gateway.sandbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时检查微信/支付宝渠道配置完整性，报告缺失项。
 *
 * <p>当 sandbox=false（真实 API 模式）但密钥未配置时，输出 WARN 日志列出缺失项。
 * 当 sandbox=true（dry-run 模拟模式）时，缺失密钥属于预期行为，仅输出 INFO 日志。</p>
 *
 * <p>检查的配置项：</p>
 * <ul>
 *   <li>微信：app-id, mch-id, api-v3-key, merchant-private-key, cert-serial-no</li>
 *   <li>支付宝：app-id, merchant-private-key, alipay-public-key</li>
 * </ul>
 */
@Component
@Profile("sandbox")
public class SandboxConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(SandboxConfigValidator.class);

    // --- 微信渠道配置 ---

    @Value("${nexus.connectors.wechat.enabled:false}")
    private boolean wechatEnabled;

    @Value("${nexus.connectors.wechat.sandbox:true}")
    private boolean wechatSandbox;

    @Value("${nexus.connectors.wechat.app-id:}")
    private String wechatAppId;

    @Value("${nexus.connectors.wechat.mch-id:}")
    private String wechatMchId;

    @Value("${nexus.connectors.wechat.api-v3-key:}")
    private String wechatApiV3Key;

    @Value("${nexus.connectors.wechat.merchant-private-key:}")
    private String wechatMerchantPrivateKey;

    @Value("${nexus.connectors.wechat.cert-serial-no:}")
    private String wechatCertSerialNo;

    // --- 支付宝渠道配置 ---

    @Value("${nexus.connectors.alipay.enabled:false}")
    private boolean alipayEnabled;

    @Value("${nexus.connectors.alipay.sandbox:true}")
    private boolean alipaySandbox;

    @Value("${nexus.connectors.alipay.app-id:}")
    private String alipayAppId;

    @Value("${nexus.connectors.alipay.merchant-private-key:}")
    private String alipayMerchantPrivateKey;

    @Value("${nexus.connectors.alipay.alipay-public-key:}")
    private String alipayPublicKey;

    /**
     * 检查微信渠道配置完整性。
     *
     * <p>当 wechat 未启用时，跳过检查并返回空缺失列表。
     * 当 sandbox=true（dry-run 模式）时，缺失密钥属于预期行为，不报告为缺失项。
     * 当 sandbox=false（真实 API 模式）时，所有必填项必须配置，缺失项记入结果。</p>
     *
     * @return 微信渠道缺失配置项列表，空列表表示配置完整或渠道未启用
     */
    public List<String> checkWeChatConfig() {
        List<String> missing = new ArrayList<>();

        if (!wechatEnabled) {
            log.info("[ConfigCheck] 微信渠道未启用，跳过配置检查");
            return missing;
        }

        if (isBlank(wechatAppId)) {
            missing.add("nexus.connectors.wechat.app-id");
        }
        if (isBlank(wechatMchId)) {
            missing.add("nexus.connectors.wechat.mch-id");
        }

        // sandbox=false（真实 API 模式）时，密钥类配置为必填
        if (!wechatSandbox) {
            if (isBlank(wechatApiV3Key)) {
                missing.add("nexus.connectors.wechat.api-v3-key");
            }
            if (isBlank(wechatMerchantPrivateKey)) {
                missing.add("nexus.connectors.wechat.merchant-private-key");
            }
            if (isBlank(wechatCertSerialNo)) {
                missing.add("nexus.connectors.wechat.cert-serial-no");
            }
        }

        return missing;
    }

    /**
     * 检查支付宝渠道配置完整性。
     *
     * <p>当 alipay 未启用时，跳过检查并返回空缺失列表。
     * 当 sandbox=true（dry-run 模式）时，缺失密钥属于预期行为，不报告为缺失项。
     * 当 sandbox=false（真实 API 模式）时，所有必填项必须配置，缺失项记入结果。</p>
     *
     * @return 支付宝渠道缺失配置项列表，空列表表示配置完整或渠道未启用
     */
    public List<String> checkAlipayConfig() {
        List<String> missing = new ArrayList<>();

        if (!alipayEnabled) {
            log.info("[ConfigCheck] 支付宝渠道未启用，跳过配置检查");
            return missing;
        }

        if (isBlank(alipayAppId)) {
            missing.add("nexus.connectors.alipay.app-id");
        }

        // sandbox=false（真实 API 模式）时，密钥类配置为必填
        if (!alipaySandbox) {
            if (isBlank(alipayMerchantPrivateKey)) {
                missing.add("nexus.connectors.alipay.merchant-private-key");
            }
            if (isBlank(alipayPublicKey)) {
                missing.add("nexus.connectors.alipay.alipay-public-key");
            }
        }

        return missing;
    }

    /**
     * 执行全量配置检查，返回汇总结果。
     *
     * <p>同时检查微信和支付宝渠道，将所有缺失项汇总到 {@link ConfigCheckResult} 中。
     * 调用方可根据结果中的 {@code wechatSandbox} / {@code alipaySandbox} 判断各渠道模式，
     * 根据 {@code missingItems} 判断缺失的具体配置项。</p>
     *
     * @return 配置检查结果，包含缺失项列表和各渠道模式信息
     */
    public ConfigCheckResult validate() {
        List<String> wechatMissing = checkWeChatConfig();
        List<String> alipayMissing = checkAlipayConfig();

        List<String> allMissing = new ArrayList<>(wechatMissing);
        allMissing.addAll(alipayMissing);

        return new ConfigCheckResult(
                wechatEnabled, wechatSandbox, wechatMissing,
                alipayEnabled, alipaySandbox, alipayMissing,
                allMissing
        );
    }

    // --- 内部工具方法 ---

    /**
     * 判断字符串是否为空白（null、空串或仅含空白字符）。
     *
     * @param value 待检查的字符串
     * @return true 表示空白，false 表示有实际内容
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 配置检查结果。
     *
     * <p>包含微信和支付宝渠道的启用状态、sandbox 模式、各自缺失项列表，
     * 以及合并后的全量缺失项列表。</p>
     */
    public static class ConfigCheckResult {

        private final boolean wechatEnabled;
        private final boolean wechatSandbox;
        private final List<String> wechatMissingItems;

        private final boolean alipayEnabled;
        private final boolean alipaySandbox;
        private final List<String> alipayMissingItems;

        private final List<String> missingItems;

        /**
         * 构造配置检查结果。
         *
         * @param wechatEnabled       微信渠道是否启用
         * @param wechatSandbox       微信渠道是否为 sandbox（dry-run）模式
         * @param wechatMissingItems  微信渠道缺失配置项列表
         * @param alipayEnabled       支付宝渠道是否启用
         * @param alipaySandbox       支付宝渠道是否为 sandbox（dry-run）模式
         * @param alipayMissingItems  支付宝渠道缺失配置项列表
         * @param missingItems        全量缺失配置项列表（微信 + 支付宝）
         */
        public ConfigCheckResult(boolean wechatEnabled, boolean wechatSandbox,
                                  List<String> wechatMissingItems,
                                  boolean alipayEnabled, boolean alipaySandbox,
                                  List<String> alipayMissingItems,
                                  List<String> missingItems) {
            this.wechatEnabled = wechatEnabled;
            this.wechatSandbox = wechatSandbox;
            this.wechatMissingItems = wechatMissingItems;
            this.alipayEnabled = alipayEnabled;
            this.alipaySandbox = alipaySandbox;
            this.alipayMissingItems = alipayMissingItems;
            this.missingItems = missingItems;
        }

        public boolean isWechatEnabled() { return wechatEnabled; }
        public boolean isWechatSandbox() { return wechatSandbox; }
        public List<String> getWechatMissingItems() { return wechatMissingItems; }

        public boolean isAlipayEnabled() { return alipayEnabled; }
        public boolean isAlipaySandbox() { return alipaySandbox; }
        public List<String> getAlipayMissingItems() { return alipayMissingItems; }

        /** 全量缺失配置项列表（微信 + 支付宝合并） */
        public List<String> getMissingItems() { return missingItems; }

        /** 是否存在任何缺失项 */
        public boolean hasMissingItems() { return !missingItems.isEmpty(); }

        /** 是否存在真实 API 模式下的缺失项（需 WARN 级别关注） */
        public boolean hasRealModeMissingItems() {
            return (!wechatSandbox && !wechatMissingItems.isEmpty())
                    || (!alipaySandbox && !alipayMissingItems.isEmpty());
        }
    }
}