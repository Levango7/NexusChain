package org.nexus.gateway.sandbox.mock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

/**
 * 支付宝统一网关 API Mock Server — 使用 WireMock 模拟支付宝开放平台网关接口，
 * 供 AlipayConnector 集成测试使用。
 *
 * <p>支付宝所有 API 请求均 POST 到统一网关 {@code /gateway.do}，
 * 通过 form 表单参数 {@code method} 区分不同的 API 方法：
 * <ul>
 *   <li>method=alipay.trade.precreate — 预下单（扫码支付），返回 qr_code 和 trade_no</li>
 *   <li>method=alipay.trade.query — 交易查询，返回 trade_status 和 trade_no</li>
 *   <li>method=alipay.trade.close — 交易关闭，返回成功状态</li>
 *   <li>method=alipay.trade.refund — 交易退款，返回 fund_change 和 trade_no</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>{@code
 * AlipayMockServer server = new AlipayMockServer();
 * server.start();
 * // 使用 server.getBaseUrl() 作为 AlipayConnector 的 gatewayUrl
 * server.stop();
 * }</pre>
 *
 * <p>WireMock 3.5.2 standalone 通过 build.gradle testImplementation 引入。</p>
 */
public class AlipayMockServer {

    private final WireMockServer wireMockServer;

    public AlipayMockServer() {
        this.wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    }

    /**
     * 启动 Mock Server 并注册所有支付宝统一网关 API stub。
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
     * 注册所有支付宝统一网关 API 的 mock stub。
     *
     * <p>支付宝统一网关的特点：所有 API 请求都 POST 到 {@code /gateway.do}，
     * 通过 form 表单参数 {@code method} 的值区分不同的 API 方法。
     * WireMock 通过 {@code withRequestBody(containing(...))} 匹配 form 参数。</p>
     */
    private void registerStubs() {

        // ---------- method=alipay.trade.precreate — 预下单（扫码支付） ----------
        wireMockServer.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.precreate"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_precreate_response\":{" +
                                "\"code\":\"10000\"," +
                                "\"msg\":\"Success\"," +
                                "\"qr_code\":\"https://qr.alipay.com/mock_qr_001\"," +
                                "\"trade_no\":\"alipay_tx_mock_001\"" +
                                "}}")));

        // ---------- method=alipay.trade.query — 交易查询 ----------
        wireMockServer.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.query"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_query_response\":{" +
                                "\"code\":\"10000\"," +
                                "\"msg\":\"Success\"," +
                                "\"trade_status\":\"TRADE_SUCCESS\"," +
                                "\"trade_no\":\"alipay_tx_mock_001\"" +
                                "}}")));

        // ---------- method=alipay.trade.close — 交易关闭 ----------
        wireMockServer.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.close"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_close_response\":{" +
                                "\"code\":\"10000\"," +
                                "\"msg\":\"Success\"" +
                                "}}")));

        // ---------- method=alipay.trade.refund — 交易退款 ----------
        wireMockServer.stubFor(post(urlPathMatching("/gateway.do"))
                .withRequestBody(containing("method=alipay.trade.refund"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"alipay_trade_refund_response\":{" +
                                "\"code\":\"10000\"," +
                                "\"msg\":\"Success\"," +
                                "\"fund_change\":\"Y\"," +
                                "\"trade_no\":\"alipay_tx_mock_001\"" +
                                "}}")));
    }
}