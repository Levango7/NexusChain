package org.nexus.gateway.observability;

import brave.Tracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Dev/DAST 环境 no-op Tracer bean 配置。
 *
 * <p>Spring Boot 4.0 将 tracing autoconfig 从 spring-boot-actuator-autoconfigure 移到
 * spring-boot-zipkin 模块。dev/DAST 环境不引入 spring-boot-starter-zipkin（避免
 * 单元测试 @MockitoBean 冲突），导致 Tracer bean 缺失，PaymentServiceImpl 等构造器
 * 注入失败。</p>
 *
 * <p>本配置在 dev profile 下提供基于 Brave 的 no-op Tracer bean：
 * <ul>
 *   <li>Brave Tracing.newBuilder().build() 创建不上报的 tracing 实例</li>
 *   <li>BraveTracer 包装为 Micrometer Tracer 接口</li>
 *   <li>@ConditionalOnMissingBean 确保生产环境有真实 Tracer 时不覆盖</li>
 * </ul></p>
 *
 * <p>BusinessSpan.start(tracer, ...) 正常工作，span 不上报到 Zipkin（dev 环境无 Zipkin server）。</p>
 */
@Configuration
@Profile("dev")
public class NoopTracerConfig {

    @Bean
    @ConditionalOnMissingBean(Tracer.class)
    public Tracer noopTracer() {
        Tracing tracing = Tracing.newBuilder().build();
        return new BraveTracer(tracing.tracer(), tracing.currentTraceContext());
    }
}