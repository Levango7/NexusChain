package org.nexus.signing.util;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * HTTP 请求工具（签名服务 → 链节点 RPC）。
 *
 * <p>从 {@code org.nexus.wallet.Utils.HttpRequestUtil}（exchange-wallet）
 * 迁入 signing-service，包路径变更为 {@code org.nexus.signing.util}。</p>
 *
 * <p>NodeController 依赖本类进行链节点 RPC 调用。</p>
 *
 * <p>健壮性修复（质量审查 Top4，2026-09-10）：设置 connect 5s / read 30s
 * 超时（链节点无响应时快速失败，避免签名线程永久挂起）。</p>
 *
 * <p><b>连接池改造（评估报告 §6.3-5「签名服务连接池」，P1）</b>：
 * 原实现每次调用都通过 {@link java.net.URLConnection} 打开连接，无法控制连接
 * 复用与上限，每次 RPC 都可能经历一次 TCP 握手。现改为进程内共享单例
 * {@link HttpClient}（HTTP/1.1），其内置连接池自动复用 keep-alive 连接，
 * 显著降低高频广播/查询场景的连接开销。</p>
 *
 * <p>对外契约保持不变：{@link #sendPost(String, String)} /
 * {@link #sendGet(String, String)} 的签名、请求头语义与失败时返回的统一错误
 * JSON（{@code {"message":"Connection refused","data":"","code":"5000"}}）均与改造前一致。</p>
 */
public class HttpRequestUtil {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestUtil.class);

    /** 连接建立超时（ms）——链节点不可达时快速失败。 */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    /** 响应读取超时（ms）——链 RPC 可能慢（全节点同步查询），但 30s 后放弃。 */
    private static final int READ_TIMEOUT_MS = 30_000;

    /** 原实现使用的 user-agent，保持请求指纹一致。 */
    private static final String USER_AGENT =
            "Mozilla/4.0 (compatible; MSIE 6.0; Windows NT 5.1;SV1)";

    /**
     * 进程内共享的 HTTP 客户端（连接池）。
     *
     * <p>HTTP/1.1 + 内置 keep-alive 连接池；{@code connectTimeout} 在客户端级别设定，
     * 单请求超时通过 {@link HttpRequest.Builder#timeout} 设定。</p>
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
            .build();

    private HttpRequestUtil() {
    }

    /**
     * 发送 POST 请求（表单体，无 Content-Type，与改造前
     * {@code URLConnection} 行为一致）。
     *
     * @param url   完整地址
     * @param param 请求体字符串
     * @return 响应体；失败时返回统一错误 JSON
     */
    public static String sendPost(String url, String param) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .header("accept", "*/*")
                .header("user-agent", USER_AGENT)
                .method("POST",
                        HttpRequest.BodyPublishers.ofString(
                                param == null ? "" : param, StandardCharsets.UTF_8))
                .build();
        return execute(request, url);
    }

    /**
     * 发送 GET 请求（参数以 query string 拼接）。
     *
     * @param url   基础地址
     * @param param 查询参数（{@code k=v}）
     * @return 响应体；失败时返回统一错误 JSON
     */
    public static String sendGet(String url, String param) {
        String urlNameString = url + "?" + param;
        HttpRequest request = HttpRequest.newBuilder(URI.create(urlNameString))
                .timeout(Duration.ofMillis(READ_TIMEOUT_MS))
                .header("accept", "*/*")
                .header("user-agent", USER_AGENT)
                .GET()
                .build();
        return execute(request, urlNameString);
    }

    private static String execute(HttpRequest request, String url) {
        try {
            HttpResponse<String> response =
                    CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.body();
        } catch (Exception e) {
            log.error("HTTP request failed: url={}", url, e);
            JsonObject jo = new JsonObject();
            jo.addProperty("message", "Connection refused");
            jo.addProperty("data", "");
            jo.addProperty("code", "5000");
            return jo.toString();
        }
    }
}
