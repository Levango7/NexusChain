package org.nexus.gateway.risk.link;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.security.JwtTokenProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 风控联动端点的授权闭环（本次修复的红→绿探针）。
 *
 * <p><b>缺陷形状（现查 master 8965a2c8）：</b>三条写端点 {@code POST /freeze|/unfreeze|/status-change}
 * 只有类级 {@code @PreAuthorize("hasRole('ADMIN') or hasRole('MERCHANT')")}，且它们不在
 * {@code WebConfig} 的 ApiKeyInterceptor 排除名单里，于是必须带商户 API Key 才能进到控制器；
 * 控制器又用 {@link org.nexus.gateway.security.MerchantOwnershipGuard#requireMerchantId} 把目标商户
 * <b>取自调用方自己的 API Key</b>。合起来的实际语义是：</p>
 * <ol>
 *   <li>持商户凭据的一方可以走"风控联动"这条路<b>给自己的账户解冻 / 改状态</b>——
 *       {@code executeUnfreeze} 是真动钱（FROZEN→BALANCE），被处罚者能自助撤掉处罚；</li>
 *   <li>管理员持 JWT 而无商户 API Key 时，被 ApiKeyInterceptor 以 <b>401</b> 挡在控制器之外 ⇒
 *       这些"管理"端点对管理员本身不可用。审核那两条（approve/reject，方法级 ADMIN）同形。</li>
 * </ol>
 *
 * <p>同仓已有正确先例：{@code /api/v1/merchants/{id}/verify} 与 {@code .../api-keys}
 * 既从 ApiKeyInterceptor 排除、又标 {@code @PreAuthorize("hasRole('ADMIN')")}
 * （{@code MerchantAdminJwtAuthIT} 守着）。本类把同一形状钉到 risk-link 上。</p>
 *
 * <p>断言口径：判据只看**鉴权是否放行**（401/403 = 拒），不看业务结果——数据层因缺少 riskEventId
 * 而 4xx/5xx 都不算鉴权失败。这与 {@code MerchantAdminJwtAuthIT} 的写法一致，避免把
 * "授权修复"和"夹具里有没有这行数据"混成一谈。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@org.junit.jupiter.api.Tag("integration")
@DisplayName("风控联动端点授权闭环（商户不得自助撤处罚，管理员可达）")
class RiskLinkEnforcementAuthIT {

    private static final String API_KEY_HEADER = "X-NexusChain-ApiKey";
    private static final String MERCHANT_API_KEY = "nexus-it-risklink-key";
    private static final long MERCHANT_ID = 7001L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider tokenProvider;

    // sandbox 上下文缺 Tracer bean（同 MerchantAdminJwtAuthIT 的先例）
    @MockitoBean
    private io.micrometer.tracing.Tracer tracer;

    // ==================== 1. 商户不得再走这条路 ====================

    @Test
    @DisplayName("MERCHANT 凭据 → 解冻联动必须被方法级鉴权拒（master 上实测返回 200）")
    void merchantMustNotBeAbleToLiftItsOwnRiskFreeze() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/risk/link/unfreeze")
                        .header(API_KEY_HEADER, MERCHANT_API_KEY)
                        .header("Authorization", "Bearer " + jwt(List.of("MERCHANT")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":" + MERCHANT_ID + ",\"riskEventId\":\"RE-IT-1\",\"amount\":100,\"reason\":\"self lift\"}"))
                .andReturn();

        // 断成 403 这一个码：401 意味着这条路径被加回到 ApiKeyInterceptor 之后（管理员又被挡死），
        // 2xx/其它 4xx 意味着商户越权或校验顺序变化。带上合法 merchantId 是为了把"授权"与"参数校验"分开。
        assertEquals(403, result.getResponse().getStatus(),
                "商户不得对自己的账户走解冻联动（executeUnfreeze 真动钱 FROZEN→BALANCE），实际="
                        + describe(result));
    }

    @Test
    @DisplayName("MERCHANT 凭据 → 冻结与状态变更联动同样被拒（403，不是 401）")
    void merchantMustNotBeAbleToTriggerFreezeOrStatusChange() throws Exception {
        int freeze = mockMvc.perform(post("/api/v1/risk/link/freeze")
                .header(API_KEY_HEADER, MERCHANT_API_KEY)
                .header("Authorization", "Bearer " + jwt(List.of("MERCHANT")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":" + MERCHANT_ID + ",\"riskEventId\":\"RE-IT-2\",\"amount\":100,\"reason\":\"by merchant\"}")
        ).andReturn().getResponse().getStatus();
        assertEquals(403, freeze, "冻结联动不应由 MERCHANT 触发，实际=" + freeze);

        int statusChange = mockMvc.perform(post("/api/v1/risk/link/status-change")
                .header(API_KEY_HEADER, MERCHANT_API_KEY)
                .header("Authorization", "Bearer " + jwt(List.of("MERCHANT")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchantId\":" + MERCHANT_ID + ",\"riskEventId\":\"RE-IT-3\",\"linkAction\":\"STATUS_FROZEN\"}")
        ).andReturn().getResponse().getStatus();
        assertEquals(403, statusChange, "状态变更联动不应由 MERCHANT 触发，实际=" + statusChange);
    }

    // ==================== 2. 管理员要真够得着 ====================

    @Test
    @DisplayName("ADMIN JWT（无商户 API Key）→ 不应被 ApiKeyInterceptor 以 401 挡在控制器外")
    void adminWithoutMerchantApiKeyCanReachEnforcement() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/risk/link/freeze")
                        .header("Authorization", "Bearer " + jwt(List.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":" + MERCHANT_ID + ",\"riskEventId\":\"RE-IT-4\",\"amount\":100,\"reason\":\"admin action\"}"))
                .andReturn();

        int status = result.getResponse().getStatus();
        assertNotEquals(401, status,
                "管理端点不该要求商户 API Key（master 上实测就是 401 Missing API key），实际=" + describe(result));
        assertNotEquals(403, status, "ADMIN JWT 不应被方法级鉴权拒绝，实际=" + describe(result));
    }

    @Test
    @DisplayName("ADMIN JWT（无商户 API Key）→ 大额审核 approve/reject 可达（否则人工审核形同虚设）")
    void adminCanReachInterceptionReviewEndpoints() throws Exception {
        for (String action : new String[]{"approve", "reject"}) {
            MvcResult result = mockMvc.perform(post("/api/v1/risk/link/interceptions/1/" + action)
                            .header("Authorization", "Bearer " + jwt(List.of("ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewComment\":\"it-" + action + "\"}"))
                    .andReturn();
            int status = result.getResponse().getStatus();
            assertNotEquals(401, status, "审核端点被商户 API Key 拦截器挡死 ⇒ 人工审核不可用，action="
                    + action + " 实际=" + describe(result));
            assertNotEquals(403, status, "ADMIN JWT 应通过审核端点的方法级鉴权，action="
                    + action + " 实际=" + describe(result));
        }
    }

    // ==================== 3. 不变式（改前改后都该成立） ====================

    @Test
    @DisplayName("匿名请求 → 一律拒（401/403 都算拒），不能落到业务层")
    void anonymousIsAlwaysDenied() throws Exception {
        for (String path : new String[]{"/api/v1/risk/link/freeze", "/api/v1/risk/link/unfreeze",
                "/api/v1/risk/link/status-change", "/api/v1/risk/link/interceptions/1/approve"}) {
            MockHttpServletRequestBuilder builder = post(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"riskEventId\":\"RE-IT-5\",\"amount\":100,\"linkAction\":\"STATUS_FROZEN\"}");
            int status = mockMvc.perform(builder).andReturn().getResponse().getStatus();
            assertTrue(isUnauthorizedOrForbidden(status), "匿名不得进入联动业务层，path=" + path + " 实际=" + status);
        }
    }

    @Test
    @DisplayName("非 ADMIN 角色 JWT（READ）→ 联动端点仍被拒（最小权限不因本次修改而放宽）")
    void readOnlyRoleStaysDenied() throws Exception {
        int status = mockMvc.perform(post("/api/v1/risk/link/freeze")
                        .header(API_KEY_HEADER, MERCHANT_API_KEY)
                        .header("Authorization", "Bearer " + jwt(List.of("READ")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantId\":" + MERCHANT_ID + ",\"riskEventId\":\"RE-IT-6\",\"amount\":100}"))
                .andReturn().getResponse().getStatus();
        assertEquals(403, status, "READ 角色不应能触发冻结，实际=" + status);
    }

    @Test
    @DisplayName("ADMIN 触发冻结但没指名目标商户 → 400（绝不回退成调用方自己的商户）")
    void adminActionWithoutTargetMerchantFailsClosed() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/risk/link/freeze")
                        .header("Authorization", "Bearer " + jwt(List.of("ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskEventId\":\"RE-IT-7\",\"amount\":100,\"reason\":\"no target\"}"))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(),
                "目标商户缺失必须 400，不能默认成某个商户，实际=" + describe(result));
    }

    // ==================== 辅助 ====================

    private String jwt(List<String> roles) {
        // subject 用数字：approve/reject 的 resolveReviewerId() 会把 JWT subject 解析成 Long，
        // 非数字会让它退化成"取不到审核人"→400，掩盖我要证的恰恰是"鉴权是否放行"。
        return tokenProvider.generateToken("4201", roles, 60_000L);
    }

    private static boolean isUnauthorizedOrForbidden(int status) {
        return status == 401 || status == 403;
    }

    private String describe(MvcResult result) throws Exception {
        return "status=" + result.getResponse().getStatus()
                + " body=" + abbreviate(result.getResponse().getContentAsString());
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "<null>";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }
}
