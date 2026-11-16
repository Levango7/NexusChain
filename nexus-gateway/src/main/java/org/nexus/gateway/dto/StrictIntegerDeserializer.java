package org.nexus.gateway.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.exc.InvalidFormatException;

import java.math.BigDecimal;

/**
 * 严格整数反序列化：拒绝「小数被静默截断」。
 *
 * <p><b>动机（B-13 攻击载荷电池实测）</b>：把 {@code expiryMinutes} 传 {@code 3.5}
 * 时，Jackson 默认强转成 {@code 3} 并返回 201——调用方以为订单 3.5 分钟过期，
 * 实际 3 分钟，<b>静默数据丢失</b>。本类把它变成显式 400。</p>
 *
 * <p><b>⚠️ 必须用 Jackson 3（{@code tools.jackson}）的 API</b>：本仓是 Spring Boot 4.0，
 * HTTP 报文转换由 <b>Jackson 3.x</b> 执行；{@code com.fasterxml.jackson}（2.x）的
 * {@code @JsonDeserialize}/{@code JsonDeserializer} 对请求体<b>完全无效</b>
 * （2.x 的 {@code ObjectMapper} bean 只是给遗留组件用的兼容件，见 {@code JacksonConfig}）。
 * 第一版按 2.x 写、编译通过但运行时被静默忽略——实测 {@code 3.5} 仍拿到 201。</p>
 *
 * <p><b>只堵有损路径</b>：整数值的字符串（{@code "3"}）与小数但值为整数（{@code 3.0}）
 * 仍接受（无精度损失、与既有宽松口径一致）；{@code 3.5} 这类一律拒绝。</p>
 */
public class StrictIntegerDeserializer extends ValueDeserializer<Integer> {

    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) {
        String raw = p.getText();
        if (raw == null) {
            throw InvalidFormatException.from(p, "expiryMinutes 期望整数分钟数", null, Integer.class);
        }
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(raw.trim());
        } catch (NumberFormatException nfe) {
            // 非数字文本（如 "abc"）→ 与其它字段同口径：400（由
            // GlobalExceptionHandler 的 HttpMessageNotReadableException 处理器承接）
            throw InvalidFormatException.from(p, "expiryMinutes 期望整数分钟数", raw, Integer.class);
        }
        if (decimal.stripTrailingZeros().scale() > 0) {
            throw InvalidFormatException.from(p,
                    "expiryMinutes 不接受小数（会静默截断，请传整分钟数）", raw, Integer.class);
        }
        try {
            return decimal.intValueExact();
        } catch (ArithmeticException overflow) {
            throw InvalidFormatException.from(p, "expiryMinutes 超出 int 范围", raw, Integer.class);
        }
    }
}
