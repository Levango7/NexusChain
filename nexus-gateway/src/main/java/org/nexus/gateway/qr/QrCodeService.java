package org.nexus.gateway.qr;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.nexus.gateway.OrderService;
import org.nexus.gateway.model.PaymentOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 二维码生成与解析服务。
 *
 * <p>支持两种二维码模式：</p>
 * <ul>
 *   <li><b>PAY（B2C）</b>：商户展示码，格式 {@code nexus:pay?orderId={orderId}&token={qrToken}&amount={amount}&symbol=NEX&payee={payeeAddress}}</li>
 *   <li><b>COLLECT（C2B）</b>：用户展示码，格式 {@code nexus:collect?merchantId={merchantId}&payee={payeeAddress}&payer={payerAddress}}</li>
 * </ul>
 *
 * <p>使用 ZXing 库生成二维码 PNG 图片的 Base64 字符串。</p>
 */
@Service
public class QrCodeService {

    private static final Logger log = LoggerFactory.getLogger(QrCodeService.class);

    /** 二维码 URI scheme */
    private static final String QR_SCHEME = "nexus";

    /** PAY 模式路径 */
    private static final String QR_PATH_PAY = "pay";

    /** COLLECT 模式路径 */
    private static final String QR_PATH_COLLECT = "collect";

    private final OrderService orderService;

    public QrCodeService(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 为订单生成支付二维码内容字符串（商户展示码，B2C 模式）。
     *
     * <p>格式：{@code nexus:pay?orderId={orderId}&token={qrToken}&amount={amount}&symbol=NEX&payee={payeeAddress}}</p>
     *
     * @param orderId 订单 ID
     * @return 二维码内容字符串，若订单不存在返回 null
     */
    public String generatePaymentQrCode(Long orderId) {
        Optional<PaymentOrder> orderOpt = orderService.findById(orderId);
        if (orderOpt.isEmpty()) {
            log.warn("Cannot generate QR code: order not found, orderId={}", orderId);
            return null;
        }

        PaymentOrder order = orderOpt.get();
        String qrToken = order.getQrCodeToken();
        if (qrToken == null || qrToken.isEmpty()) {
            log.warn("Cannot generate QR code: qrCodeToken is null for orderId={}", orderId);
            return null;
        }

        String content = String.format("%s:%s?orderId=%d&token=%s&amount=%s&symbol=%s&payee=%s",
                QR_SCHEME,
                QR_PATH_PAY,
                orderId,
                qrToken,
                order.getAmount().toPlainString(),
                order.getTokenSymbol(),
                order.getPayeeAddress());

        log.info("Generated payment QR code for orderId={}", orderId);
        return content;
    }

    /**
     * 为商户生成收款二维码内容字符串（用户展示码，C2B 模式）。
     *
     * <p>格式：{@code nexus:collect?merchantId={merchantId}&payee={payeeAddress}}</p>
     *
     * @param merchantId   商户 ID
     * @param payeeAddress 收款地址
     * @return 二维码内容字符串
     */
    public String generateMerchantQrCode(Long merchantId, String payeeAddress) {
        String content = String.format("%s:%s?merchantId=%d&payee=%s",
                QR_SCHEME,
                QR_PATH_COLLECT,
                merchantId,
                payeeAddress);

        log.info("Generated merchant QR code for merchantId={}", merchantId);
        return content;
    }

    /**
     * 解析二维码内容，返回 QrPaymentRequest 对象。
     *
     * <p>支持 PAY 和 COLLECT 两种格式的解析。无效格式返回 null。</p>
     *
     * @param qrContent 二维码内容字符串
     * @return 解析后的 QrPaymentRequest，无效格式返回 null
     */
    public QrPaymentRequest parseQrCode(String qrContent) {
        if (qrContent == null || qrContent.isEmpty()) {
            return null;
        }

        try {
            // 格式：nexus:pay?params 或 nexus:collect?params
            URI uri = new URI(qrContent);
            if (!QR_SCHEME.equals(uri.getScheme())) {
                log.warn("Invalid QR code scheme: {}", uri.getScheme());
                return null;
            }

            String path = uri.getSchemeSpecificPart();
            // path 格式：pay?orderId=xxx&token=xxx... 或 collect?merchantId=xxx...
            int queryIndex = path.indexOf('?');
            if (queryIndex < 0) {
                log.warn("Invalid QR code format: no query string in {}", qrContent);
                return null;
            }

            String mode = path.substring(0, queryIndex);
            String query = path.substring(queryIndex + 1);

            Map<String, String> params = parseQueryString(query);

            QrPaymentRequest request = new QrPaymentRequest();

            if (QR_PATH_PAY.equals(mode)) {
                request.setQrType(QrPaymentRequest.QrType.PAY);
                request.setOrderId(parseLong(params.get("orderId")));
                request.setQrToken(params.get("token"));
                request.setAmount(parseBigDecimal(params.get("amount")));
                request.setTokenSymbol(params.getOrDefault("symbol", "NEX"));
                request.setPayeeAddress(params.get("payee"));
            } else if (QR_PATH_COLLECT.equals(mode)) {
                request.setQrType(QrPaymentRequest.QrType.COLLECT);
                request.setMerchantId(parseLong(params.get("merchantId")));
                request.setPayeeAddress(params.get("payee"));
                request.setPayerAddress(params.get("payer"));
                request.setAmount(parseBigDecimal(params.get("amount")));
                request.setTokenSymbol(params.getOrDefault("symbol", "NEX"));
            } else {
                log.warn("Unknown QR code mode: {}", mode);
                return null;
            }

            log.info("Parsed QR code: type={}, orderId={}, merchantId={}",
                    request.getQrType(), request.getOrderId(), request.getMerchantId());
            return request;

        } catch (URISyntaxException e) {
            log.warn("Failed to parse QR code content: {}", qrContent, e);
            return null;
        }
    }

    /**
     * 生成二维码 PNG 图片的 Base64 字符串。
     *
     * @param content 二维码内容
     * @param width   图片宽度（像素）
     * @return Base64 编码的 PNG 图片字符串，失败返回 null
     */
    public String generateQrCodeImage(String content, int width) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, width, hints);

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", outputStream);

            String base64 = Base64.getEncoder().encodeToString(outputStream.toByteArray());
            log.debug("Generated QR code image, width={}, contentLength={}", width, content.length());
            return base64;

        } catch (WriterException e) {
            log.error("Failed to generate QR code image: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            log.error("Unexpected error generating QR code image: {}", e.getMessage(), e);
            return null;
        }
    }

    // --- Private helpers ---

    private Map<String, String> parseQueryString(String query) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                String key = pair.substring(0, idx);
                String value = pair.substring(idx + 1);
                params.put(key, value);
            }
        }
        return params;
    }

    private Long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}