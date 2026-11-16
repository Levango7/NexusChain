package org.nexus.gateway.attack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.model.Merchant;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.security.RequestSignatureInterceptor;
import org.nexus.gateway.service.MerchantServiceImpl;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * B-13 本机子集：API 层「真实攻击载荷」电池（zcode 2026-11-16）。
 *
 * <p><b>立意</b>：既有的 negative test 打的多是「缺字段 / 签名错 / 未认证」这类
 * <b>合成拒绝</b>；ROI 清单 B-13 要求的是「以真实攻击载荷打 API 层」。本类把
 * 攻击载荷固定成一张表，逐条打到真链路（{@code ApiKeyInterceptor → RequestSignatureInterceptor
 * → PaymentController → MerchantOwnershipGuard → OrderService}）上，断言三条<b>不变量</b>：</p>
 * <ol>
 *   <li><b>绝不 5xx</b>：攻击载荷不得把服务端打成 500（形态错误必须是 4xx）；</li>
 *   <li><b>不泄堆栈</b>：响应体不得含 {@code Caused by} / {@code at org.nexus.} / {@code \tat }
 *       等运行时痕迹（许多「信息泄露」事故的第一现场就是异常文本）；</li>
 *   <li><b>结构性非法的载荷不得到达业务服务</b>：被拒绝的请求不应调用
 *       {@link OrderService#createOrder}（否则等于把畸形数据透传给下游）。</li>
 * </ol>
 *
 * <p><b>为什么用全上下文</b>：{@code standaloneSetup} 不起 Spring 的 Validator，
 * {@code @Valid @RequestBody} 会被静默跳过 ⇒ 判据不具代表性。本类用
 * {@code @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("sandbox")}（H2），
 * 只 mock 两处：商户查询（让 interceptor 放行）与订单服务（让载荷止步于边界）。</p>
 *
 * <p><b>为什么放在 {@code org.nexus.gateway.attack} 而不是 {@code ...security}</b>：
 * ArchUnit 规则 {@code layer_dependencies}（切片无环）会连测试类一起扫。本类需要按
 * <b>具体类</b> mock {@link MerchantServiceImpl}，那是 {@code security} 切片 → {@code service}
 * 切片的新边，会闭合既有长环（实测 26 处违规、违规文本里本类名出现 156 次）。
 * 放进独立的新切片 {@code attack}（无任何反向依赖）⇒ 结构上不可能成环，
 * 也不必给分层门加豁免（**不加豁免、不弱化门禁**）。</p>
 *
 * <p><b>已知会暴露的形态（先登记，别当成测试写错）</b>：
 * 修复前 {@code GlobalExceptionHandler} 没有 {@code MethodArgumentNotValidException} 与
 * {@code HttpMessageNotReadableException} 处理器 ⇒ Bean Validation 失败与畸形 JSON
 * 都会落到 {@code Exception} 兜底 ⇒ 500。修复后为 400（见 PLAN-085 §四.1）。</p>
 *
 * <p><b>凭据纪律</b>：商户 API Key 不写字面量——每个用例在 setUp 里用
 * {@link UUID#randomUUID()} 现生成一个未登记的值（既满足安全约束，也让
 * 「查不到的 key 必然 401」这条对照更硬）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("sandbox")
@ExtendWith(MockitoExtension.class)
@Tag("integration")
@DisplayName("B-13：API 层真实攻击载荷电池（不变量：不 5xx / 不泄堆栈 / 不达业务）")
class ApiAttackPayloadBatteryIT {

    private static final String ORDERS_URI = "/api/v1/orders";
    private static final String API_KEY_HEADER = "X-NexusChain-ApiKey";
    private static final String TIMESTAMP_HEADER = "X-NexusChain-Timestamp";
    private static final String NONCE_HEADER = "X-NexusChain-Nonce";
    private static final String SIGNATURE_HEADER = "X-NexusChain-Signature";

    /** 合法基线请求（除攻击字段外的部分全部合法），保证「只有攻击字段不同」。 */
    private static final String VALID_BASE =
            "{\"merchantId\":\"100\",\"amount\":100,\"notifyUrl\":\"https://merchant.example/notify\"}";

    @Autowired
    private MockMvc mockMvc;

    /**
     * 按<b>具体类</b>而非接口 mock：{@code defaultSettlementService} 等构造参数是按
     * {@link MerchantServiceImpl} 具体类型注入的；若 mock 接口 {@code MerchantService}，
     * 同名 bean 被换成接口 mock ⇒ 报 BeanNotOfRequiredTypeException（本轮实测）。
     * 具体类 mock 同时满足接口方（{@code ApiKeyInterceptor} 持 {@code MerchantService}）。
     */
    @MockitoBean
    private MerchantServiceImpl merchantService;

    @MockitoBean
    private OrderService orderService;

    /** 测试上下文未启用 tracing autoconfigure；PaymentServiceImpl 构造需要 Tracer。 */
    @MockitoBean
    private Tracer tracer;

    /**
     * 请求签名共享密钥——从 Spring {@code Environment}/{@code @Value} 注入
     * （sandbox profile 已配 {@code nexus.security.requestSigningSecret}）。
     * <b>不写死在测试源码里</b>：凭据一律来自配置而非代码字面量。
     */
    @org.springframework.beans.factory.annotation.Value("${nexus.security.requestSigningSecret:}")
    private String signingSecret;

    private Merchant verifiedMerchant;
    private String testApiKey;

    @BeforeEach
    void setUp() {
        testApiKey = "b13-" + UUID.randomUUID();
        verifiedMerchant = new Merchant();
        verifiedMerchant.setId(100L);
        verifiedMerchant.setMerchantCode("M-100");
        verifiedMerchant.setVerificationStatus(Merchant.VerificationStatus.VERIFIED);
        Mockito.lenient().when(merchantService.findByApiKey(testApiKey))
                .thenReturn(Optional.of(verifiedMerchant));
        PaymentOrder stored = new PaymentOrder();
        stored.setId(1L);
        stored.setOrderNo("NEX-B13-001");
        stored.setMerchantId(100L);
        stored.setAmount(new BigDecimal("100"));
        stored.setStatus(PaymentOrder.OrderStatus.PENDING);
        Mockito.lenient().when(orderService.createOrder(Mockito.any(CreateOrderRequest.class)))
                .thenReturn(stored);
    }

    // ==================== 不变量断言助手 ====================

    /** 不变量 1+2：不 5xx、不泄运行时痕迹。返回状态码便于逐例再断言。 */
    private static int assertAttackNeverBreaksContract(MvcResult result, String caseName) throws Exception {
        int st = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertTrue(st < 500,
                "[" + caseName + "] 攻击载荷不得打成 5xx（实测 " + st + "）；body=" + body);
        assertFalse(body.contains("Caused by"),
                "[" + caseName + "] 响应体不得含 Caused by（异常链泄露）");
        assertFalse(body.contains("at org.nexus."),
                "[" + caseName + "] 响应体不得含 org.nexus 栈帧");
        assertFalse(body.contains("\tat "),
                "[" + caseName + "] 响应体不得含 tab 缩进栈帧");
        return st;
    }

    /** 不变量 3：结构性非法的载荷不得到达业务服务。 */
    private void assertNeverReachedOrderService() {
        Mockito.verifyNoInteractions(orderService);
    }

    /**
     * 打真链路：API Key + HMAC 请求签名（v2 canonical，长度前缀）。
     *
     * <p><b>为什么必须签名</b>：第一版电池没带签名头，所有用例拿到的都是
     * {@code 401 Missing request signature}——9 个用例「通过」其实是<b>假绿</b>
     * （测的是 {@code RequestSignatureInterceptor} 而不是控制器）。签名 = 让载荷
     * 真正到达 {@code PaymentController}，否则整组不变量毫无意义。</p>
     *
     * <p>签名用拦截器自己的公开静态实现 {@link RequestSignatureInterceptor#computeSignatureV2}
     * 计算（「被测代码自己算」是可接受的口径：这里验的是<b>拦截之后的链路</b>，
     * 签名算得对不对由 {@code RequestSignatureInterceptorTest} 单独钉住）。</p>
     */
    private MvcResult fire(String jsonBody) throws Exception {
        String nonce = "n-" + UUID.randomUUID();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = RequestSignatureInterceptor.computeSignatureV2(
                timestamp, nonce, "POST", ORDERS_URI, jsonBody, signingSecret);
        return mockMvc.perform(post(ORDERS_URI)
                        .header(API_KEY_HEADER, testApiKey)
                        .header(TIMESTAMP_HEADER, timestamp)
                        .header(NONCE_HEADER, nonce)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonBody))
                .andReturn();
    }

    private static String withField(String json, String field, String rawValue) {
        // 合法基线上追加一个字段（rawValue 已是 JSON 字面量，便于注入类型混淆）
        String head = json.substring(0, json.length() - 1); // 去掉末尾 }
        return head + ",\"" + field + "\":" + rawValue + "}";
    }

    // ==================== 0. 基线自证（先证明合法请求真能通） ====================

    @Test
    @DisplayName("基线：合法请求 201 且被服务端覆盖 merchantId（证明链路通、证明 mass-assignment 被挡）")
    void validBaseline_created201_andMerchantIdOverridden() throws Exception {
        MvcResult r = fire(VALID_BASE);
        assertEquals(201, r.getResponse().getStatus(),
                "基线必须 201；body=" + r.getResponse().getContentAsString());

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        Mockito.verify(orderService).createOrder(captor.capture());
        assertEquals("100", captor.getValue().getMerchantId(),
                "merchantId 必须由服务端按认证身份覆盖（body 里伪造他人身份无效）");
    }

    // ==================== 1. SQL 注入串 ====================

    @Test
    @DisplayName("SQL 注入串：' OR 1=1-- / DROP TABLE / UNION SELECT → 不 5xx、不泄堆栈")
    void sqlInjectionStrings_neverBreakContract() throws Exception {
        String[] payloads = {
                "' OR 1=1--",
                "'; DROP TABLE payment_order;--",
                "1' UNION SELECT NULL,api_key FROM merchant--",
                "\" OR \"\"=\"",
                "admin'--"
        };
        for (int i = 0; i < payloads.length; i++) {
            String escaped = payloads[i].replace("\\", "\\\\").replace("\"", "\\\"");
            String json = withField(VALID_BASE, "description", "\"" + escaped + "\"");
            MvcResult r = fire(json);
            assertAttackNeverBreaksContract(r, "sqli#" + i);
        }
    }

    // ==================== 2. 日志伪造 / CRLF ====================

    @Test
    @DisplayName("CRLF/日志伪造：\\r\\n 伪造告警行、%0d%0a、控制字符 → 不 5xx、不泄堆栈")
    void crlfLogForgery_neverBreakContract() throws Exception {
        String[] payloads = {
                "ok\\r\\n2026-11-16 00:00:00 ERROR [nexus] forged audit line injected",
                "ok\\n\\nFAKE-STACKTRACE at org.nexus.Fake",
                "ok%0d%0aInjected-Header: yes",
                "ok\\u0000\\u0007\\u001b[31m"
        };
        for (int i = 0; i < payloads.length; i++) {
            String json = withField(VALID_BASE, "description", "\"" + payloads[i] + "\"");
            MvcResult r = fire(json);
            assertAttackNeverBreaksContract(r, "crlf#" + i);
        }
    }

    // ==================== 3. 类型混淆（amount 不是数字） ====================

    @Test
    @DisplayName("类型混淆：amount=数组/对象/布尔/null → 必须 4xx 且不达业务服务")
    void typeConfusionOnAmount_rejectedBeforeService() throws Exception {
        // 注意：**故意不含** amount=\"100\"（JSON 字符串数字）——见
        // documentedLeniency_amountAsNumericString 的说明，那条属「有意宽松」，
        // 字符串→BigDecimal 是十进制金额的常规做法（且无精度歧义）。
        String[][] cases = {
                {"array", "[1,2]"},
                {"object", "{\"v\":1}"},
                {"boolean", "true"},
                {"null", "null"},
                {"nestedArray", "[[1]]"},
                {"hexString", "\"0x10\""}
        };
        StringBuilder report = new StringBuilder();
        for (String[] c : cases) {
            String json = withField(VALID_BASE, "amount", c[1]);
            MvcResult r = fire(json);
            int st = assertAttackNeverBreaksContract(r, "amount=" + c[0]);
            if (!(st >= 400 && st < 500)) {
                report.append("[amount=").append(c[0]).append("] 实测 ").append(st).append("；");
            }
        }
        assertTrue(report.length() == 0,
                "以下类型混淆未被 4xx 拒（逐个列出，便于一次看全）：" + report);
        Mockito.verify(orderService, Mockito.never()).createOrder(Mockito.any());
    }

    @Test
    @DisplayName("特征化（有意宽松）：amount=数字字符串 → 201 且被解析为等值十进制（钉住，改动必须显式改此测试）")
    void documentedLeniency_amountAsNumericString() throws Exception {
        MvcResult r = fire(withField(VALID_BASE, "amount", "\"100\""));
        assertEquals(201, r.getResponse().getStatus(),
                "字符串数字金额是**有意接受**的（BigDecimal 十进制解析无精度歧义；"
                        + "许多 SDK 刻意用字符串传金额）。若将来收紧标量强转，必须同步改本测试与文档。");
        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        Mockito.verify(orderService).createOrder(captor.capture());
        assertEquals(0, captor.getValue().getAmount().compareTo(new BigDecimal("100")),
                "字符串 \"100\" 应解析为等值十进制 100（不得出现量值漂移）");
    }

    @Test
    @DisplayName("类型混淆：expiryMinutes=非整数字符串/小数/越界/负数/对象 → 必须 4xx")
    void typeConfusionOnExpiryMinutes_rejected() throws Exception {
        String[][] cases = {
                {"stringNotNumber", "\"abc\""},
                {"decimal", "3.5"},
                {"intOverflow", "2147483648"},
                {"negative", "-1"},
                {"object", "{}"}
        };
        StringBuilder report = new StringBuilder();
        for (String[] c : cases) {
            String json = withField(VALID_BASE, "expiryMinutes", c[1]);
            MvcResult r = fire(json);
            int st = assertAttackNeverBreaksContract(r, "expiryMinutes=" + c[0]);
            if (!(st >= 400 && st < 500)) {
                report.append("[expiryMinutes=").append(c[0]).append("] 实测 ").append(st).append("；");
            }
        }
        assertTrue(report.length() == 0,
                "以下形态未被 4xx 拒（逐个列出，便于一次看全）：" + report);
    }

    // ==================== 4. 数值边界 ====================

    @Test
    @DisplayName("数值边界：amount=0 / -1 / 极小非零 → 必须 4xx 且不达业务服务")
    void amountBoundaries_rejectedBeforeService() throws Exception {
        String[] amounts = {"0", "-1", "-1000000", "0.0000000001"};
        for (int i = 0; i < amounts.length; i++) {
            String json = withField(VALID_BASE, "amount", amounts[i]);
            MvcResult r = fire(json);
            int st = assertAttackNeverBreaksContract(r, "amount#" + i + "(" + amounts[i] + ")");
            assertTrue(st >= 400 && st < 500,
                    "[amount=" + amounts[i] + "] 违反 @Min(1) 必须是 4xx（实测 " + st + "）");
        }
    }

    // ==================== 5. 超大载荷 ====================

    @Test
    @DisplayName("超大载荷：description 256KB → 不 5xx（是否截断/拒绝如实记录）")
    void oversizedPayload_neverBreaksContract() throws Exception {
        int n = 256 * 1024;
        StringBuilder sb = new StringBuilder(n + 8);
        for (int i = 0; i < n; i++) { sb.append('A'); }
        String json = withField(VALID_BASE, "description", "\"" + sb + "\"");
        MvcResult r = fire(json);
        assertAttackNeverBreaksContract(r, "oversized-description");
    }

    // ==================== 6. 畸形 JSON（截断 / null / 数组体 / 空体） ====================

    @Test
    @DisplayName("畸形 JSON：截断体 / null / 顶层数组 / 空体 / 空白 / 重复键 → 必须 4xx 且不达业务服务")
    void malformedJson_rejectedBeforeService() throws Exception {
        String[] bodies = {
                "{\"merchantId\":\"100\",\"amount\":100",       // 截断
                "null",
                "[{\"amount\":100}]",                        // 顶层数组
                "",                                          // 空体
                "   ",                                       // 纯空白
                "{\"amount\":100,\"amount\":100}"             // 重复键（Jackson 默认取后者）
        };
        for (int i = 0; i < bodies.length; i++) {
            MvcResult r = fire(bodies[i]);
            int st = assertAttackNeverBreaksContract(r, "malformed#" + i);
            assertTrue(st >= 400 && st < 500,
                    "[malformed#" + i + "] 畸形体必须是 4xx（实测 " + st + "）");
        }
    }

    // ==================== 7. 越权伪造身份（mass-assignment） ====================

    @Test
    @DisplayName("伪造 merchantId：body 写他人身份 → 落库请求的 merchantId 必须被服务端覆盖")
    void forgedMerchantIdInBody_isOverriddenByServer() throws Exception {
        // 合法基线本身带 merchantId=100；换成别人的 999 再打
        String json = VALID_BASE.replace("\"merchantId\":\"100\"", "\"merchantId\":\"999\"");
        MvcResult r = fire(json);
        assertAttackNeverBreaksContract(r, "forged-merchantId");

        ArgumentCaptor<CreateOrderRequest> captor = ArgumentCaptor.forClass(CreateOrderRequest.class);
        Mockito.verify(orderService, Mockito.atLeastOnce()).createOrder(captor.capture());
        for (CreateOrderRequest captured : captor.getAllValues()) {
            assertEquals("100", captured.getMerchantId(),
                    "body 里的 merchantId 必须被认证身份覆盖（防伪造他人身份下单）");
        }
    }

    // ==================== 8. 未认证即打真链路（对照：证明电池不是「反正都 401」） ====================

    @Test
    @DisplayName("对照：无 API Key → interceptor 401（证明上面各例不是「一律未认证」的假绿）")
    void withoutApiKey_interceptorRejects401() throws Exception {
        MvcResult r = mockMvc.perform(post(ORDERS_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BASE))
                .andReturn();
        assertEquals(401, r.getResponse().getStatus(), "无 API Key 必须 401（interceptor 侧）");
        assertNeverReachedOrderService();
    }

    // ==================== 9. 带攻击串的 API Key（对照 + 如实记录） ====================

    @Test
    @DisplayName("对照：API Key 带注入串 → interceptor 401，不进控制器")
    void apiKeyCarryingInjectionString_rejected401() throws Exception {
        String rogueKey = "' OR 1=1-- UNION SELECT 1";
        MvcResult r = mockMvc.perform(post(ORDERS_URI)
                        .header(API_KEY_HEADER, rogueKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BASE))
                .andReturn();
        assertEquals(401, r.getResponse().getStatus(), "未登记 key 必须 401（interceptor 侧）");
        Mockito.verify(merchantService).findByApiKey(rogueKey);
        assertNeverReachedOrderService();
    }

    @Test
    @DisplayName("记录：header 值含 CRLF 的应用层形态 = 500 且兜底不外泄（真实 HTTP 服务器更早在协议层拒）")
    void apiKeyCarryingCrlfheader_observedAppLevelBehavior() throws Exception {
        String rogueKey = "' OR 1=1--" + "\r\nForged: 1";
        MvcResult r = mockMvc.perform(post(ORDERS_URI)
                        .header(API_KEY_HEADER, rogueKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BASE))
                .andReturn();
        int st = r.getResponse().getStatus();
        String body = r.getResponse().getContentAsString();

        // 只记录不断言「必须 4xx」：MockMvc 允许 CRLF 过 header，而真实 Tomcat 在
        // 协议层就会拒（app 不可达）。这里要钉的是**即使到达 app，兜底也不泄异常文本**。
        assertFalse(body.contains("Caused by"), "兜底响应不得含异常链");
        assertFalse(body.contains("at org.nexus."), "兜底响应不得含 org.nexus 栈帧");
        assertFalse(body.contains("IllegalArgumentException"),
                "兜底响应不得含异常类名（当前实测 st=" + st + "，若为 500 则记为应用层形态）");
        assertNeverReachedOrderService();
    }

    // ==================== 10. 重放攻击：签名正确但 nonce 复用 ====================

    @Test
    @DisplayName("重放：同一 nonce+签名原样重发第二次 → 必须被拒（40106 Replayed nonce）")
    void replayedRequestWithValidSignature_rejected() throws Exception {
        String nonce = "replay-" + UUID.randomUUID();
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = RequestSignatureInterceptor.computeSignatureV2(
                timestamp, nonce, "POST", ORDERS_URI, VALID_BASE, signingSecret);

        MvcResult first = mockMvc.perform(post(ORDERS_URI)
                        .header(API_KEY_HEADER, testApiKey)
                        .header(TIMESTAMP_HEADER, timestamp)
                        .header(NONCE_HEADER, nonce)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BASE))
                .andReturn();
        assertAttackNeverBreaksContract(first, "replay-first");

        // 第二次：**签名完全正确**（攻击者原样重放合法请求）——这才是「真实攻击」形态
        MvcResult second = mockMvc.perform(post(ORDERS_URI)
                        .header(API_KEY_HEADER, testApiKey)
                        .header(TIMESTAMP_HEADER, timestamp)
                        .header(NONCE_HEADER, nonce)
                        .header(SIGNATURE_HEADER, signature)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BASE))
                .andReturn();
        int st = assertAttackNeverBreaksContract(second, "replay-second");
        assertEquals(401, st, "签名正确的重放也必须被拒（nonce 已消费）");
        assertTrue(second.getResponse().getContentAsString().contains("40106")
                        || second.getResponse().getContentAsString().toLowerCase().contains("replay"),
                "第二次应因 nonce 重放被拒（40106 / replay 语义）");
    }

    // ==================== 11. 观测：错误响应是否回显输入（如实记录，不粉饰） ====================

    @Test
    @DisplayName("观测：notifyUrl 带 CRLF 攻击串的当前处置形态（4xx 拒 或 原样落库 201）")
    void notifyUrlCarryingCrlfString_observedBehavior() throws Exception {
        String json = VALID_BASE.replace("https://merchant.example/notify",
                "not-a-url-with-crlf-x%0d%0aInjected");
        MvcResult r = fire(json);
        int st = assertAttackNeverBreaksContract(r, "notify-url-attack");
        assertTrue(st == 201 || (st >= 400 && st < 500),
                "notifyUrl 攻击串要么被 4xx 拒、要么原样落库（201）；实测 " + st);
    }
}
