package org.nexus.gateway.controller;

import feign.FeignException;
import feign.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.gateway.client.feign.SigningApprovalFeignClient;
import org.nexus.gateway.dto.ApiResponse;
import org.nexus.gateway.dto.ErrorCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link SigningApprovalProxyController} 的转发语义测试。
 *
 * <p>方法安全不在此生效（直接构造对象）：ADMIN 门禁由
 * {@link SigningApprovalProxyControllerMethodSecurityTest} 用真实代理验证。
 * 本类只钉住三件事：调用者 JWT 原样转发（不被服务身份顶替）、下游状态码/正文
 * 逐字透传、传输失败映射为 502。</p>
 */
@ExtendWith(MockitoExtension.class)
class SigningApprovalProxyControllerTest {

    private static final String CALLER_TOKEN = "Bearer human-admin.token";

    @Mock
    private SigningApprovalFeignClient client;

    private SigningApprovalProxyController controller;

    @BeforeEach
    void setUp() {
        controller = new SigningApprovalProxyController(client);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("alice@nexus", null,
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("调用者 JWT 与请求体原样转发，下游 200 正文逐字透传")
    void forwardsCallerTokenAndBodyVerbatim() {
        String downstreamJson = "{\"statusCode\":2000,\"message\":\"ok\","
                + "\"data\":{\"requestId\":\"req-1\",\"status\":\"PENDING\"}}";
        Map<String, Object> body = Map.of("decision", "APPROVE", "comment", "ok");
        when(client.decide("req-1", CALLER_TOKEN, body)).thenReturn(response(200, downstreamJson));

        ResponseEntity<Object> result = controller.decide("req-1", CALLER_TOKEN, body);

        assertEquals(200, result.getStatusCode().value());
        assertEquals(downstreamJson, result.getBody());
        assertEquals(MediaType.APPLICATION_JSON, result.getHeaders().getContentType());
        verify(client).decide("req-1", CALLER_TOKEN, body);
    }

    @Test
    @DisplayName("下游 403 与 denied_reason 逐字透传——网关不重写下游判定")
    void passesThroughForbiddenWithDeniedReason() {
        String downstreamJson = "{\"statusCode\":4030,\"message\":\"Approver is not allowed to decide\","
                + "\"data\":{\"denied_reason\":\"approver_not_in_whitelist\"}}";
        when(client.decide(eq("req-2"), anyString(), anyMap())).thenReturn(response(403, downstreamJson));

        ResponseEntity<Object> result = controller.decide("req-2", CALLER_TOKEN,
                Map.of("decision", "APPROVE"));

        assertEquals(403, result.getStatusCode().value());
        assertEquals(downstreamJson, result.getBody());
    }

    @Test
    @DisplayName("下游 409（已不在待审状态）同样原样返回")
    void passesThroughConflict() {
        String downstreamJson = "{\"statusCode\":4090,\"message\":\"审批请求已不在待审状态\"}";
        when(client.decide(eq("req-3"), anyString(), anyMap())).thenReturn(response(409, downstreamJson));

        ResponseEntity<Object> result = controller.decide("req-3", CALLER_TOKEN,
                Map.of("decision", "REJECT"));

        assertEquals(409, result.getStatusCode().value());
        assertEquals(downstreamJson, result.getBody());
    }

    @Test
    @DisplayName("请求体缺省时按空对象转发，由下游判 decision 合法性")
    void forwardsEmptyBodyWhenAbsent() {
        when(client.decide(eq("req-4"), anyString(), eq(Map.of()))).thenReturn(response(400, "{}"));

        ResponseEntity<Object> result = controller.decide("req-4", CALLER_TOKEN, null);

        assertEquals(400, result.getStatusCode().value());
        verify(client).decide("req-4", CALLER_TOKEN, Map.of());
    }

    @Test
    @DisplayName("下游不可达 → 502 + 50202，不冒充 500")
    void mapsTransportFailureToBadGateway() {
        when(client.decide(anyString(), anyString(), anyMap()))
                .thenThrow(FeignException.errorStatus("decide", response(503, "{}")));

        ResponseEntity<Object> result = controller.decide("req-5", CALLER_TOKEN, Map.of());

        assertEquals(502, result.getStatusCode().value());
        assertEquals(ErrorCode.SIGNING_SERVICE_UNAVAILABLE.getCode(),
                ((ApiResponse<?>) result.getBody()).getCode());
    }

    @Test
    @DisplayName("没有可转发的 Bearer 凭证 → 403，且不发起下游调用")
    void rejectsMissingBearerWithoutCallingDownstream() {
        ResponseEntity<Object> result = controller.decide("req-6", null, Map.of("decision", "APPROVE"));

        assertEquals(403, result.getStatusCode().value());
        assertEquals(ErrorCode.ACCESS_DENIED.getCode(),
                ((ApiResponse<?>) result.getBody()).getCode());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("空白 Authorization 同样被拒，且不发起下游调用")
    void rejectsBlankBearerWithoutCallingDownstream() {
        ResponseEntity<Object> result = controller.decide("req-7", "  ", Map.of());

        assertEquals(403, result.getStatusCode().value());
        verifyNoInteractions(client);
    }

    @Test
    @DisplayName("下游 2xx 空正文不抛异常")
    void toleratesEmptyBody() {
        when(client.decide(any(), any(), any())).thenReturn(
                Response.builder().request(stubRequest()).status(200).reason("OK").headers(Map.of()).build());

        ResponseEntity<Object> result = controller.decide("req-8", CALLER_TOKEN, Map.of());

        assertEquals(200, result.getStatusCode().value());
        assertEquals("", result.getBody());
    }

    @Test
    @DisplayName("requestId 含路径分隔/点段等形态 → 400，不发起下游调用")
    void rejectsMalformedRequestId() {
        for (String bad : List.of("../transfers", "a/b", "req.1", "")) {
            ResponseEntity<Object> result = controller.decide(bad, CALLER_TOKEN, Map.of());

            assertEquals(400, result.getStatusCode().value(), "应拒绝: " + bad);
        }
        verifyNoInteractions(client);
    }

    private static Response response(int status, String json) {
        return Response.builder()
                .request(stubRequest())
                .status(status)
                .reason("stub")
                .headers(Map.of("Content-Type", List.of("application/json")))
                .body(json, StandardCharsets.UTF_8)
                .build();
    }

    private static feign.Request stubRequest() {
        return feign.Request.create(feign.Request.HttpMethod.POST,
                "http://signing-service/api/v1/signing/approvals/stub/decision",
                Map.of(), new byte[0], StandardCharsets.UTF_8);
    }
}
