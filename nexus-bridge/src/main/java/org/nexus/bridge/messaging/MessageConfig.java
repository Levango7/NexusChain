package org.nexus.bridge.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 跨链消息传递配置。
 *
 * <p>对应 {@code application.yml} 中 {@code nexus.bridge.messaging} 前缀的配置项，
 * 控制消息中继器的运行参数：</p>
 *
 * <ul>
 *   <li>{@code enabled}             — 是否启用跨链消息传递模块</li>
 *   <li>{@code requiredSignatures}  — 多签要求（N-of-M 中的 N）</li>
 *   <li>{@code messageTimeout}      — 消息超时窗口（秒），超过则标记 EXPIRED</li>
 *   <li>{@code maxPayloadSize}      — 单条消息最大负载字节数</li>
 * </ul>
 *
 * <p>配置示例：</p>
 * <pre>
 *   nexus:
 *     bridge:
 *       messaging:
 *         enabled: true
 *         required-signatures: 2
 *         message-timeout: 3600
 *         max-payload-size: 32768
 * </pre>
 *
 * @since 1.9.2
 */
@Configuration
@ConfigurationProperties(prefix = "nexus.bridge.messaging")
public class MessageConfig {

    /** 默认多签要求。 */
    private static final int DEFAULT_REQUIRED_SIGNATURES = 2;

    /** 默认消息超时（1 小时）。 */
    private static final long DEFAULT_MESSAGE_TIMEOUT = 3600L;

    /** 默认最大负载 32KB。 */
    private static final int DEFAULT_MAX_PAYLOAD_SIZE = 32768;

    /** 是否启用。 */
    private boolean enabled = true;

    /** 多签要求。 */
    private int requiredSignatures = DEFAULT_REQUIRED_SIGNATURES;

    /** 消息超时（秒）。 */
    private long messageTimeout = DEFAULT_MESSAGE_TIMEOUT;

    /** 最大负载字节数。 */
    private int maxPayloadSize = DEFAULT_MAX_PAYLOAD_SIZE;

    /**
     * 默认构造函数。
     */
    public MessageConfig() {
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRequiredSignatures() {
        return requiredSignatures;
    }

    public void setRequiredSignatures(int requiredSignatures) {
        this.requiredSignatures = requiredSignatures;
    }

    public long getMessageTimeout() {
        return messageTimeout;
    }

    public void setMessageTimeout(long messageTimeout) {
        this.messageTimeout = messageTimeout;
    }

    public int getMaxPayloadSize() {
        return maxPayloadSize;
    }

    public void setMaxPayloadSize(int maxPayloadSize) {
        this.maxPayloadSize = maxPayloadSize;
    }

    @Override
    public String toString() {
        return "MessageConfig{enabled=" + enabled
                + ", requiredSignatures=" + requiredSignatures
                + ", messageTimeout=" + messageTimeout + "s"
                + ", maxPayloadSize=" + maxPayloadSize + "B}";
    }
}