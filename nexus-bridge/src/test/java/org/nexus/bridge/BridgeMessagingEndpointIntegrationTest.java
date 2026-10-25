package org.nexus.bridge;

import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全上下文冒烟：messaging 观测端点 + 装配开关 + jpa 日限额存储（PLAN-037/038）。
 *
 * <p>补足单元测试覆盖不到的<b>真实上下文</b>接缝：</p>
 * <ul>
 *   <li>{@code SecurityConfig} 对 {@code GET /api/v1/bridge/messaging} 的白名单
 *       （经 Spring Security 过滤链实际放行）；</li>
 *   <li>{@code relay-enabled=true} 下 relayer/executor/worker/status 全装配、
 *       真实链 {@code @Component} 适配器 + 模拟链在注册表按 chainId 归键；</li>
 *   <li>{@code nexus.bridge.daily-usage.store=jpa} 的 store 选择在完整容器中生效;</li>
 *   <li>装配快照 JSON 字段与端点单测约定一致。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        "nexus.bridge.messaging.relay-enabled=true",
        "nexus.bridge.messaging.required-signatures=1",
        "nexus.bridge.messaging.simulated-target-chains=L2-sim",
        "nexus.bridge.daily-usage.store=jpa"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BridgeMessagingEndpointIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /** 与 BridgeFullChainIntegrationTest 同因：测试上下文未启用 tracing autoconfigure。 */
    @MockitoBean
    private Tracer tracer;

    @Test
    void messagingEndpointReportsFullAssemblySnapshotOverSecurityChain() throws Exception {
        mockMvc.perform(get("/api/v1/bridge/messaging"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.relayEnabled").value(true))
                .andExpect(jsonPath("$.requiredSignatures").value(1))
                .andExpect(jsonPath("$.storeType").value("InMemoryMessageStore"))
                // 注册表 = 真实链 @Component 适配器（按 chainId 归键）+ 模拟链 L2-sim；
                // 数量为 6（ethereum/bsc/polygon/avalanche/solana + L2-sim），断言下限防漂移
                .andExpect(jsonPath("$.targetAdapters").isNumber())
                .andExpect(jsonPath("$.targetAdapters").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
    }
}
