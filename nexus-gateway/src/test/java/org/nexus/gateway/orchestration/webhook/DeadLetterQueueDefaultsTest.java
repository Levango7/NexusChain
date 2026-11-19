package org.nexus.gateway.orchestration.webhook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 缺省死信存储的 fail-safe 判据（2026-11-19 审计复核）。
 *
 * <p>防止回归为「裸跑无 Kafka 也选中 Kafka」：那才是第一次投递才失败的
 * fail-open 形态，比显式选内存更隐蔽。</p>
 */
class DeadLetterQueueDefaultsTest {

    @Test
    @DisplayName("base application.yml 缺省走内存 fail-safe（生产用 env 显式 opt-in Kafka）")
    void bareBootDefaultsToMemoryFailSafe() throws IOException {
        PropertySource<?> props = new YamlPropertySourceLoader()
                .load("base", new ClassPathResource("application.yml")).get(0);
        assertEquals("${NEXUS_WEBHOOK_DLQ_STORE:memory}", props.getProperty("nexus.webhook.dlq.store"),
                "base 缺省必须是可安全启动的内存 DLQ，不得默认 Kafka");
    }

    @Test
    @DisplayName("Kafka 实现只在 store=kafka 时装配，不再 matchIfMissing 隐式回退")
    void kafkaImplementationIsExplicitOptInOnly() {
        ConditionalOnProperty condition = DeadLetterQueueService.class
                .getAnnotation(ConditionalOnProperty.class);
        assertNotNull(condition, "Kafka DLQ 的条件装配注解丢失");
        assertFalse(condition.matchIfMissing(),
                "缺省属性不得隐式选中 Kafka（否则会回到裸跑无 broker 即失败的形态）");
    }
}
