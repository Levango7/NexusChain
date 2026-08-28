package org.nexus.bridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4.0 Jackson 2.x 兼容配置。
 *
 * <p><b>背景</b>：Spring Boot 4.0 将 JSON 处理从 Jackson 2.x
 * ({@code com.fasterxml.jackson.databind.ObjectMapper}) 迁移到 Jackson 3.x
 * ({@code tools.jackson.databind.json.JsonMapper})。{@code JacksonAutoConfiguration}
 * 现在只创建 {@code JsonMapper} bean，不再自动创建 {@code ObjectMapper} bean。</p>
 *
 * <p>本项目 {@link org.nexus.bridge.saga.BridgeSagaCoordinator} 等组件仍通过
 * 构造函数注入 Jackson 2.x {@code ObjectMapper}。此配置类提供兼容 bean，
 * 使其在 Spring Boot 4.0 下继续可用。</p>
 *
 * <p><b>生命周期</b>：项目完成 Jackson 3.x 迁移后，本类应删除。</p>
 *
 * @since 2.30.0（Spring Boot 4.0.8 升级）
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}