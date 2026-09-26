package org.nexus.gateway.security.replay;

import org.nexus.gateway.security.exception.IdempotencyKeyException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Nonce 长度校验器。
 *
 * <p>校验 nonce 的 UTF-8 字节长度 ≥ 16 字节（128 位），不满足则抛出
 * {@link IdempotencyKeyException}（错误码 40109 NONCE_TOO_SHORT）。</p>
 *
 * <p>设计依据：Wave 12 设计文档 §5.2.1-2、§4.5 错误码汇总。</p>
 */
@Component
public class NonceLengthValidator {

    /** nonce 最小字节长度（128 位 = 16 字节）。 */
    static final int MIN_NONCE_BYTES = 16;

    /**
     * 校验 nonce 字节长度。
     *
     * @param nonce 待校验的 nonce 字符串
     * @throws IdempotencyKeyException 当 nonce 字节长度 < 16 时（错误码 40109）
     */
    public void validate(String nonce) {
        if (nonce == null) {
            throw new IdempotencyKeyException("40109", 401, "Nonce is null");
        }
        int byteLength = nonce.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < MIN_NONCE_BYTES) {
            throw new IdempotencyKeyException("40109", 401,
                    "Nonce too short: " + byteLength + " bytes (min " + MIN_NONCE_BYTES + " bytes)");
        }
    }
}