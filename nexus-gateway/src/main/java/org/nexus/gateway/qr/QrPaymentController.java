package org.nexus.gateway.qr;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.security.MerchantOwnershipException;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 扫码支付 API 端点。
 *
 * <p>提供二维码生成、解析、扫码支付（PAY 模式）和扫码收款（COLLECT 模式）功能。</p>
 *
 * <ul>
 *   <li>{@code POST /api/v1/qr/generate} — 为指定订单生成支付二维码（商户展示码）</li>
 *   <li>{@code GET /api/v1/qr/parse?content={qrContent}} — 解析二维码内容</li>
 *   <li>{@code POST /api/v1/qr/pay} — 用户扫码后发起支付（PAY 模式）</li>
 *   <li>{@code POST /api/v1/qr/collect} — 商户扫码后发起收款（COLLECT 模式）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/qr")
@Tag(name = "QR Payment", description = "扫码支付：二维码生成、解析、支付与收款")
public class QrPaymentController {

    private static final Logger log = LoggerFactory.getLogger(QrPaymentController.class);

    private final QrCodeService qrCodeService;
    private final OrderService orderService;
    private final PaymentService paymentService;
    private final MerchantOwnershipGuard ownershipGuard;

    public QrPaymentController(QrCodeService qrCodeService,
                               OrderService orderService,
                               PaymentService paymentService,
                               MerchantOwnershipGuard ownershipGuard) {
        this.qrCodeService = qrCodeService;
        this.orderService = orderService;
        this.paymentService = paymentService;
        this.ownershipGuard = ownershipGuard;
    }

    /**
     * 为指定订单生成支付二维码（商户展示码，B2C 模式）。
     *
     * <p>返回二维码内容字符串和 Base64 编码的 PNG 图片。</p>
     *
     * @param request 包含 orderId 的请求体
     * @return 二维码内容 + Base64 图片
     */
    @Operation(summary = "为指定订单生成支付二维码")
    @PostMapping("/generate")
    public ResponseEntity<Map<String, Object>> generateQrCode(@RequestBody QrGenerateRequest request,
                                                              HttpServletRequest httpRequest) {
        Long orderId = request.getOrderId();
        if (orderId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId is required"));
        }

        // 商户归属校验
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);
        PaymentOrder order = orderService.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
        ownershipGuard.requireOwned(callerMerchantId, order.getMerchantId(), "order", orderId);

        // 校验订单状态：只有 PENDING 状态的订单才能生成二维码
        if (order.getStatus() != PaymentOrder.OrderStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Order is not in PENDING status",
                    "status", order.getStatus().name()));
        }

        // 校验订单是否过期
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Order has expired"));
        }

        String qrContent = qrCodeService.generatePaymentQrCode(orderId);
        if (qrContent == null) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate QR code"));
        }

        String qrImage = qrCodeService.generateQrCodeImage(qrContent, 300);

        Map<String, Object> response = new HashMap<>();
        response.put("qrContent", qrContent);
        response.put("qrImage", qrImage);
        response.put("orderId", orderId);
        response.put("orderNo", order.getOrderNo());
        response.put("amount", order.getAmount());
        response.put("tokenSymbol", order.getTokenSymbol());
        response.put("payeeAddress", order.getPayeeAddress());
        response.put("expiresAt", order.getExpiresAt());

        return ResponseEntity.ok(response);
    }

    /**
     * 解析二维码内容，返回支付信息。
     *
     * @param content 二维码内容字符串
     * @return 解析后的 QrPaymentRequest
     */
    @Operation(summary = "解析二维码内容")
    @GetMapping("/parse")
    public ResponseEntity<QrPaymentRequest> parseQrCode(@RequestParam String content) {
        QrPaymentRequest request = qrCodeService.parseQrCode(content);
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(request);
    }

    /**
     * 用户扫码后发起支付（PAY 模式）。
     *
     * <p>流程：校验 qrToken → 校验订单有效期 → 调用 paymentService.initiatePayment。</p>
     *
     * @param request 包含二维码解析后的支付信息
     * @return 支付结果
     */
    @Operation(summary = "用户扫码支付（PAY 模式）")
    @PostMapping("/pay")
    public ResponseEntity<?> payByQrCode(@RequestBody QrPayRequest request) {
        if (request.getQrContent() == null && request.getOrderId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "qrContent or orderId is required"));
        }

        QrPaymentRequest qrPayment;
        if (request.getQrContent() != null) {
            qrPayment = qrCodeService.parseQrCode(request.getQrContent());
            if (qrPayment == null || qrPayment.getQrType() != QrPaymentRequest.QrType.PAY) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid QR code for PAY mode"));
            }
        } else {
            // 直接通过 orderId + qrToken 支付
            qrPayment = new QrPaymentRequest();
            qrPayment.setQrType(QrPaymentRequest.QrType.PAY);
            qrPayment.setOrderId(request.getOrderId());
            qrPayment.setQrToken(request.getQrToken());
        }

        Long orderId = qrPayment.getOrderId();
        String qrToken = qrPayment.getQrToken();

        if (orderId == null || qrToken == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "orderId and qrToken are required"));
        }

        // 查找订单
        Optional<PaymentOrder> orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Order not found: " + orderId));
        }

        PaymentOrder order = orderOpt.get();

        // 安全校验：qrToken 必须与订单的 qrCodeToken 一致
        if (!qrToken.equals(order.getQrCodeToken())) {
            log.warn("SECURITY: QR token mismatch for orderId={}, expected={}, got={}",
                    orderId, order.getQrCodeToken(), qrToken);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Invalid QR token"));
        }

        // 校验订单状态
        if (order.getStatus() != PaymentOrder.OrderStatus.PENDING) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Order is not in PENDING status",
                    "status", order.getStatus().name()));
        }

        // 校验订单是否过期
        if (order.getExpiresAt() != null && order.getExpiresAt().isBefore(LocalDateTime.now())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Order has expired"));
        }

        // 使用请求中的 payerAddress，或二维码解析结果中的 payerAddress
        String payerAddress = request.getPayerAddress() != null
                ? request.getPayerAddress()
                : qrPayment.getPayerAddress();

        if (payerAddress == null || payerAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "payerAddress is required"));
        }

        // 调用现有支付服务发起支付（复用现有流程，不绕过风控/合规检查）
        PaymentResult result = paymentService.initiatePayment(orderId, payerAddress);

        log.info("QR payment initiated: orderId={}, payerAddress={}", orderId, payerAddress);
        return ResponseEntity.ok(result);
    }

    /**
     * 商户扫码后发起收款（COLLECT 模式）。
     *
     * <p>流程：解析二维码 → 创建订单 → 发起支付。</p>
     *
     * @param request 包含二维码内容和收款信息
     * @return 支付结果
     */
    @Operation(summary = "商户扫码收款（COLLECT 模式）")
    @PostMapping("/collect")
    public ResponseEntity<?> collectByQrCode(@RequestBody QrCollectRequest request,
                                             HttpServletRequest httpRequest) {
        if (request.getQrContent() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "qrContent is required"));
        }

        QrPaymentRequest qrPayment = qrCodeService.parseQrCode(request.getQrContent());
        if (qrPayment == null || qrPayment.getQrType() != QrPaymentRequest.QrType.COLLECT) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid QR code for COLLECT mode"));
        }

        // 商户归属校验
        Long callerMerchantId = ownershipGuard.requireMerchantId(httpRequest);

        // 校验二维码中的 merchantId 与认证商户一致
        if (qrPayment.getMerchantId() == null
                || !qrPayment.getMerchantId().equals(callerMerchantId)) {
            log.warn("SECURITY: Merchant ID mismatch in COLLECT mode, qrMerchantId={}, callerMerchantId={}",
                    qrPayment.getMerchantId(), callerMerchantId);
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Merchant ID mismatch"));
        }

        // 创建订单
        CreateOrderRequest createOrderRequest = new CreateOrderRequest();
        createOrderRequest.setMerchantId(String.valueOf(callerMerchantId));
        createOrderRequest.setAmount(request.getAmount() != null ? request.getAmount() : qrPayment.getAmount());
        createOrderRequest.setTokenSymbol(qrPayment.getTokenSymbol() != null ? qrPayment.getTokenSymbol() : "NEX");
        createOrderRequest.setDescription(request.getDescription() != null ? request.getDescription() : "QR Collect Payment");
        createOrderRequest.setPayerAddress(qrPayment.getPayerAddress());
        createOrderRequest.setNotifyUrl(request.getNotifyUrl());
        createOrderRequest.setExpiryMinutes(request.getExpiryMinutes());

        PaymentOrder order = orderService.createOrder(createOrderRequest);

        // 发起支付
        String payerAddress = qrPayment.getPayerAddress();
        if (payerAddress == null || payerAddress.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "payerAddress is required in QR code",
                    "orderId", order.getId()));
        }

        PaymentResult result = paymentService.initiatePayment(order.getId(), payerAddress);

        log.info("QR collect payment initiated: orderId={}, merchantId={}, payerAddress={}",
                order.getId(), callerMerchantId, payerAddress);
        return ResponseEntity.ok(result);
    }

    // --- Request DTOs ---

    public static class QrGenerateRequest {
        private Long orderId;

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
    }

    public static class QrPayRequest {
        /** 二维码内容（与 orderId+qrToken 二选一） */
        private String qrContent;
        /** 订单 ID（与 qrContent 二选一） */
        private Long orderId;
        /** 二维码令牌（与 qrContent 二选一，配合 orderId 使用） */
        private String qrToken;
        /** 付款地址（可选，优先于二维码中的 payerAddress） */
        private String payerAddress;

        public String getQrContent() { return qrContent; }
        public void setQrContent(String qrContent) { this.qrContent = qrContent; }

        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }

        public String getQrToken() { return qrToken; }
        public void setQrToken(String qrToken) { this.qrToken = qrToken; }

        public String getPayerAddress() { return payerAddress; }
        public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }
    }

    public static class QrCollectRequest {
        /** 二维码内容 */
        private String qrContent;
        /** 收款金额（可选，优先于二维码中的 amount） */
        private BigDecimal amount;
        /** 订单描述 */
        private String description;
        /** 商户通知 URL */
        private String notifyUrl;
        /** 订单有效期（分钟） */
        private Integer expiryMinutes;

        public String getQrContent() { return qrContent; }
        public void setQrContent(String qrContent) { this.qrContent = qrContent; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }

        public String getNotifyUrl() { return notifyUrl; }
        public void setNotifyUrl(String notifyUrl) { this.notifyUrl = notifyUrl; }

        public Integer getExpiryMinutes() { return expiryMinutes; }
        public void setExpiryMinutes(Integer expiryMinutes) { this.expiryMinutes = expiryMinutes; }
    }
}