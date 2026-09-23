package org.nexus.gateway.logging;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;
import java.util.List;

/**
 * 结构化日志配置属性。
 *
 * <p>绑定 application.yml 中 {@code logging.structured} / {@code logging.sensitive}
 * / {@code logging.sampling} 节，供 {@link StructuredLogConfig} 及各 Filter 读取。</p>
 *
 * <p>使用 {@code @ConfigurationProperties(prefix = "logging")} 统一绑定三个子节，
 * 避免多个 {@code @ConfigurationProperties} 类的碎片化管理。</p>
 */
@ConfigurationProperties(prefix = "logging")
public class StructuredLogProperties {

    /** 结构化日志配置 */
    private Structured structured = new Structured();

    /** 敏感数据脱敏配置 */
    private Sensitive sensitive = new Sensitive();

    /** 日志采样配置 */
    private Sampling sampling = new Sampling();

    public Structured getStructured() {
        return structured;
    }

    public void setStructured(Structured structured) {
        this.structured = structured;
    }

    public Sensitive getSensitive() {
        return sensitive;
    }

    public void setSensitive(Sensitive sensitive) {
        this.sensitive = sensitive;
    }

    public Sampling getSampling() {
        return sampling;
    }

    public void setSampling(Sampling sampling) {
        this.sampling = sampling;
    }

    /** 结构化日志输出配置 */
    public static class Structured {
        /** 是否启用结构化日志 */
        private boolean enabled = true;
        /** 输出格式：json | text */
        private String format = "json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }
    }

    /** 敏感数据脱敏配置 */
    public static class Sensitive {
        /** 是否启用脱敏 */
        private boolean enabled = true;
        /** 敏感字段列表 */
        private List<String> fields = Arrays.asList(
                "password", "secret", "apiKey", "api_key",
                "privateKey", "private_key", "token", "hmac",
                "signature", "mchId", "appId"
        );
        /** 脱敏替换字符 */
        private String maskPattern = "****";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public List<String> getFields() {
            return fields;
        }

        public void setFields(List<String> fields) {
            this.fields = fields;
        }

        public String getMaskPattern() {
            return maskPattern;
        }

        public void setMaskPattern(String maskPattern) {
            this.maskPattern = maskPattern;
        }
    }

    /** 日志采样配置 */
    public static class Sampling {
        /** 是否启用采样 */
        private boolean enabled = true;
        /** 采样率（0.0 ~ 1.0，如 0.1 表示 10% 采样） */
        private double rate = 0.1;
        /** 需要采样的 logger 名称前缀列表 */
        private List<String> loggers = Arrays.asList(
                "org.springframework.web.filter",
                "org.nexus.gateway.interceptor"
        );

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public double getRate() {
            return rate;
        }

        public void setRate(double rate) {
            this.rate = rate;
        }

        public List<String> getLogger() {
            return loggers;
        }

        /** YAML 中 logging.sampling.loggers 映射到此 setter */
        public void setLoggers(List<String> loggers) {
            this.loggers = loggers;
        }
    }
}