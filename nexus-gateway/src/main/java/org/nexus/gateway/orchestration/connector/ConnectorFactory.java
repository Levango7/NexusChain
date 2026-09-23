package org.nexus.gateway.orchestration.connector;

import org.nexus.gateway.orchestration.connectors.AlipayConnector;
import org.nexus.gateway.orchestration.connectors.DynamicHttpPspConnector;
import org.nexus.gateway.orchestration.connectors.WeChatPayConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Connector 工厂 — 根据 {@link ConnectorConfig#getType()} 创建对应的 {@link PaymentConnector} 实例。
 *
 * <p>支持的类型：</p>
 * <ul>
 *   <li>{@code http_psp} → {@link DynamicHttpPspConnector}（使用 JDK HttpClient，无需 RestTemplate）</li>
 *   <li>{@code wechat} → {@link WeChatPayConnector}（通过反射设置 @Value 字段，非 Spring 管理实例）</li>
 *   <li>{@code alipay} → {@link AlipayConnector}（通过反射设置 @Value 字段，非 Spring 管理实例）</li>
 * </ul>
 *
 * <p>对于 wechat/alipay 类型，动态注册的场景是多商户：运维通过 API 注册一个"额外"的
 * 微信/支付宝连接器（不同的 app_id/mch_id），用于多商户场景。此时创建一个新的非 Spring
 * 管理的实例，用数据库配置初始化，然后注册到 {@link ConnectorRegistry}（使用不同的 ID）。</p>
 *
 * <p>敏感信息通过环境变量引用：wechat 的 {@code apiKeyEnv} 引用环境变量名存储 api-key，
 * alipay 的 {@code merchantPrivateKey}/{@code alipayPublicKey} 同理。实际值通过
 * {@code System.getenv()} 在运行时解析。</p>
 */
@Component
public class ConnectorFactory {

    private static final Logger log = LoggerFactory.getLogger(ConnectorFactory.class);

    private final RestTemplate restTemplate;

    public ConnectorFactory(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 根据 {@link ConnectorConfig} 创建对应的 {@link PaymentConnector} 实例。
     *
     * @param config 连接器配置
     * @return 创建的 PaymentConnector 实例
     * @throws IllegalArgumentException 不支持的类型
     */
    public PaymentConnector create(ConnectorConfig config) {
        String type = config.getType();
        return switch (type) {
            case "http_psp" -> createHttpPsp(config);
            case "wechat" -> createWeChat(config);
            case "alipay" -> createAlipay(config);
            default -> throw new IllegalArgumentException("Unsupported connector type: " + type);
        };
    }

    /**
     * 创建 {@link DynamicHttpPspConnector} 实例。
     */
    private PaymentConnector createHttpPsp(ConnectorConfig config) {
        Set<String> currencies = parseCurrencies(config.getCurrencies());
        return new DynamicHttpPspConnector(
                config.getId(),
                config.getDisplayName(),
                config.getBaseUrl(),
                config.getApiKeyEnv(),
                currencies,
                config.getFeeBps()
        );
    }

    /**
     * 创建 {@link WeChatPayConnector} 实例（非 Spring 管理）。
     *
     * <p>使用无参构造器创建实例，通过反射设置 @Value 字段：
     * apiKey（从 apiKeyEnv 环境变量解析）、appId、mchId、enabled=true、apiBase。</p>
     */
    private PaymentConnector createWeChat(ConnectorConfig config) {
        WeChatPayConnector connector = new WeChatPayConnector(restTemplate);

        // apiKey：从 apiKeyEnv 引用的环境变量中解析实际值
        String apiKey = resolveEnvVar(config.getApiKeyEnv());
        setField(connector, "apiKey", apiKey);
        setField(connector, "appId", config.getAppId());
        setField(connector, "mchId", config.getMchId());
        setField(connector, "enabled", true);
        setField(connector, "apiBase", "https://api.mch.weixin.qq.com");

        log.info("Created dynamic WeChatPayConnector: id={}, appId={}, mchId={}",
                config.getId(), config.getAppId(), config.getMchId());
        return connector;
    }

    /**
     * 创建 {@link AlipayConnector} 实例（非 Spring 管理）。
     *
     * <p>使用无参构造器创建实例，通过反射设置 @Value 字段：
     * appId、merchantPrivateKey（从环境变量解析）、alipayPublicKey（从环境变量解析）、
     * enabled=true、apiBaseUrl。</p>
     */
    private PaymentConnector createAlipay(ConnectorConfig config) {
        AlipayConnector connector = new AlipayConnector(restTemplate);

        // merchantPrivateKey 和 alipayPublicKey：从环境变量引用中解析实际值
        String merchantPrivateKey = resolveEnvVar(config.getMerchantPrivateKey());
        String alipayPublicKey = resolveEnvVar(config.getAlipayPublicKey());
        setField(connector, "appId", config.getAppId());
        setField(connector, "merchantPrivateKey", merchantPrivateKey);
        setField(connector, "alipayPublicKey", alipayPublicKey);
        setField(connector, "enabled", true);
        setField(connector, "apiBaseUrl", "https://openapi.alipay.com/gateway.do");

        log.info("Created dynamic AlipayConnector: id={}, appId={}",
                config.getId(), config.getAppId());
        return connector;
    }

    /**
     * 解析环境变量引用：输入为环境变量名，输出为 {@code System.getenv(name)} 的值。
     * 若环境变量名 为 null/空 或未设置，返回 null。
     */
    private String resolveEnvVar(String envVarName) {
        if (envVarName == null || envVarName.isBlank()) {
            return null;
        }
        return System.getenv(envVarName);
    }

    /**
     * 通过反射设置 private 字段值（包括 @Value 注解字段）。
     */
    private void setField(Object target, String fieldName, Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            log.warn("Failed to set field '{}' on {}: {}", fieldName, target.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * 在类层次结构中查找字段（包括父类）。
     */
    private Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                // 继续查找父类
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    /**
     * 解析逗号分隔的货币列表。
     */
    private Set<String> parseCurrencies(String currencies) {
        if (currencies == null || currencies.isBlank()) {
            return Set.of();
        }
        return new HashSet<>(Arrays.asList(currencies.split(",")));
    }
}