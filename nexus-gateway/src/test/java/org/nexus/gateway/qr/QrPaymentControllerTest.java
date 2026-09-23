package org.nexus.gateway.qr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.PaymentService;
import org.nexus.gateway.config.GlobalExceptionHandler;
import org.nexus.gateway.dto.CreateOrderRequest;
import org.nexus.gateway.dto.PaymentResult;
import org.nexus.gateway.model.PaymentOrder;
import org.nexus.gateway.security.MerchantOwnershipGuard;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * QrPaymentController 单元测试 — 使用 MockMvc + Mockito 验证扫码支付 API 端点。
 *
 * <p>采用 standaloneSetup（无 Spring 上下文），注入 GlobalExceptionHandler 使异常处理
 * 与 production 行为一致。所有依赖均通过 Mockito mock。</p>
 */
class QrPaymentControllerTest {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private QrCodeService qrCodeService;
    private OrderService orderService;
    private PaymentService paymentService;
    private MerchantOwnershipGuard ownershipGuard;

    @BeforeEach
    void setUp() {
        qrCodeService = mock(QrCodeService.class);
        orderService = mock(OrderService.class);
        paymentService = mock(PaymentService.class);
        ownershipGuard = mock(MerchantOwnershipGuard.class);

        QrPaymentController controller = new QrPaymentController(
                qrCodeService, orderService, paymentService, ownershipGuard);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    // ==================== POST /api/v1/qr/generate ====================

    @Test
    @DisplayName("生成二维码：订单存在且 PENDING -> 200 + qrContent + qrImage")
    void generateQrCodeSuccess() throws Exception {
        Long orderId = 1001L;
        Long merchantId = 500L;

        // 准备 mock 订单
        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setOrderNo("ORD-1001");
        order.setMerchantId(merchantId);
        order.setAmount(new BigDecimal("500.00"));
        order.setTokenSymbol("NEX");
        order.setPayeeAddress("0xAa1Bb2Cc3Dd4Ee5Ff6");
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));
        order.setQrCodeToken("qr-token-abc");

        when(ownershipGuard.requireMerchantId(any())).thenReturn(merchantId);
        when(orderService.findById(orderId)).thenReturn(Optional.of(order));
        when(qrCodeService.generatePaymentQrCode(orderId))
                .thenReturn("nexus:pay?orderId=1001&token=qr-token-abc&amount=500.00&symbol=NEX&payee=0xAa1Bb2Cc3Dd4Ee5Ff6");
        when(qrCodeService.generateQrCodeImage(anyString(), eq(300)))
                .thenReturn("iVBORw0KGgoAAAANSUhEUg==");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("orderId", orderId);

        mockMvc.perform(post("/api/v1/qr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrContent").exists())
                .andExpect(jsonPath("$.qrImage").exists())
                .andExpect(jsonPath("$.orderId").value(orderId))
                .andExpect(jsonPath("$.orderNo").value("ORD-1001"))
                .andExpect(jsonPath("$.amount").isNumber())
                .andExpect(jsonPath("$.tokenSymbol").value("NEX"))
                .andExpect(jsonPath("$.payeeAddress").value("0xAa1Bb2Cc3Dd4Ee5Ff6"));
    }

    @Test
    @DisplayName("生成二维码：订单不存在 -> 400（IllegalArgumentException 被 GlobalExceptionHandler 映射）")
    void generateQrCodeOrderNotFound() throws Exception {
        Long orderId = 999L;
        Long merchantId = 500L;

        when(ownershipGuard.requireMerchantId(any())).thenReturn(merchantId);
        when(orderService.findById(orderId)).thenReturn(Optional.empty());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("orderId", orderId);

        // 注意：generateQrCode 中订单不存在时抛出 IllegalArgumentException，
        // GlobalExceptionHandler 将其映射为 400 Bad Request
        mockMvc.perform(post("/api/v1/qr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("生成二维码：订单已过期 -> 400")
    void generateQrCodeOrderExpired() throws Exception {
        Long orderId = 1002L;
        Long merchantId = 500L;

        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setMerchantId(merchantId);
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().minusMinutes(10)); // 已过期

        when(ownershipGuard.requireMerchantId(any())).thenReturn(merchantId);
        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("orderId", orderId);

        mockMvc.perform(post("/api/v1/qr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Order has expired"));
    }

    @Test
    @DisplayName("生成二维码：订单状态非 PENDING -> 400")
    void generateQrCodeOrderNotPending() throws Exception {
        Long orderId = 1003L;
        Long merchantId = 500L;

        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setMerchantId(merchantId);
        order.setStatus(PaymentOrder.OrderStatus.PAID); // 已支付
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));

        when(ownershipGuard.requireMerchantId(any())).thenReturn(merchantId);
        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("orderId", orderId);

        mockMvc.perform(post("/api/v1/qr/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Order is not in PENDING status"));
    }

    // ==================== GET /api/v1/qr/parse ====================

    @Test
    @DisplayName("解析二维码：PAY 模式 -> 200 + 正确的 QrPaymentRequest")
    void parseQrCodePayMode() throws Exception {
        String qrContent = "nexus:pay?orderId=1001&token=abc&amount=500&symbol=NEX&payee=0xPayee";

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY);
        qrRequest.setOrderId(1001L);
        qrRequest.setQrToken("abc");
        qrRequest.setAmount(new BigDecimal("500"));
        qrRequest.setTokenSymbol("NEX");
        qrRequest.setPayeeAddress("0xPayee");

        when(qrCodeService.parseQrCode(qrContent)).thenReturn(qrRequest);

        mockMvc.perform(get("/api/v1/qr/parse")
                        .param("content", qrContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrType").value("PAY"))
                .andExpect(jsonPath("$.orderId").value(1001))
                .andExpect(jsonPath("$.qrToken").value("abc"))
                .andExpect(jsonPath("$.tokenSymbol").value("NEX"))
                .andExpect(jsonPath("$.payeeAddress").value("0xPayee"));
    }

    @Test
    @DisplayName("解析二维码：COLLECT 模式 -> 200 + 正确的 QrPaymentRequest")
    void parseQrCodeCollectMode() throws Exception {
        String qrContent = "nexus:collect?merchantId=2001&payee=0xMerchantPayee&payer=0xPayer";

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.COLLECT);
        qrRequest.setMerchantId(2001L);
        qrRequest.setPayeeAddress("0xMerchantPayee");
        qrRequest.setPayerAddress("0xPayer");
        qrRequest.setAmount(new BigDecimal("300"));
        qrRequest.setTokenSymbol("NEX");

        when(qrCodeService.parseQrCode(qrContent)).thenReturn(qrRequest);

        mockMvc.perform(get("/api/v1/qr/parse")
                        .param("content", qrContent))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrType").value("COLLECT"))
                .andExpect(jsonPath("$.merchantId").value(2001))
                .andExpect(jsonPath("$.payeeAddress").value("0xMerchantPayee"))
                .andExpect(jsonPath("$.payerAddress").value("0xPayer"));
    }

    @Test
    @DisplayName("解析二维码：无效内容 -> 400")
    void parseQrCodeInvalidContent() throws Exception {
        when(qrCodeService.parseQrCode("invalid-content")).thenReturn(null);

        mockMvc.perform(get("/api/v1/qr/parse")
                        .param("content", "invalid-content"))
                .andExpect(status().isBadRequest());
    }

    // ==================== POST /api/v1/qr/pay ====================

    @Test
    @DisplayName("扫码支付：qrToken 匹配 + PENDING 订单 -> 200 + 调用 initiatePayment")
    void payByQrCodeSuccess() throws Exception {
        Long orderId = 1001L;
        String qrToken = "qr-token-abc";
        String payerAddress = "0xPayerAddr";

        // mock 二维码解析
        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY);
        qrRequest.setOrderId(orderId);
        qrRequest.setQrToken(qrToken);
        qrRequest.setPayeeAddress("0xPayee");

        when(qrCodeService.parseQrCode(anyString())).thenReturn(qrRequest);

        // mock 订单
        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setQrCodeToken(qrToken); // token 匹配
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));

        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        // mock 支付结果
        PaymentResult paymentResult = PaymentResult.pending("ORD-1001", "https://checkout.example.com");
        when(paymentService.initiatePayment(orderId, payerAddress)).thenReturn(paymentResult);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", "nexus:pay?orderId=1001&token=qr-token-abc");
        requestBody.put("payerAddress", payerAddress);

        mockMvc.perform(post("/api/v1/qr/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-1001"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 验证 paymentService.initiatePayment 被调用
        verify(paymentService).initiatePayment(orderId, payerAddress);
    }

    @Test
    @DisplayName("扫码支付：qrToken 不匹配 -> 403")
    void payByQrCodeTokenMismatch() throws Exception {
        Long orderId = 1001L;

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY);
        qrRequest.setOrderId(orderId);
        qrRequest.setQrToken("wrong-token");

        when(qrCodeService.parseQrCode(anyString())).thenReturn(qrRequest);

        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setQrCodeToken("correct-token"); // 不匹配
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));

        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", "nexus:pay?orderId=1001&token=wrong-token");
        requestBody.put("payerAddress", "0xPayerAddr");

        mockMvc.perform(post("/api/v1/qr/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Invalid QR token"));

        // 验证 paymentService.initiatePayment 未被调用
        verify(paymentService, never()).initiatePayment(anyLong(), anyString());
    }

    @Test
    @DisplayName("扫码支付：订单已过期 -> 400")
    void payByQrCodeOrderExpired() throws Exception {
        Long orderId = 1001L;
        String qrToken = "qr-token-abc";

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY);
        qrRequest.setOrderId(orderId);
        qrRequest.setQrToken(qrToken);

        when(qrCodeService.parseQrCode(anyString())).thenReturn(qrRequest);

        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setQrCodeToken(qrToken); // token 匹配
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().minusMinutes(10)); // 已过期

        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", "nexus:pay?orderId=1001&token=qr-token-abc");
        requestBody.put("payerAddress", "0xPayerAddr");

        mockMvc.perform(post("/api/v1/qr/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Order has expired"));

        verify(paymentService, never()).initiatePayment(anyLong(), anyString());
    }

    @Test
    @DisplayName("扫码支付：订单不存在 -> 404")
    void payByQrCodeOrderNotFound() throws Exception {
        Long orderId = 999L;

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY);
        qrRequest.setOrderId(orderId);
        qrRequest.setQrToken("some-token");

        when(qrCodeService.parseQrCode(anyString())).thenReturn(qrRequest);
        when(orderService.findById(orderId)).thenReturn(Optional.empty());

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", "nexus:pay?orderId=999&token=some-token");
        requestBody.put("payerAddress", "0xPayerAddr");

        mockMvc.perform(post("/api/v1/qr/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Order not found: 999"));
    }

    @Test
    @DisplayName("扫码支付：通过 orderId + qrToken 直接支付（无 qrContent） -> 200")
    void payByQrCodeWithOrderIdAndToken() throws Exception {
        Long orderId = 1001L;
        String qrToken = "qr-token-abc";
        String payerAddress = "0xPayerAddr";

        // 不传 qrContent，直接传 orderId + qrToken
        PaymentOrder order = new PaymentOrder();
        order.setId(orderId);
        order.setQrCodeToken(qrToken);
        order.setStatus(PaymentOrder.OrderStatus.PENDING);
        order.setExpiresAt(LocalDateTime.now().plusMinutes(30));

        when(orderService.findById(orderId)).thenReturn(Optional.of(order));

        PaymentResult paymentResult = PaymentResult.pending("ORD-1001", "https://checkout.example.com");
        when(paymentService.initiatePayment(orderId, payerAddress)).thenReturn(paymentResult);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("orderId", orderId);
        requestBody.put("qrToken", qrToken);
        requestBody.put("payerAddress", payerAddress);

        mockMvc.perform(post("/api/v1/qr/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk());

        verify(paymentService).initiatePayment(orderId, payerAddress);
    }

    // ==================== POST /api/v1/qr/collect ====================

    @Test
    @DisplayName("商户扫码收款：merchantId 匹配 -> 200 + 创建订单 + 发起支付")
    void collectByQrCodeSuccess() throws Exception {
        Long merchantId = 500L;
        String payerAddress = "0xPayerAddr";
        String qrContent = "nexus:collect?merchantId=500&payee=0xMerchantPayee&payer=0xPayerAddr&amount=300";

        // mock 二维码解析
        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.COLLECT);
        qrRequest.setMerchantId(merchantId);
        qrRequest.setPayeeAddress("0xMerchantPayee");
        qrRequest.setPayerAddress(payerAddress);
        qrRequest.setAmount(new BigDecimal("300"));
        qrRequest.setTokenSymbol("NEX");

        when(qrCodeService.parseQrCode(qrContent)).thenReturn(qrRequest);
        when(ownershipGuard.requireMerchantId(any())).thenReturn(merchantId);

        // mock 创建订单
        PaymentOrder newOrder = new PaymentOrder();
        newOrder.setId(2001L);
        newOrder.setOrderNo("ORD-2001");
        newOrder.setMerchantId(merchantId);
        when(orderService.createOrder(any(CreateOrderRequest.class))).thenReturn(newOrder);

        // mock 支付结果
        PaymentResult paymentResult = PaymentResult.pending("ORD-2001", "https://checkout.example.com");
        when(paymentService.initiatePayment(2001L, payerAddress)).thenReturn(paymentResult);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", qrContent);
        requestBody.put("description", "QR Collect Payment");

        mockMvc.perform(post("/api/v1/qr/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderNo").value("ORD-2001"));

        // 验证创建了订单并发起了支付
        verify(orderService).createOrder(any(CreateOrderRequest.class));
        verify(paymentService).initiatePayment(2001L, payerAddress);
    }

    @Test
    @DisplayName("商户扫码收款：merchantId 不匹配 -> 403")
    void collectByQrCodeMerchantIdMismatch() throws Exception {
        Long callerMerchantId = 500L;
        Long qrMerchantId = 600L; // 不匹配
        String qrContent = "nexus:collect?merchantId=600&payee=0xPayee&payer=0xPayer";

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.COLLECT);
        qrRequest.setMerchantId(qrMerchantId);
        qrRequest.setPayeeAddress("0xPayee");
        qrRequest.setPayerAddress("0xPayer");

        when(qrCodeService.parseQrCode(qrContent)).thenReturn(qrRequest);
        when(ownershipGuard.requireMerchantId(any())).thenReturn(callerMerchantId);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", qrContent);

        mockMvc.perform(post("/api/v1/qr/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Merchant ID mismatch"));

        verify(orderService, never()).createOrder(any());
        verify(paymentService, never()).initiatePayment(anyLong(), anyString());
    }

    @Test
    @DisplayName("商户扫码收款：二维码非 COLLECT 模式 -> 400")
    void collectByQrCodeWrongMode() throws Exception {
        String qrContent = "nexus:pay?orderId=1&token=abc";

        QrPaymentRequest qrRequest = new QrPaymentRequest();
        qrRequest.setQrType(QrPaymentRequest.QrType.PAY); // PAY 模式，不是 COLLECT

        when(qrCodeService.parseQrCode(qrContent)).thenReturn(qrRequest);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("qrContent", qrContent);

        mockMvc.perform(post("/api/v1/qr/collect")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid QR code for COLLECT mode"));
    }
}