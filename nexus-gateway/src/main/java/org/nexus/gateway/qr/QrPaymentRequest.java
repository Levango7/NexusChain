package org.nexus.gateway.qr;

import java.math.BigDecimal;

/**
 * 扫码支付请求实体（DTO），用于解析二维码内容后传递支付信息。
 *
 * <p>支持两种二维码模式：</p>
 * <ul>
 *   <li><b>PAY（B2C）</b>：商户展示二维码，用户扫码支付。包含 orderId、qrToken、amount、payeeAddress。</li>
 *   <li><b>COLLECT（C2B）</b>：用户展示二维码，商户扫码收款。包含 merchantId、payeeAddress、payerAddress。</li>
 * </ul>
 */
public class QrPaymentRequest {

    /**
     * 二维码类型枚举。
     */
    public enum QrType {
        /** 商户展示码（B2C）：用户扫码支付 */
        PAY,
        /** 用户展示码（C2B）：商户扫码收款 */
        COLLECT
    }

    /** 二维码类型 */
    private QrType qrType;

    /** 订单 ID（PAY 模式） */
    private Long orderId;

    /** 二维码令牌（PAY 模式，用于安全校验，防止伪造） */
    private String qrToken;

    /** 商户 ID（COLLECT 模式） */
    private Long merchantId;

    /** 收款地址 */
    private String payeeAddress;

    /** 付款地址（COLLECT 模式） */
    private String payerAddress;

    /** 金额 */
    private BigDecimal amount;

    /** 代币符号 */
    private String tokenSymbol;

    // --- Getters and Setters ---

    public QrType getQrType() { return qrType; }
    public void setQrType(QrType qrType) { this.qrType = qrType; }

    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }

    public String getQrToken() { return qrToken; }
    public void setQrToken(String qrToken) { this.qrToken = qrToken; }

    public Long getMerchantId() { return merchantId; }
    public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }

    public String getPayeeAddress() { return payeeAddress; }
    public void setPayeeAddress(String payeeAddress) { this.payeeAddress = payeeAddress; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    @Override
    public String toString() {
        return "QrPaymentRequest{" +
                "qrType=" + qrType +
                ", orderId=" + orderId +
                ", qrToken='" + qrToken + '\'' +
                ", merchantId=" + merchantId +
                ", payeeAddress='" + payeeAddress + '\'' +
                ", payerAddress='" + payerAddress + '\'' +
                ", amount=" + amount +
                ", tokenSymbol='" + tokenSymbol + '\'' +
                '}';
    }
}