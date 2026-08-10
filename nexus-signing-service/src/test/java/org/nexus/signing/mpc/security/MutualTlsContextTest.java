package org.nexus.signing.mpc.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MutualTlsContext} 单元测试。
 *
 * <p>使用 JDK keytool 生成的临时 PKCS12 密钥库验证 SSLContext 创建逻辑。</p>
 */
public class MutualTlsContextTest {

    @Test
    public void testConstructorAndGetters() {
        MutualTlsContext ctx = new MutualTlsContext(
                "/path/to/keystore.p12", "pass1",
                "/path/to/truststore.p12", "pass2",
                "PKCS12");
        assertEquals(Path.of("/path/to/keystore.p12"), ctx.getKeystorePath());
        assertEquals(Path.of("/path/to/truststore.p12"), ctx.getTruststorePath());
        assertEquals(ctx.getKeystoreType(), "PKCS12");
    }

    @Test
    public void testDefaultKeystoreTypePkcs12() {
        MutualTlsContext ctx = new MutualTlsContext(
                "/a", "p", "/b", "p", null);
        assertEquals(ctx.getKeystoreType(), "PKCS12");
    }

    @Test
    public void testNullPasswordsHandled() {
        MutualTlsContext ctx = new MutualTlsContext(
                "/a", null, "/b", null, "JKS");
        assertEquals(ctx.getKeystoreType(), "JKS");
        // 不抛异常即视为成功
    }

    @Test
    public void testCreateSslContextWithRealKeystore() throws Exception {
        // 生成临时 PKCS12 密钥库（使用 JDK keytool）
        Path tmpDir = Files.createTempDirectory("mtls-test");
        Path keystorePath = tmpDir.resolve("test.p12");
        Path truststorePath = tmpDir.resolve("trust.p12");

        try {
            // 用 keytool 生成自签名证书 + PKCS12 密钥库
            String password = "testpass";
            ProcessBuilder pb = new ProcessBuilder(
                    "keytool", "-genkeypair",
                    "-alias", "test",
                    "-keyalg", "RSA",
                    "-keysize", "2048",
                    "-validity", "1",
                    "-keystore", keystorePath.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", password,
                    "-dname", "CN=test, OU=test, O=test, L=test, ST=test, C=US");
            pb.inheritIO();
            Process p = pb.start();
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                // keytool 不可用，跳过此测试
                return;
            }

            // 复制为 truststore
            Files.copy(keystorePath, truststorePath);

            MutualTlsContext ctx = new MutualTlsContext(
                    keystorePath.toString(), password,
                    truststorePath.toString(), password,
                    "PKCS12");
            javax.net.ssl.SSLContext sslCtx = ctx.createSslContext();
            assertNotNull(sslCtx);
        } finally {
            // 清理
            Files.deleteIfExists(keystorePath);
            Files.deleteIfExists(truststorePath);
            Files.deleteIfExists(tmpDir);
        }
    }
}