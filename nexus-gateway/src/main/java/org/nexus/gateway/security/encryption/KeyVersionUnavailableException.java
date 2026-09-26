package org.nexus.gateway.security.encryption;

/**
 * KEK 版本不可用异常 — 指定版本的 KEK 不在缓存中。
 *
 * <p>通常发生在密钥轮换后旧版本 KEK 已归档，但仍有 DEK 引用该版本。
 * 此异常表示 fail-closed：禁止回退至其他版本或明文。</p>
 */
public class KeyVersionUnavailableException extends RuntimeException {

    private final int kekVersion;

    public KeyVersionUnavailableException(int kekVersion) {
        super("KEK 版本 " + kekVersion + " 不可用");
        this.kekVersion = kekVersion;
    }

    public int getKekVersion() {
        return kekVersion;
    }
}