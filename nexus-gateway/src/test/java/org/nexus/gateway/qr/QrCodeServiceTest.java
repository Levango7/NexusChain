package org.nexus.gateway.qr;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.model.PaymentOrder;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * QrCodeService 单元测试 — 验证二维码生成与解析逻辑。
 *
 * <p>使用 Mockito mock OrderService 依赖，直接实例化 QrCodeService。</p>
 */
class QrCodeServiceTest {

    private OrderService orderService;
    private QrCodeService qrCodeService;

    @BeforeEach
    void setUp() {
        orderService = mock(OrderService.class);
        qrCodeService = new QrCodeService(orderService);
    }

    // ==================== generatePaymentQrCode ====================

    @Test
    @DisplayName("生成支付二维码：订单存在且 qrCodeToken 有效 -> 返回正确格式的二维码内容")
    void generatePaymentQrCodeReturnsValidContent() {
        // 准备 mock 订单数据
        PaymentOrder order = new PaymentOrder();
        order.setId(1001L);
        order.setQrCodeToken("abc-token-123");
        order.setAmount(new BigDecimal("500.00"));
        order.setTokenSymbol("NEX");
        order.setPayeeAddress("0xAa1Bb2Cc3Dd4Ee5Ff6");
        when(orderService.findById(1001L)).thenReturn(Optional.of(order));

        // 执行
        String content = qrCodeService.generatePaymentQrCode(1001L);

        // 验证二维码内容格式：nexus:pay?orderId=...&token=...&amount=...&symbol=...&payee=...
        assertNotNull(content);
        assertTrue(content.startsWith("nexus:pay?"));
        assertTrue(content.contains("orderId=1001"));
        assertTrue(content.contains("token=abc-token-123"));
        assertTrue(content.contains("amount=500.00"));
        assertTrue(content.contains("symbol=NEX"));
        assertTrue(content.contains("payee=0xAa1Bb2Cc3Dd4Ee5Ff6"));
    }

    @Test
    @DisplayName("生成支付二维码：订单不存在 -> 返回 null")
    void generatePaymentQrCodeOrderNotFoundReturnsNull() {
        when(orderService.findById(999L)).thenReturn(Optional.empty());

        String content = qrCodeService.generatePaymentQrCode(999L);

        assertNull(content);
    }

    @Test
    @DisplayName("生成支付二维码：订单存在但 qrCodeToken 为 null -> 返回 null")
    void generatePaymentQrCodeNullTokenReturnsNull() {
        PaymentOrder order = new PaymentOrder();
        order.setId(1002L);
        order.setQrCodeToken(null); // qrCodeToken 为 null
        order.setAmount(new BigDecimal("100"));
        order.setTokenSymbol("NEX");
        order.setPayeeAddress("0xPayee");
        when(orderService.findById(1002L)).thenReturn(Optional.of(order));

        String content = qrCodeService.generatePaymentQrCode(1002L);

        assertNull(content);
    }

    @Test
    @DisplayName("生成支付二维码：订单存在但 qrCodeToken 为空字符串 -> 返回 null")
    void generatePaymentQrCodeEmptyTokenReturnsNull() {
        PaymentOrder order = new PaymentOrder();
        order.setId(1003L);
        order.setQrCodeToken(""); // qrCodeToken 为空字符串
        order.setAmount(new BigDecimal("100"));
        order.setTokenSymbol("NEX");
        order.setPayeeAddress("0xPayee");
        when(orderService.findById(1003L)).thenReturn(Optional.of(order));

        String content = qrCodeService.generatePaymentQrCode(1003L);

        assertNull(content);
    }

    // ==================== generateMerchantQrCode ====================

    @Test
    @DisplayName("生成商户收款二维码：返回正确格式的二维码内容")
    void generateMerchantQrCodeReturnsValidContent() {
        String content = qrCodeService.generateMerchantQrCode(2001L, "0xMerchantPayee");

        assertNotNull(content);
        assertTrue(content.startsWith("nexus:collect?"));
        assertTrue(content.contains("merchantId=2001"));
        assertTrue(content.contains("payee=0xMerchantPayee"));
    }

    @Test
    @DisplayName("生成商户收款二维码：不同 merchantId 和 payeeAddress -> 内容正确反映参数")
    void generateMerchantQrCodeDifferentParams() {
        String content = qrCodeService.generateMerchantQrCode(9999L, "0xDifferentAddr");

        assertNotNull(content);
        assertTrue(content.contains("merchantId=9999"));
        assertTrue(content.contains("payee=0xDifferentAddr"));
    }

    // ==================== parseQrCode — PAY 模式 ====================

    @Test
    @DisplayName("解析二维码：PAY 模式 -> 返回正确的 QrPaymentRequest")
    void parseQrCodePayModeReturnsCorrectRequest() {
        String qrContent = "nexus:pay?orderId=1001&token=abc-token-123&amount=500.00&symbol=NEX&payee=0xAa1Bb2Cc3Dd4Ee5Ff6";

        QrPaymentRequest request = qrCodeService.parseQrCode(qrContent);

        assertNotNull(request);
        assertEquals(QrPaymentRequest.QrType.PAY, request.getQrType());
        assertEquals(1001L, request.getOrderId());
        assertEquals("abc-token-123", request.getQrToken());
        assertEquals(new BigDecimal("500.00"), request.getAmount());
        assertEquals("NEX", request.getTokenSymbol());
        assertEquals("0xAa1Bb2Cc3Dd4Ee5Ff6", request.getPayeeAddress());
    }

    @Test
    @DisplayName("解析二维码：PAY 模式，symbol 参数缺失 -> 默认使用 NEX")
    void parseQrCodePayModeDefaultSymbol() {
        String qrContent = "nexus:pay?orderId=1001&token=abc&amount=100&payee=0xPayee";

        QrPaymentRequest request = qrCodeService.parseQrCode(qrContent);

        assertNotNull(request);
        assertEquals(QrPaymentRequest.QrType.PAY, request.getQrType());
        assertEquals("NEX", request.getTokenSymbol());
    }

    // ==================== parseQrCode — COLLECT 模式 ====================

    @Test
    @DisplayName("解析二维码：COLLECT 模式 -> 返回正确的 QrPaymentRequest")
    void parseQrCodeCollectModeReturnsCorrectRequest() {
        String qrContent = "nexus:collect?merchantId=2001&payee=0xMerchantPayee&payer=0xPayerAddr&amount=300&symbol=NEX";

        QrPaymentRequest request = qrCodeService.parseQrCode(qrContent);

        assertNotNull(request);
        assertEquals(QrPaymentRequest.QrType.COLLECT, request.getQrType());
        assertEquals(2001L, request.getMerchantId());
        assertEquals("0xMerchantPayee", request.getPayeeAddress());
        assertEquals("0xPayerAddr", request.getPayerAddress());
        assertEquals(new BigDecimal("300"), request.getAmount());
        assertEquals("NEX", request.getTokenSymbol());
    }

    @Test
    @DisplayName("解析二维码：COLLECT 模式，仅含 merchantId 和 payee -> 其他字段为 null")
    void parseQrCodeCollectModeMinimalFields() {
        String qrContent = "nexus:collect?merchantId=2001&payee=0xMerchantPayee";

        QrPaymentRequest request = qrCodeService.parseQrCode(qrContent);

        assertNotNull(request);
        assertEquals(QrPaymentRequest.QrType.COLLECT, request.getQrType());
        assertEquals(2001L, request.getMerchantId());
        assertEquals("0xMerchantPayee", request.getPayeeAddress());
        assertNull(request.getPayerAddress());
        assertNull(request.getAmount());
        assertEquals("NEX", request.getTokenSymbol()); // 默认值
    }

    // ==================== parseQrCode — 无效内容 ====================

    @Test
    @DisplayName("解析二维码：null 内容 -> 返回 null")
    void parseQrCodeNullContentReturnsNull() {
        assertNull(qrCodeService.parseQrCode(null));
    }

    @Test
    @DisplayName("解析二维码：空字符串 -> 返回 null")
    void parseQrCodeEmptyContentReturnsNull() {
        assertNull(qrCodeService.parseQrCode(""));
    }

    @Test
    @DisplayName("解析二维码：错误的 scheme（非 nexus） -> 返回 null")
    void parseQrCodeWrongSchemeReturnsNull() {
        assertNull(qrCodeService.parseQrCode("http:pay?orderId=1"));
    }

    @Test
    @DisplayName("解析二维码：未知模式（非 pay/collect） -> 返回 null")
    void parseQrCodeUnknownModeReturnsNull() {
        assertNull(qrCodeService.parseQrCode("nexus:unknown?param=value"));
    }

    @Test
    @DisplayName("解析二维码：无查询字符串 -> 返回 null")
    void parseQrCodeNoQueryStringReturnsNull() {
        assertNull(qrCodeService.parseQrCode("nexus:pay"));
    }

    @Test
    @DisplayName("解析二维码：URI 语法错误 -> 返回 null")
    void parseQrCodeInvalidUriReturnsNull() {
        // URI 解析会抛出 URISyntaxException，被 catch 后返回 null
        assertNull(qrCodeService.parseQrCode("nexus:pay?orderId=###&token=abc"));
    }

    // ==================== generateQrCodeImage ====================

    @Test
    @DisplayName("生成二维码图片：有效内容 -> 返回非空 Base64 字符串且可解码")
    void generateQrCodeImageReturnsValidBase64() {
        String content = "nexus:pay?orderId=1001&token=abc&amount=100&symbol=NEX&payee=0xPayee";

        String base64Image = qrCodeService.generateQrCodeImage(content, 300);

        assertNotNull(base64Image);
        assertFalse(base64Image.isEmpty());

        // 验证 Base64 可以解码为 PNG 图片数据（PNG 文件头：\x89PNG）
        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        assertNotNull(imageBytes);
        assertTrue(imageBytes.length > 0);
        // PNG 文件以 8 字节签名开头：137 80 78 71 13 10 26 10
        assertEquals((byte) 0x89, imageBytes[0]);
        assertEquals((byte) 0x50, imageBytes[1]); // 'P'
        assertEquals((byte) 0x4E, imageBytes[2]); // 'N'
        assertEquals((byte) 0x47, imageBytes[3]); // 'G'
    }

    @Test
    @DisplayName("生成二维码图片：null 内容 -> 返回 null")
    void generateQrCodeImageNullContentReturnsNull() {
        assertNull(qrCodeService.generateQrCodeImage(null, 300));
    }

    @Test
    @DisplayName("生成二维码图片：空字符串内容 -> 返回 null")
    void generateQrCodeImageEmptyContentReturnsNull() {
        assertNull(qrCodeService.generateQrCodeImage("", 300));
    }

    @Test
    @DisplayName("生成二维码图片：不同宽度参数 -> 均生成有效 Base64 图片")
    void generateQrCodeImageDifferentWidths() {
        String content = "nexus:collect?merchantId=1&payee=0xAddr";

        String img200 = qrCodeService.generateQrCodeImage(content, 200);
        String img500 = qrCodeService.generateQrCodeImage(content, 500);

        assertNotNull(img200);
        assertNotNull(img500);
        assertFalse(img200.isEmpty());
        assertFalse(img500.isEmpty());

        // 不同宽度的图片大小应该不同（较大的图片 Base64 更长）
        assertTrue(img500.length() > img200.length());
    }
}