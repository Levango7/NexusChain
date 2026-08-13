package org.nexus.oracle.governance.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.nexus.oracle.governance.Proposal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证者集变更端口——HTTP 客户端实现（P1-4 真实桥接）。
 *
 * <p>调用 core 侧 {@code ValidatorRpcController}（/rpc/v1/validators/{register|unregister}），
 * 实现治理→验证者集连接轴的端到端接线。oracle 模块只依赖端口接口与本实现，
 * 不对 core 做编译期依赖（通过 HTTP + JSON 交互）。</p>
 *
 * <p>使用 JDK 内置 {@link HttpClient}（零新依赖），超时与失败均 fail-closed。</p>
 */
@Component
public class HttpValidatorSetPort implements ValidatorSetPort {

    private static final Logger log = LoggerFactory.getLogger(HttpValidatorSetPort.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String coreBaseUrl;

    public HttpValidatorSetPort(@Value("${nexus.core.rpc-url:http://localhost:19585}") String coreBaseUrl) {
        this.coreBaseUrl = coreBaseUrl.endsWith("/")
                ? coreBaseUrl.substring(0, coreBaseUrl.length() - 1) : coreBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public ExecutionResult apply(Proposal proposal) {
        Map<String, Object> params = proposal.getParameters();
        if (params == null) {
            return ExecutionResult.fail("missing parameters");
        }
        String action = String.valueOf(params.getOrDefault("action", ""));
        String address = String.valueOf(params.getOrDefault("validatorAddress", ""));

        try {
            if ("add".equals(action)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("address", address);
                body.put("publicKey", params.get("publicKey"));
                body.put("stakeAmount", params.get("stakeAmount"));
                body.put("commissionRate", params.getOrDefault("commissionRate", 0.1));
                return post("/rpc/v1/validators/register", body);
            } else if ("remove".equals(action)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("address", address);
                return post("/rpc/v1/validators/unregister", body);
            }
            return ExecutionResult.fail("unknown action: " + action);
        } catch (Exception e) {
            log.error("ValidatorSetPort HTTP call failed: action={}, address={}, error={}",
                    action, address, e.getMessage());
            return ExecutionResult.fail("core RPC unreachable: " + e.getMessage());
        }
    }

    private ExecutionResult post(String path, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(coreBaseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return ExecutionResult.fail("core returned HTTP " + resp.statusCode());
        }
        Map<?, ?> parsed = objectMapper.readValue(resp.body(), Map.class);
        Object code = parsed.get("code");
        int codeInt = code instanceof Number ? ((Number) code).intValue() : -1;
        if (codeInt == 2000) {
            return ExecutionResult.ok("core applied: " + resp.body());
        }
        Object message = parsed.get("message");
        return ExecutionResult.fail("core rejected: " + (message == null ? resp.body() : message));
    }
}