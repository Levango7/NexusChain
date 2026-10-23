package org.nexus.bridge.messaging;

import org.nexus.bridge.repository.BridgeMessageRecordRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link MessageStore} 装配开关（PLAN-026 桥持久化）。
 *
 * <p>通过 {@code nexus.bridge.messaging.store} 选择实现：</p>
 * <ul>
 *   <li>{@code memory}（默认，与既有行为完全一致）— {@link InMemoryMessageStore}；
 *       状态不跨重启，仅供测试与单实例开发；</li>
 *   <li>{@code jpa} — {@link JdbcMessageStore}：消息、执行结果与 per 源链 nonce
 *       落库，重启后去重 / 顺序 / 追溯状态不丢。</li>
 * </ul>
 *
 * <p>两个条件互斥，同一时刻只有一个 {@link MessageStore} Bean 存在；
 * 生产环境（多实例 / 重启敏感）应配置 {@code jpa}。</p>
 *
 * @since 2.75.0
 */
@Configuration
public class MessageStoreConfig {

    /**
     * JPA 落库实现（nexus.bridge.messaging.store=jpa 时生效）。
     */
    @Bean
    @ConditionalOnProperty(name = "nexus.bridge.messaging.store", havingValue = "jpa")
    public MessageStore jdbcMessageStore(BridgeMessageRecordRepository repository) {
        return new JdbcMessageStore(repository);
    }

    /**
     * 内存实现（默认，保持既有行为）。
     */
    @Bean
    @ConditionalOnProperty(name = "nexus.bridge.messaging.store",
            havingValue = "memory", matchIfMissing = true)
    public MessageStore inMemoryMessageStore() {
        return new InMemoryMessageStore();
    }
}
