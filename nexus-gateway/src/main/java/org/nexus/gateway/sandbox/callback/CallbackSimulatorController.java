package org.nexus.gateway.sandbox.callback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 回调模拟器 REST 控制器 — 允许测试人员手动触发模拟回调通知。
 *
 * <p>提供两个端点：</p>
 * <ul>
 *   <li>{@code POST /api/sandbox/callback-simulator/wechat} — 模拟微信支付回调</li>
 *   <li>{@code POST /api/sandbox/callback-simulator/alipay} — 模拟支付宝异步回调</li>
 * </ul>
 *
 * <p>所有端点仅在 sandbox profile 下可用（{@code @Profile("sandbox")}），
 * 不需要认证，仅供本地开发和测试使用。</p>
 *
 * <p>模拟器通过 {@link CallbackSimulatorService} 构造完整的回调通知
 * （含签名/加密），并通过 HTTP 发送到内部回调端点
 * ({@code /api/v1/callbacks/wechat} 和 {@code /api/v1/callbacks/alipay})，
 * 实现端到端的回调处理流程测试。</p>
 *
 * <p>经验来源：2026-09-24-service-without-rest-controller-gap-pattern
 * （前端 API 路径与后端 Controller 路径对比方法论）</p>
 */
@RestController
@RequestMapping("/api/sandbox/callback-simulator")
@Profile("sandbox")
public class CallbackSimulatorController {

    private static final Logger log = LoggerFactory.getLogger(CallbackSimulatorController.class);

    private final CallbackSimulatorService callbackSimulatorService;

    /** 应用基础 URL，用于构造内部回调端点的完整 URL */
    @Value("${nexus.sandbox.callback.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * 构造器注入回调模拟器服务。
     *
     * @param callbackSimulatorService 回调模拟器服务
     */
    public CallbackSimulatorController(CallbackSimulatorService callbackSimulatorService) {
        this.callbackSimulatorService = callbackSimulatorService;
    }

    /**
     * 模拟微信支付回调通知。
     *
     * <p>构造完整的微信 V3 回调 JSON body（含 AES-256-GCM 加密 resource），
     * 使用平台私钥生成 RSA-SHA256 签名头，POST 到 {@code /api/v1/callbacks/wechat}。</p>
     *
     * @param outTradeNo 商户订单号（必填）
     * @param tradeState 交易状态（默认 SUCCESS，可选值：SUCCESS / NOTPAY / CLOSED / REFUND 等）
     * @return 模拟结果，包含 requestBody、requestHeaders、responseStatus、responseBody
     */
    @PostMapping("/wechat")
    public ResponseEntity<Map<String, Object>> simulateWeChatCallback(
            @RequestParam String outTradeNo,
            @RequestParam(defaultValue = "SUCCESS") String tradeState) {

        log.info("[CallbackSimulatorController] 模拟微信回调请求: outTradeNo={}, tradeState={}",
                outTradeNo, tradeState);

        try {
            Map<String, Object> result = callbackSimulatorService.simulateWeChatCallback(
                    outTradeNo, tradeState, baseUrl);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[CallbackSimulatorController] 模拟微信回调失败: {}", e.getMessage(), e);
            Map<String, Object> error = new java.util.LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("provider", "wechat");
            error.put("outTradeNo", outTradeNo);
            error.put("tradeState", tradeState);
            return ResponseEntity.internalServerError().body(error);
        }
    }

    /**
     * 模拟支付宝异步回调通知。
     *
     * <p>构造支付宝异步通知 form 表单参数（含 RSA2 签名），
     * POST 到 {@code /api/v1/callbacks/alipay}。</p>
     *
     * @param outTradeNo  商户订单号（必填）
     * @param tradeStatus 交易状态（默认 TRADE_SUCCESS，可选值：TRADE_SUCCESS / WAIT_BUYER_PAY / TRADE_CLOSED / TRADE_FINISHED 等）
     * @return 模拟结果，包含 requestParams、responseStatus、responseBody
     */
    @PostMapping("/alipay")
    public ResponseEntity<Map<String, Object>> simulateAlipayCallback(
            @RequestParam String outTradeNo,
            @RequestParam(defaultValue = "TRADE_SUCCESS") String tradeStatus) {

        log.info("[CallbackSimulatorController] 模拟支付宝回调请求: outTradeNo={}, tradeStatus={}",
                outTradeNo, tradeStatus);

        try {
            Map<String, Object> result = callbackSimulatorService.simulateAlipayCallback(
                    outTradeNo, tradeStatus, baseUrl);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[CallbackSimulatorController] 模拟支付宝回调失败: {}", e.getMessage(), e);
            Map<String, Object> error = new java.util.LinkedHashMap<>();
            error.put("error", e.getMessage());
            error.put("provider", "alipay");
            error.put("outTradeNo", outTradeNo);
            error.put("tradeStatus", tradeStatus);
            return ResponseEntity.internalServerError().body(error);
        }
    }
}