package org.nexus.signing.mpc.security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

/**
 * mTLS（双向 TLS）上下文配置。
 *
 * <p>MPC 协议要求所有参与者之间的传输通道双向认证，防止中间人攻击与
 * 未授权节点加入。该类封装构建 {@link SSLContext} 所需的密钥库与信任库
 * 路径，并提供 {@link #createSslContext()} 工厂方法。</p>
 *
 * <p><b>配置项</b>（通过 Spring 属性或环境变量）：</p>
 * <ul>
 *   <li>{@code nexus.mpc.tls.keystore.path}：本节点身份密钥库（PKCS12/JKS）</li>
 *   <li>{@code nexus.mpc.tls.keystore.password}：密钥库密码</li>
 *   <li>{@code nexus.mpc.tls.truststore.path}：信任库（包含所有参与者证书）</li>
 *   <li>{@code nexus.mpc.tls.truststore.password}：信任库密码</li>
 * </ul>
 *
 * <p>构建出的 {@link SSLContext} 已初始化为客户端/服务端双向认证模式，
 * 可用于 gRPC Netty channel 或 HTTPSURLConnection。</p>
 */
public final class MutualTlsContext {

    private final Path keystorePath;
    private final char[] keystorePassword;
    private final Path truststorePath;
    private final char[] truststorePassword;
    private final String keystoreType;

    /**
     * 构造 mTLS 上下文。
     *
     * @param keystorePath      密钥库路径
     * @param keystorePassword  密钥库密码
     * @param truststorePath    信任库路径
     * @param truststorePassword 信任库密码
     * @param keystoreType      密钥库类型（"PKCS12" 或 "JKS"）
     */
    public MutualTlsContext(String keystorePath, String keystorePassword,
                            String truststorePath, String truststorePassword,
                            String keystoreType) {
        this.keystorePath = Paths.get(keystorePath);
        this.keystorePassword = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
        this.truststorePath = Paths.get(truststorePath);
        this.truststorePassword = truststorePassword == null ? new char[0] : truststorePassword.toCharArray();
        this.keystoreType = keystoreType == null ? "PKCS12" : keystoreType;
    }

    /**
     * 创建并初始化 {@link SSLContext}（TLSv1.3 优先，回退 TLSv1.2）。
     *
     * @return 已初始化的 SSLContext
     * @throws IllegalStateException 若任何密钥库加载失败
     */
    public SSLContext createSslContext() {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(loadKeystore(keystorePath, keystorePassword), keystorePassword);

            TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(loadKeystore(truststorePath, truststorePassword));

            SSLContext ctx;
            try {
                ctx = SSLContext.getInstance("TLSv1.3");
            } catch (NoSuchAlgorithmException e) {
                ctx = SSLContext.getInstance("TLSv1.2");
            }
            ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("mTLS SSLContext init failed: " + e.getMessage(), e);
        }
    }

    private KeyStore loadKeystore(Path path, char[] password)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        KeyStore ks = KeyStore.getInstance(keystoreType);
        try (FileInputStream in = new FileInputStream(path.toFile())) {
            ks.load(in, password);
        }
        return ks;
    }

    public Path getKeystorePath() { return keystorePath; }
    public Path getTruststorePath() { return truststorePath; }
    public String getKeystoreType() { return keystoreType; }
}