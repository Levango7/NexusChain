package org.nexus.gateway.sandbox.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * 微信支付 V3 API Mock Server — 使用 WireMock 模拟微信支付 V3 接口，
 * 供 WeChatConnector 集成测试使用。
 *
 * <p>模拟的 API 端点：
 * <ul>
 *   <li>POST /v3/pay/transactions/native — 统一下单（Native 支付），返回 code_url</li>
 *   <li>GET /v3/pay/transactions/out-trade-no/{out_trade_no} — 查询订单，返回 trade_state</li>
 *   <li>POST /v3/refund/domestic/refunds — 申请退款，返回 refund_id</li>
 *   <li>GET /v3/certificates — 获取平台证书列表</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>{@code
 * WeChatMockServer server = new WeChatMockServer();
 * server.start();
 * // 使用 server.getBaseUrl() 作为 WeChatConnector 的 apiBase
 * server.stop();
 * }</pre>
 *
 * <p>WireMock 3.5.2 standalone 通过 build.gradle testImplementation 引入。</p>
 */
public class WeChatMockServer {

    private final WireMockServer wireMockServer;

    public WeChatMockServer() {
        this.wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    }

    /**
     * 启动 Mock Server 并注册所有微信支付 V3 API stub。
     */
    public void start() {
        wireMockServer.start();
        registerStubs();
    }

    /**
     * 停止 Mock Server。
     */
    public void stop() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    /**
     * 获取 Mock Server 的基础 URL（如 http://localhost:xxxxx）。
     *
     * @return 基础 URL 字符串
     */
    public String getBaseUrl() {
        return wireMockServer.baseUrl();
    }

    /**
     * 获取 Mock Server 监听的端口号。
     *
     * @return 端口号
     */
    public int getPort() {
        return wireMockServer.port();
    }

    /**
     * 注册所有微信支付 V3 API 的 mock stub。
     */
    private void registerStubs() {

        // ---------- POST /v3/pay/transactions/native — 统一下单 ----------
        wireMockServer.stubFor(post(urlPathMatching("/v3/pay/transactions/native"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code_url\":\"weixin://wxpay/bizpayurl?pr=test_qr_001\"}")));

        // ---------- GET /v3/pay/transactions/out-trade-no/{out_trade_no} — 查询订单 ----------
        wireMockServer.stubFor(get(urlPathMatching("/v3/pay/transactions/out-trade-no/[^/]+"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"trade_state\":\"SUCCESS\",\"transaction_id\":\"wx_tx_mock_001\"}")));

        // ---------- POST /v3/refund/domestic/refunds — 申请退款 ----------
        wireMockServer.stubFor(post(urlPathMatching("/v3/refund/domestic/refunds"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"refund_id\":\"wx_refund_mock_001\",\"status\":\"SUCCESS\"}")));

        // ---------- GET /v3/certificates — 获取平台证书 ----------
        wireMockServer.stubFor(get(urlPathMatching("/v3/certificates"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(buildMockCertificatesResponse())));
    }

    /**
     * 构建模拟的微信支付平台证书列表响应。
     *
     * <p>微信支付 V3 API 的 /v3/certificates 返回一个证书数组，每个证书包含
     * serial_no、effective_time、expire_time 和 encrypt_certificate（加密的证书内容）。
     * 此处返回模拟数据，仅供测试使用。</p>
     *
     * @return 模拟证书列表的 JSON 字符串
     */
    private String buildMockCertificatesResponse() {
        return "{\"data\":[" +
                "{\"serial_no\":\"mock_cert_serial_001\"," +
                "\"effective_time\":\"2025-01-01T00:00:00+08:00\"," +
                "\"expire_time\":\"2027-01-01T00:00:00+08:00\"," +
                "\"encrypt_certificate\":{" +
                "\"algorithm\":\"AEAD_AES_256_GCM\"," +
                "\"nonce\":\"mock_nonce_001\"," +
                "\"associated_data\":\"certificate\"," +
                "\"ciphertext\":\"mock_ciphertext_001\"" +
                "}}" +
                "]}";
    }
}