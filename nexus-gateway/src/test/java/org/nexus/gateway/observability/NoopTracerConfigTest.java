package org.nexus.gateway.observability;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 不用 MockitoBean 顶替：sandbox 真实启动路径也必须能注入并创建 span。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("sandbox")
class NoopTracerConfigTest {

    @Autowired
    private Tracer tracer;

    @Test
    void sandboxProfileProvidesUsableTracer() {
        assertNotNull(tracer);
        assertNotNull(tracer.nextSpan());
    }
}
