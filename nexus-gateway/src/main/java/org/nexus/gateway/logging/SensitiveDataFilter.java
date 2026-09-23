package org.nexus.gateway.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感数据脱敏过滤器（任务 #28）。
 *
 * <p>检测日志消息中的敏感字段并脱敏，支持两种格式：</p>
 * <ul>
 *   <li>JSON 字段：{@code "password":"value"} 或 {@code "password": "value"}</li>
 *   <li>key=value 格式：{@code password=value}</li>
 * </ul>
 *
 * <p>脱敏规则：</p>
 * <ul>
 *   <li>值长度 > 12：保留前4位 + **** + 后4位（如 {@code sk_live_abcdef1234567890} → {@code sk_l****7890}）</li>
 *   <li>值长度 <= 12：全部替换为 ****</li>
 * </ul>
 *
 * <p>敏感字段列表可通过 {@link #setFields} 配置，默认包含：
 * password, secret, apiKey, api_key, privateKey, private_key, token, hmac, signature, mchId, appId</p>
 *
 * <p>注意：Logback {@link Filter} 的 {@code decide} 方法只能返回 ACCEPT/DENY/NEUTRAL，
 * 无法直接修改日志事件内容。因此本 Filter 的 {@code decide} 始终返回 NEUTRAL，
 * 实际脱敏通过 {@link #maskSensitiveData(String)} 公共方法实现，
 * 由 {@link StructuredLogConfig} 编程式注入的自定义 Layout 调用。</p>
 */
public class SensitiveDataFilter extends Filter<ILoggingEvent> {

    private List<String> fields = List.of(
            "password", "secret", "apiKey", "api_key",
            "privateKey", "private_key", "token", "hmac",
            "signature", "mchId", "appId"
    );

    private String maskPattern = "****";

    /** 编译后的正则模式缓存 */
    private List<Pattern> jsonPatterns = new ArrayList<>();
    private List<Pattern> kvPatterns = new ArrayList<>();

    /**
     * 初始化：编译正则模式。
     */
    @Override
    public void start() {
        compilePatterns();
        super.start();
    }

    /**
     * 编译 JSON 字段和 key=value 格式的正则模式。
     */
    private void compilePatterns() {
        jsonPatterns.clear();
        kvPatterns.clear();
        for (String field : fields) {
            // JSON 格式："field":"value" 或 "field": "value"
            String jsonRegex = "\"(" + Pattern.quote(field) + ")\"\\s*:\\s*\"([^\"]*)\"";
            jsonPatterns.add(Pattern.compile(jsonRegex, Pattern.CASE_INSENSITIVE));

            // key=value 格式：field=value（值到空格、逗号、分号、引号或行尾结束）
            String kvRegex = "(" + Pattern.quote(field) + ")\\s*=\\s*([^\\s,;\"]+)";
            kvPatterns.add(Pattern.compile(kvRegex, Pattern.CASE_INSENSITIVE));
        }
    }

    /**
     * Filter 决策方法。始终返回 NEUTRAL，不阻止任何日志输出。
     *
     * <p>实际脱敏由 {@link #maskSensitiveData(String)} 方法执行，
     * 通过 {@link StructuredLogConfig} 注入的自定义 Layout 在格式化阶段调用。</p>
     */
    @Override
    public FilterReply decide(ILoggingEvent event) {
        return FilterReply.NEUTRAL;
    }

    /**
     * 对消息中的敏感数据进行脱敏处理。
     *
     * <p>此方法为公共方法，可被自定义 Layout/Encoder 调用，
     * 也可被单元测试直接验证。</p>
     *
     * @param message 原始日志消息
     * @return 脱敏后的日志消息
     */
    public String maskSensitiveData(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String result = message;

        // 处理 JSON 格式："field":"value"
        for (Pattern pattern : jsonPatterns) {
            Matcher matcher = pattern.matcher(result);
            result = matcher.replaceAll(mr ->
                    "\"" + mr.group(1) + "\":\"" + maskValue(mr.group(2)) + "\""
            );
        }

        // 处理 key=value 格式：field=value
        for (Pattern pattern : kvPatterns) {
            Matcher matcher = pattern.matcher(result);
            result = matcher.replaceAll(mr ->
                    mr.group(1) + "=" + maskValue(mr.group(2))
            );
        }

        return result;
    }

    /**
     * 对单个值进行脱敏处理。
     *
     * <p>脱敏规则：</p>
     * <ul>
     *   <li>值长度 > 12：保留前4位 + **** + 后4位</li>
     *   <li>值长度 <= 12：全部替换为 ****</li>
     * </ul>
     *
     * @param value 原始值
     * @return 脱敏后的值
     */
    private String maskValue(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() > 12) {
            return value.substring(0, 4) + maskPattern + value.substring(value.length() - 4);
        }
        return maskPattern;
    }

    /**
     * 设置敏感字段列表，设置后需重新编译正则模式。
     */
    public void setFields(List<String> fields) {
        this.fields = fields;
        if (isStarted()) {
            compilePatterns();
        }
    }

    /**
     * 获取敏感字段列表。
     */
    public List<String> getFields() {
        return fields;
    }

    /**
     * 设置脱敏替换字符。
     */
    public void setMaskPattern(String maskPattern) {
        this.maskPattern = maskPattern;
    }

    /**
     * 获取脱敏替换字符。
     */
    public String getMaskPattern() {
        return maskPattern;
    }
}
