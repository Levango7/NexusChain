package org.nexus.signing.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.signing.approval.SigningApprovalRequest;
import org.nexus.signing.approval.SigningApprovalService;
import org.nexus.signing.audit.AuditLogService;
import org.nexus.signing.config.JwtTokenProvider;
import org.nexus.signing.mpc.MpcApprovalPolicy;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 审批决策端点的鉴权闭环（#50，真实 HTTP + 真实过滤器链 + 真实方法级鉴权）。
 *
 * <p>为什么不能只靠 {@link SigningApprovalControllerTest}：那个类是 standalone MockMvc，
 * 不加载 Spring Security——它验的是控制器返回什么码，验不了"这个角色到底进不进得来"。
 * {@code @PreAuthorize} 拼错、roles claim 解析对不上、{@code JwtAuthenticationFilter}
 * 没接进链这三类缺陷，只有在真实链路上才照得出来。</p>
 *
 * <p>用 JDK 自带 {@link HttpClient} 打真端口而不是 MockMvc/TestRestTemplate：
 * signing-service 的测试 classpath 里没有 Boot 4 拆分后的
 * {@code spring-boot-starter-webmvc-test}（{@code @AutoConfigureMockMvc} 与
 * {@code TestRestTemplate} 都在那边）。为一份测试加一个测试依赖不值得，
 * 而随机端口跑真 servlet 容器本来就更接近生产。</p>
 *
 * <p>四类输入各自的判据（每条都要求"红因可归因"，光看状态码不够——白名单和角色闸门
 * 都给 403，上一版就因此被骗：把 {@code @PreAuthorize} 整行删掉，5 条断言全绿）：</p>
 * <ol>
 *   <li>无凭据 ⇒ 拒（401 或 403 都算拒——钉的是"不放行"）；</li>
 *   <li>名单内主体 + 只有 READ 角色 ⇒ 403，且响应里<b>不能</b>出现 {@code denied_reason}
 *       （它只由控制器写出 ⇒ 出现即说明请求已进到方法体，角色闸门失效），并且不留记账；</li>
 *   <li>SIGNER + 名单内主体 ⇒ 200，两个主体各批一次才到 APPROVED
 *       （这就是 #50 补的入口，顺带钉住法定数真的生效）；</li>
 *   <li>OPERATOR + 名单外主体 ⇒ 403 且 {@code denied_reason=approver_not_in_whitelist}
 *       ——钉的是"过角色 ≠ 能下决策"：服务间 token 的 subject 是服务身份，不该能批钱。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "nexus.security.jwt.secret=it-approval-decision-secret-0123456789abcdef",
        "nexus.approval.approver-whitelist=ops-alice@nexus,ops-bob@nexus",
        "nexus.approval.store-type=memory"
})
@ActiveProfiles("test")
@DisplayName("审批决策端点鉴权闭环（角色闸门 + 白名单双层）")
class SigningApprovalDecisionAuthIT {

    private static final String WHITELISTED_SUBJECT = "ops-alice@nexus";
    private static final String SECOND_WHITELISTED_SUBJECT = "ops-bob@nexus";

    @Autowired
    private Environment environment;

    @Autowired
    private JwtTokenProvider tokenProvider;

    @Autowired
    private SigningApprovalService approvalService;

    @MockitoBean
    private CggmpMpcCryptoEngine cryptoEngine;

    @MockitoBean
    private MpcEngineRouter engineRouter;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private MpcApprovalPolicy mpcApprovalPolicy;

    /**
     * 必须打桩：{@code Leveldb} 是 {@code @Component} + {@code @PostConstruct openDb()}，
     * 数据目录是写死的 {@code user.dir/leveldb}（static final）。真 bean 一被实例化，
     * 本上下文就把文件锁占住了——同一个测试 JVM 里后面的 {@code LeveldbTest}
     * （直构 {@code new Leveldb()}）全部报 {@code OverlappingFileLockException}
     * （实测：加上本 IT 后 5 条用例转红，把 {@code Leveldb} 换成 mock 即恢复）。
     * 审批决策这条链本来也不碰 nonce 池，mock 掉不损失任何被验性质。
     */
    @MockitoBean
    private org.nexus.signing.storage.Leveldb leveldb;

    @Test
    @DisplayName("无凭据 → 决策端点必须被拒")
    void anonymousCannotDecide() throws Exception {
        HttpResult result = decide(pendingId(), "{\"decision\":\"APPROVE\"}", null);

        assertTrue(result.status() == 401 || result.status() == 403,
                () -> "匿名请求必须被拒（401 或 403），实际=" + result.status()
                        + " body=" + result.body());
    }

    @Test
    @DisplayName("名单内主体但只有 READ 角色 → 403，且这条 403 由角色闸门给出")
    void whitelistedSubjectWithoutSigningRoleIsRefused() throws Exception {
        String rid = pendingId();

        HttpResult result = decide(rid, "{\"decision\":\"APPROVE\"}",
                token(SECOND_WHITELISTED_SUBJECT, List.of("READ")));

        assertEquals(403, result.status(),
                () -> "READ 不该能下审批决策，body=" + result.body());
        // 单看 403 不足以证明角色闸门在工作：白名单也会给 403（上一版就是这么被骗过去的——
        // 把 @PreAuthorize 整行删掉，5 条断言照样全绿）。denied_reason 只可能由控制器写出，
        // 它一出现就说明请求已经进到方法体里。
        assertFalse(result.body().contains("denied_reason"),
                () -> "这条 403 必须来自角色闸门；响应里出现 denied_reason 说明请求已经进到控制器"
                        + "（@PreAuthorize 失效），body=" + result.body());
        assertFalse(approvalService.getRequest(rid).getApprovals().contains(SECOND_WHITELISTED_SUBJECT),
                "被角色闸门拒掉的决策不能留下任何记账痕迹");
    }

    @Test
    @DisplayName("SIGNER + 名单内主体 → 200；达到法定数（2）才推到 APPROVED")
    void whitelistedSignersReachQuorum() throws Exception {
        String rid = pendingId();

        HttpResult first = decide(rid, "{\"decision\":\"APPROVE\"}",
                token(WHITELISTED_SUBJECT, List.of("SIGNER")));
        assertEquals(200, first.status(),
                () -> "名单内的 SIGNER 必须能批——这正是 #50 补的入口，body=" + first.body());
        assertEquals(SigningApprovalRequest.Status.PENDING, approvalService.getRequest(rid).getStatus(),
                "min-required-approvers=2，一个人批完不该就放行");

        HttpResult second = decide(rid, "{\"decision\":\"APPROVE\"}",
                token(SECOND_WHITELISTED_SUBJECT, List.of("SIGNER")));
        assertEquals(200, second.status(), () -> "body=" + second.body());

        SigningApprovalRequest after = approvalService.getRequest(rid);
        assertEquals(SigningApprovalRequest.Status.APPROVED, after.getStatus(),
                "两个名单内主体各批一次，必须真的到 APPROVED（HTTP 200 之外还要落到状态机）");
        assertTrue(after.getApprovals().contains(WHITELISTED_SUBJECT)
                        && after.getApprovals().contains(SECOND_WHITELISTED_SUBJECT),
                "记账的审批人必须是两个 JWT subject，实际=" + after.getApprovals());
    }

    @Test
    @DisplayName("OPERATOR + 名单外主体 → 403 + approver_not_in_whitelist（过角色不等于能决策）")
    void serviceIdentityPassesRoleButNotWhitelist() throws Exception {
        HttpResult result = decide(pendingId(), "{\"decision\":\"APPROVE\"}",
                token("nexus-gateway", List.of("OPERATOR")));

        assertEquals(403, result.status(), () -> "服务间 token 的主体不该能批钱，body=" + result.body());
        assertTrue(result.body().contains("approver_not_in_whitelist"),
                () -> "拒绝理由必须可区分并进响应体，body=" + result.body());
    }

    @Test
    @DisplayName("请求体里的 approver 字段不生效：身份只认 token 的 sub")
    void bodyCannotChooseTheApprover() throws Exception {
        String rid = pendingId();

        HttpResult result = decide(rid, "{\"decision\":\"APPROVE\",\"approver\":\"mallory@nexus\"}",
                token(WHITELISTED_SUBJECT, List.of("SIGNER")));

        assertEquals(200, result.status(), () -> "body=" + result.body());
        assertEquals(List.of(WHITELISTED_SUBJECT), List.copyOf(approvalService.getRequest(rid).getApprovals()),
                "自填的审批人绝不能被记账");
    }

    // ==================== 辅助 ====================

    /** 真实 HTTP 往返的最小结果（状态码 + 正文，正文在 4xx 上也要能读）。 */
    private record HttpResult(int status, String body) {
    }

    /** 用真实服务创建一条待审请求（金额过大额阈值），返回其 ID。 */
    private String pendingId() {
        when(mpcApprovalPolicy.getRequiredApprovers(any(), any())).thenReturn(1);
        String rid = approvalService.createApprovalRequest("pkA", "pkB",
                new BigDecimal("50000"), "USDT", "it-initiator", "10.0.0.0");
        if (rid == null) {
            throw new IllegalStateException("夹具没能创建出待审请求（检查大额阈值配置）");
        }
        return rid;
    }

    private HttpResult decide(String requestId, String json, String bearerToken) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port
                        + "/api/v1/signing/approvals/" + requestId + "/decision"))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new HttpResult(response.statusCode(), response.body());
    }

    private String token(String subject, List<String> roles) {
        return tokenProvider.generateToken(subject, roles);
    }
}
