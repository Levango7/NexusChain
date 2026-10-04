package org.nexus.signing.util;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpRequestUtil} 连接池改造（评估报告 §6.3-5）回归测试。
 *
 * <p>原 {@link NodeControllerTest} 仅覆盖连接失败路径，无法验证成功路径的
 * 方法/请求体/查询串是否被正确投递。本测试用 JDK 内置
 * {@link HttpServer} 起一个真实本地端点，断言：
 * <ul>
 *   <li>{@code sendPost} 以 POST 投递原始表单体，并返回响应体；</li>
 *   <li>{@code sendGet} 以 GET 投递 query string；</li>
 *   <li>多次调用（连接复用）均可用；</li>
 *   <li>连接失败时仍返回统一错误 JSON（契约不变）。</li>
 * </ul></p>
 */
class HttpRequestUtilPoolTest {

    private static HttpServer server;
    private static int port;

    private static final AtomicReference<String> LAST_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> LAST_BODY = new AtomicReference<>();
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/sendNonce", exchange -> {
            LAST_METHOD.set(exchange.getRequestMethod());
            LAST_QUERY.set(exchange.getRequestURI().getQuery());
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            LAST_BODY.set(new String(body, StandardCharsets.UTF_8));
            byte[] resp = ("{\"echo\":\"" + LAST_BODY.get() + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("sendPost：POST 投递原始表单体并返回响应体（复用连接）")
    void sendPost_deliversRawBody_andReturnsResponse() {
        String url = "http://127.0.0.1:" + port + "/sendNonce";
        String first = HttpRequestUtil.sendPost(url, "pubkeyhash=abc");
        String second = HttpRequestUtil.sendPost(url, "pubkeyhash=def");
        assertEquals("POST", LAST_METHOD.get());
        assertEquals("pubkeyhash=def", LAST_BODY.get());
        assertTrue(first.contains("pubkeyhash=abc"), "首次响应应回显请求体");
        assertTrue(second.contains("pubkeyhash=def"), "复用连接后响应应回显请求体");
    }

    @Test
    @DisplayName("sendGet：GET 以 query string 投递并返回响应体")
    void sendGet_appendsQuery_andReturnsResponse() {
        String url = "http://127.0.0.1:" + port + "/sendNonce";
        String out = HttpRequestUtil.sendGet(url, "txHash=0xdead");
        assertEquals("GET", LAST_METHOD.get());
        assertEquals("txHash=0xdead", LAST_QUERY.get());
        assertNotNull(out);
    }

    @Test
    @DisplayName("连接失败：返回统一错误 JSON（契约不变）")
    void connectionFailure_returnsErrorJson() {
        String out = HttpRequestUtil.sendPost("http://127.0.0.1:1/sendNonce", "x=1");
        assertTrue(out.contains("\"code\":\"5000\""), "失败时应返回 code=5000 错误 JSON");
    }
}
