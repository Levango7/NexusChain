package org.nexus.gateway.config;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 完整 dev 启动路径上的 CORS 预检与 Tracer 装配回归。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WebConfigCorsPreflightTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private Tracer tracer;

    @Test
    void preflightNeedsNoBusinessCredentials() throws Exception {
        mvc.perform(options("/api/v1/payments")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,
                                "X-NexusChain-ApiKey,X-NexusChain-Signature,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsString("X-NexusChain-ApiKey")));
    }

    @Test
    void actualPostStillRequiresBusinessCredentials() throws Exception {
        mvc.perform(post("/api/v1/payments")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void devProfileProvidesUsableTracer() {
        assertNotNull(tracer, "dev 无 Zipkin 时也必须能启动并注入 Tracer");
        assertNotNull(tracer.nextSpan(), "no-op Tracer 仍须支持业务创建 span");
    }
}
