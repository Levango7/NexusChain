package org.nexus.gateway.sandbox.mock;

import org.nexus.gateway.orchestration.connectors.AlipaySignatureUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付宝回调通知模拟器 — 用于端到端测试回调处理全流程。
 *
 * <p>支付宝异步回调通知以 form 表单参数形式发送到商户配置的 notify_url，
 * 包含交易状态、订单号、支付宝流水号等关键信息，并附带 RSA2 签名供商户验签。</p>
 *
 * <p>回调参数（form 表单，非 JSON）：</p>
 * <ul>
 *   <li>app_id — 支付宝应用 ID</li>
 *   <li>out_trade_no — 商户订单号</li>
 *   <li>trade_no — 支付宝交易流水号</li>
 *   <li>trade_status — 交易状态（TRADE_SUCCESS / WAIT_BUYER_PAY / TRADE_CLOSED 等）</li>
 *   <li>notify_id — 通知 ID</li>
 *   <li>sign — RSA2 签名（商户私钥签名）</li>
 *   <li>sign_type — 签名类型（RSA2）</li>
 * </ul>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * AlipayCallbackSimulator simulator = new AlipayCallbackSimulator(merchantPrivateKey, appId);
 * Map<String, String> params = simulator.buildCallbackParams("ORDER_001", "ALIPAY_TX_001", "TRADE_SUCCESS");
 * // 将 params 作为 form 表单 POST 到 notify_url 进行端到端测试
 * }</pre>
 *
 * <p>签名规则遵循支付宝开放平台规范：参数按 key ASCII 升序排序，
 * 拼接为 key=value 格式，使用 SHA256WithRSA 算法签名，Base64 编码输出。
 * sign 和 sign_type 参数不参与签名内容计算。</p>
 */
public class AlipayCallbackSimulator {

    private final String merchantPrivateKey;  // RSA2 商户私钥（PKCS#8 Base64 编码）
    private final String appId;

    /**
     * 构造支付宝回调模拟器。
     *
     * @param merchantPrivateKey 商户 RSA2 私钥（PKCS#8 Base64 编码）
     * @param appId              支付宝应用 ID
     */
    public AlipayCallbackSimulator(String merchantPrivateKey, String appId) {
        this.merchantPrivateKey = merchantPrivateKey;
        this.appId = appId;
    }

    /**
     * 构造支付宝回调参数 Map（含 RSA2 签名）。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>构造业务参数（app_id, out_trade_no, trade_no, trade_status, notify_id）</li>
     *   <li>使用 {@link AlipaySignatureUtil#generateSignature} 对业务参数签名</li>
     *   <li>添加 sign 和 sign_type=RSA2 参数</li>
     *   <li>返回完整参数 Map，可直接作为 form 表单提交</li>
     * </ol>
     *
     * @param outTradeNo  商户订单号
     * @param tradeNo     支付宝交易流水号
     * @param tradeStatus 交易状态（如 TRADE_SUCCESS、WAIT_BUYER_PAY、TRADE_CLOSED）
     * @return 完整回调参数 Map（含 sign 和 sign_type）
     */
    public Map<String, String> buildCallbackParams(String outTradeNo, String tradeNo, String tradeStatus) {
        // 1. 构造业务参数（不含 sign 和 sign_type）
        Map<String, String> params = new LinkedHashMap<>();
        params.put("app_id", appId);
        params.put("out_trade_no", outTradeNo);
        params.put("trade_no", tradeNo);
        params.put("trade_status", tradeStatus);
        params.put("notify_id", generateNotifyId());

        // 2. 使用商户私钥对业务参数生成 RSA2 签名
        String sign = AlipaySignatureUtil.generateSignature(params, merchantPrivateKey);

        // 3. 添加 sign 和 sign_type 参数（这两个参数不参与签名内容计算）
        params.put("sign", sign);
        params.put("sign_type", "RSA2");

        // 4. 返回完整参数 Map
        return params;
    }

    /**
     * 生成模拟的 notify_id。
     *
     * <p>支付宝回调中的 notify_id 是唯一通知标识，格式为长数字字符串。
     * 此处使用时间戳 + 随机数模拟，确保每次调用产生不同的 notify_id。</p>
     *
     * @return 模拟的 notify_id 字符串
     */
    private String generateNotifyId() {
        return String.valueOf(System.currentTimeMillis()) + (int) (Math.random() * 10000);
    }
}