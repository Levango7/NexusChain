package org.nexus.gateway.orchestration.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPaySignatureUtil;
import org.nexus.gateway.orchestration.connectors.WeChatPlatformCertificateManager;
import org.nexus.gateway.orchestration.model.OrchPaymentStatus;
import org.nexus.gateway.orchestration.model.OrchestratedPayment;
import org.nexus.gateway.orchestration.service.PaymentCallbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 支付渠道异步回调通知接收控制器 — 微信支付 / 支付宝。
 *
 * <p>职责：</p>
 * <ol>
 *   <li>接收微信支付 V3 / 支付宝异步通知</li>
 *   <li>验签：使用对应签名工具验证通知合法性</li>
 *   <li>幂等处理：通过 paymentId + 通知ID 去重，已处理的通知不再重复处理</li>
 *   <li>状态更新：验签通过后更新支付订单状态</li>
 * </ol>
 *
 * <p>微信回调端点：POST /api/v1/callbacks/wechat
 * 支付宝回调端点：POST /api/v1/callbacks/alipay</p>
 */
@RestController
@RequestMapping("/api/v1/callbacks")
public class PaymentCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PaymentCallbackController.class);

    /** 复用线程安全的 ObjectMapper 解析回调 JSON（P1-7）。 */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PaymentCallbackService callbackService;
    private final WeChatPlatformCertificateManager certificateManager;

    @Value("${nexus.connectors.wechat.api-key:}")
    private String wechatApiV3Key;

    @Value("${nexus.connectors.alipay.alipay-public-key:}")
    private String alipayPublicKey;

    /**
     * 幂等去重缓存：key = paymentId + ":" + notificationId。
     *
     * <p>P0-2 修复：原 ConcurrentHashMap.newKeySet() 无界增长，长期运行会 OOM。
     * 改为 Caffeine 有界缓存：最大 10 万条，写入后 24 小时过期。
     * 支付回调的去重窗口远小于 24 小时，超期条目可安全淘汰。</p>
     */
    private final Cache<String, Boolean> processedNotifications = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(24, TimeUnit.HOURS)
            .build();

    public PaymentCallbackController(PaymentCallbackService callbackService,
                                      WeChatPlatformCertificateManager certificateManager) {
        this.callbackService = callbackService;
        this.certificateManager = certificateManager;
    }

    // ==================== 微信支付回调 ====================

    /**
     * 接收微信支付 V3 异步通知。
     *
     * <p>微信 V3 回调 HTTP 头包含：</p>
     * <ul>
     *   <li>Wechatpay-Timestamp：时间戳</li>
     *   <li>Wechatpay-Nonce：随机串</li>
     *   <li>Wechatpay-Signature：签名</li>
     *   <li>Wechatpay-Serial：证书序列号</li>
     * </ul>
     *
     * <p>验签流程：使用微信平台证书公钥做 RSA-SHA256 验签（替代原 HMAC-SHA256），
     * 验签通过后使用 AES-256-GCM 解密 resource.ciphertext，从解密后的 JSON 中
     * 提取 out_trade_no（对应 paymentId）和 transaction_id，更新支付订单状态。</p>
     *
     * @param body           回调请求体（JSON）
     * @param timestamp      Wechatpay-Timestamp 头
     * @param nonce          Wechatpay-Nonce 头
     * @param signature      Wechatpay-Signature 头
     * @param wechatpaySerial Wechatpay-Serial 头（平台证书序列号）
     * @return 微信要求的响应：成功返回 200 + {"code":"SUCCESS"}，失败返回对应错误
     */
    @PostMapping("/wechat")
    public ResponseEntity<Map<String, Object>> wechatCallback(
            @RequestBody String body,
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestHeader(value = "Wechatpay-Serial", required = false) String wechatpaySerial) {

        log.info("[WeChat Callback] 收到微信支付回调通知");

        // 1. 验签前置检查：密钥未配置时禁止处理回调（P0-1 安全修复）
        // APIv3 密钥是 AES-256-GCM 解密 resource 的必需配置，缺失时拒绝处理。
        if (wechatApiV3Key == null || wechatApiV3Key.isBlank()) {
            log.error("[WeChat Callback] APIv3 密钥未配置，拒绝处理回调（防止伪造通知）");
            return failResponse("APIv3 密钥未配置，拒绝回调");
        }

        // 2. 验签：检查必要头是否存在
        if (timestamp == null || nonce == null || signature == null) {
            log.warn("[WeChat Callback] 缺少必要的签名头");
            return failResponse("缺少必要的签名头");
        }

        // 3. 验签：使用微信平台证书公钥做 RSA-SHA256 验签
        String platformPublicKey = certificateManager.getPlatformPublicKey(wechatpaySerial);
        if (platformPublicKey == null) {
            // 无可用平台证书时，尝试获取任意有效证书
            platformPublicKey = certificateManager.getAnyValidPlatformPublicKey();
        }
        if (platformPublicKey == null) {
            log.error("[WeChat Callback] 无可用的微信平台证书，拒绝处理回调");
            return failResponse("无可用平台证书，拒绝回调");
        }
        boolean verified = WeChatPaySignatureUtil.verifyCallbackSignatureWithPlatformCert(
                timestamp, nonce, body, signature, platformPublicKey);
        if (!verified) {
            log.warn("[WeChat Callback] 验签失败（RSA-SHA256，平台证书）");
            return failResponse("验签失败");
        }
        log.info("[WeChat Callback] 验签通过（RSA-SHA256，平台证书）");

        // 4. 解析回调体并解密 resource.ciphertext（AES-256-GCM）
        String notificationId;
        String outTradeNo;
        String transactionId;
        String tradeState;
        try {
            JsonNode bodyNode = OBJECT_MAPPER.readTree(body);
            notificationId = bodyNode.path("id").asText();

            // 从 resource 字段提取加密数据
            JsonNode resourceNode = bodyNode.path("resource");
            String ciphertext = resourceNode.path("ciphertext").asText();
            String resourceNonce = resourceNode.path("nonce").asText();
            String associatedData = resourceNode.path("associated_data").asText();

            if (ciphertext.isEmpty() || resourceNonce.isEmpty()) {
                log.warn("[WeChat Callback] resource 字段缺失或格式不合法");
                return failResponse("resource 字段缺失");
            }

            // AES-256-GCM 解密
            String decryptedJson;
            try {
                decryptedJson = WeChatPaySignatureUtil.decryptResource(
                        ciphertext, resourceNonce, associatedData, wechatApiV3Key);
            } catch (RuntimeException e) {
                log.warn("[WeChat Callback] AES-256-GCM 解密失败: {}", e.getMessage());
                return failResponse("解密失败");
            }

            log.info("[WeChat Callback] AES-256-GCM 解密成功，提取业务字段");

            // 从解密后的 JSON 中提取业务字段
            JsonNode decryptedNode = OBJECT_MAPPER.readTree(decryptedJson);
            outTradeNo = decryptedNode.path("out_trade_no").asText();
            transactionId = decryptedNode.path("transaction_id").asText();
            tradeState = decryptedNode.path("trade_state").asText();
        } catch (JsonProcessingException e) {
            log.warn("[WeChat Callback] JSON 解析失败: {}", e.getOriginalMessage());
            return failResponse("JSON 解析失败");
        }

        if (outTradeNo == null || outTradeNo.isEmpty()) {
            log.warn("[WeChat Callback] 无法提取 out_trade_no");
            return failResponse("无法提取 out_trade_no");
        }

        // 5. 幂等处理：paymentId + notificationId 去重
        String dedupKey = outTradeNo + ":" + (notificationId != null ? notificationId : transactionId);
        if (processedNotifications.getIfPresent(dedupKey) != null) {
            log.info("[WeChat Callback] 通知已处理过，幂等返回成功: {}", dedupKey);
            return successResponse();
        }

        // 6. 更新支付订单状态
        Optional<OrchestratedPayment> paymentOpt = callbackService.findById(outTradeNo);
        if (paymentOpt.isEmpty()) {
            log.warn("[WeChat Callback] 支付订单不存在: {}", outTradeNo);
            // 微信要求：即使订单不存在也应返回 200，否则微信会持续重试
            processedNotifications.put(dedupKey, Boolean.TRUE);
            return successResponse();
        }

        OrchestratedPayment payment = paymentOpt.get();
        OrchPaymentStatus newStatus = mapWeChatTradeState(tradeState);
        if (newStatus != null && payment.getStatus() != newStatus) {
            payment.setStatus(newStatus);
            if (newStatus == OrchPaymentStatus.SUCCEEDED && payment.getConfirmedAt() == null) {
                payment.setConfirmedAt(Instant.now());
            }
            callbackService.save(payment);
            log.info("[WeChat Callback] 支付订单状态更新: {} -> {}", outTradeNo, newStatus);
        }

        processedNotifications.put(dedupKey, Boolean.TRUE);
        return successResponse();
    }

    // ==================== 支付宝回调 ====================

    /**
     * 接收支付宝异步通知。
     *
     * <p>支付宝异步通知以表单参数形式发送，包含 trade_status、out_trade_no、trade_no、
     * sign、sign_type 等字段。验签时需将所有参数（排除 sign 和 sign_type）按 key 排序
     * 拼接后用支付宝公钥验证 RSA2 签名。</p>
     *
     * @param params 回调通知参数（表单参数）
     * @return 支付宝要求的响应：成功返回 "success"，失败返回 "fail"
     */
    @PostMapping(value = "/alipay", consumes = "application/x-www-form-urlencoded")
    public ResponseEntity<String> alipayCallback(@RequestParam Map<String, String> params) {

        log.info("[Alipay Callback] 收到支付宝回调通知");

        // 1. 验签前置检查：公钥未配置时禁止处理回调（P0-1 安全修复）
        if (alipayPublicKey == null || alipayPublicKey.isBlank()) {
            log.error("[Alipay Callback] 支付宝公钥未配置，拒绝处理回调（防止伪造通知）");
            return ResponseEntity.status(HttpStatus.OK).body("fail");
        }

        // 2. 验签
        boolean verified = AlipaySignatureUtil.verifyCallbackSignature(params, alipayPublicKey);
        if (!verified) {
            log.warn("[Alipay Callback] 验签失败");
            return ResponseEntity.status(HttpStatus.OK).body("fail");
        }
        log.info("[Alipay Callback] 验签通过");

        // 3. 解析关键字段
        String outTradeNo = params.get("out_trade_no");
        String tradeNo = params.get("trade_no");
        String tradeStatus = params.get("trade_status");
        String notifyId = params.get("notify_id");

        if (outTradeNo == null || outTradeNo.isEmpty()) {
            log.warn("[Alipay Callback] 无法提取 out_trade_no");
            return ResponseEntity.status(HttpStatus.OK).body("fail");
        }

        // 4. 幂等处理：paymentId + notifyId 去重
        String dedupKey = outTradeNo + ":" + (notifyId != null ? notifyId : tradeNo);
        if (processedNotifications.getIfPresent(dedupKey) != null) {
            log.info("[Alipay Callback] 通知已处理过，幂等返回成功: {}", dedupKey);
            return ResponseEntity.status(HttpStatus.OK).body("success");
        }

        // 5. 更新支付订单状态
        Optional<OrchestratedPayment> paymentOpt = callbackService.findById(outTradeNo);
        if (paymentOpt.isEmpty()) {
            log.warn("[Alipay Callback] 支付订单不存在: {}", outTradeNo);
            processedNotifications.put(dedupKey, Boolean.TRUE);
            return ResponseEntity.status(HttpStatus.OK).body("success");
        }

        OrchestratedPayment payment = paymentOpt.get();
        OrchPaymentStatus newStatus = mapAlipayTradeStatus(tradeStatus);
        if (newStatus != null && payment.getStatus() != newStatus) {
            payment.setStatus(newStatus);
            if (newStatus == OrchPaymentStatus.SUCCEEDED && payment.getConfirmedAt() == null) {
                payment.setConfirmedAt(Instant.now());
            }
            callbackService.save(payment);
            log.info("[Alipay Callback] 支付订单状态更新: {} -> {}", outTradeNo, newStatus);
        }

        processedNotifications.put(dedupKey, Boolean.TRUE);
        return ResponseEntity.status(HttpStatus.OK).body("success");
    }

    // ==================== 异常处理 ====================

    /**
     * 全局异常处理：fail-closed 守卫。
     *
     * <p>含 fail-closed 守卫的 Controller 必须声明 ExceptionHandler，
     * 确保未预期异常不会泄露堆栈信息，返回 403 Forbidden（非 500），
     * 响应体仅含简短 message。</p>
     *
     * <p>来源：2026-09-16-fail-closed-guard-exception-handler-completeness</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception e) {
        log.error("[Payment Callback] 未预期异常，fail-closed 拒绝处理: {}", e.getMessage());
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "FAIL");
        resp.put("message", "处理异常，拒绝回调");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(resp);
    }

    // ==================== 辅助方法 ====================

    /**
     * 微信支付 trade_state 映射到 OrchPaymentStatus。
     */
    private OrchPaymentStatus mapWeChatTradeState(String tradeState) {
        if (tradeState == null) return null;
        return switch (tradeState) {
            case "SUCCESS" -> OrchPaymentStatus.SUCCEEDED;
            case "REFUND" -> OrchPaymentStatus.REFUNDED;
            case "NOTPAY", "USERPAYING" -> OrchPaymentStatus.PROCESSING;
            case "CLOSED", "REVOKED" -> OrchPaymentStatus.CANCELLED;
            case "PAYERROR" -> OrchPaymentStatus.FAILED;
            default -> null;
        };
    }

    /**
     * 支付宝 trade_status 映射到 OrchPaymentStatus。
     */
    private OrchPaymentStatus mapAlipayTradeStatus(String tradeStatus) {
        if (tradeStatus == null) return null;
        return switch (tradeStatus) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> OrchPaymentStatus.SUCCEEDED;
            case "WAIT_BUYER_PAY" -> OrchPaymentStatus.PROCESSING;
            case "TRADE_CLOSED" -> OrchPaymentStatus.CANCELLED;
            case "TRADE_REFUND" -> OrchPaymentStatus.REFUNDED;
            default -> null;
        };
    }

    /**
     * 微信回调成功响应。
     */
    private ResponseEntity<Map<String, Object>> successResponse() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "SUCCESS");
        resp.put("message", "成功");
        return ResponseEntity.ok(resp);
    }

    /**
     * 微信回调失败响应。
     */
    private ResponseEntity<Map<String, Object>> failResponse(String message) {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("code", "FAIL");
        resp.put("message", message);
        return ResponseEntity.status(HttpStatus.OK).body(resp);
    }
}