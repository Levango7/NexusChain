package org.nexus.gateway.security.exception;

/**
 * 密钥版本不可用异常。KEK 版本在 kekCache 中不存在时抛出，
 * 遵循 fail-closed 策略拒绝解密操作。
 *
 * <p>错误码 40002，HTTP 503。设计依据：Wave 12 设计文档 §8.2.2、§8.3.1。</p>
 */
public class KeyVersionUnavailableException extends SecurityException {

    private final int kekVersion;

    public KeyVersionUnavailableException(int kekVersion) {
        super("40002", 503, "KEK version " + kekVersion + " is unavailable");
        this.kekVersion = kekVersion;
    }

    public KeyVersionUnavailableException(int kekVersion, String message) {
        super("40002", 503, message);
        this.kekVersion = kekVersion;
    }

    public int getKekVersion() {
        return kekVersion;
    }
}