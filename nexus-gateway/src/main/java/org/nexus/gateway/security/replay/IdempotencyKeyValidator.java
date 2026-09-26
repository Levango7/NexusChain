package org.nexus.gateway.security.replay;

import org.nexus.gateway.security.exception.IdempotencyKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * 幂等性键校验器。
 *
 * <p>校验规则：
 * <ul>
 *   <li>键的 UTF-8 字节长度 ≥ 8 字节（错误码 40110 IDEMPOTENCY_KEY_TOO_SHORT）</li>
 *   <li>键格式匹配 {@code ^[a-zA-Z0-9\\-]+$}（错误码 40111 IDEMPOTENCY_KEY_INVALID_FORMAT）</li>
 * </ul></p>
 *
 * <p>设计依据：Wave 12 设计文档 §6.4.1、§4.5 错误码汇总。</p>
 */
@Component
public class IdempotencyKeyValidator {

    /** 幂等性键最小字节长度。 */
    static final int MIN_KEY_BYTES = 8;

    /** 合法字符集：字母、数字、连字符。 */
    private static final Pattern VALID_FORMAT = Pattern.compile("^[a-zA-Z0-9\\-]+$");

    /**
     * 校验幂等性键的长度和格式。
     *
     * @param idempotencyKey 待校验的幂等性键
     * @throws IdempotencyKeyException 当键长度不足（40110）或格式非法（40111）时
     */
    public void validate(String idempotencyKey) {
        if (idempotencyKey == null) {
            throw new IdempotencyKeyException("40110", 401, "Idempotency key is null");
        }
        int byteLength = idempotencyKey.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < MIN_KEY_BYTES) {
            throw new IdempotencyKeyException("40110", 401,
                    "Idempotency key too short: " + byteLength + " bytes (min " + MIN_KEY_BYTES + " bytes)");
        }
        if (!VALID_FORMAT.matcher(idempotencyKey).matches()) {
            throw new IdempotencyKeyException("40111", 401,
                    "Idempotency key invalid format: only alphanumeric and hyphen allowed");
        }
    }
}