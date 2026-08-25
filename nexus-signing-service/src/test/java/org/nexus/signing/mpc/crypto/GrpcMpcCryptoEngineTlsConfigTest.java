package org.nexus.signing.mpc.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GrpcMpcCryptoEngine} 的 TLS 配置加载逻辑测试（MPC-P0-02 修复验证）。
 *
 * <h2>测试目标</h2>
 * <p>不启动 Spring 上下文，通过反射设置 {@code @Value} 字段后调用 {@link GrpcMpcCryptoEngine#init()}，
 * 验证在不同 TLS 配置组合下的行为：</p>
 * <ul>
 *   <li>{@code usePlaintext=true}：明文模式，channel 创建成功</li>
 *   <li>{@code usePlaintext=false} + TLS 配置完整：mTLS 模式（证书不存在时记录错误并回退）</li>
 *   <li>{@code usePlaintext=false} + TLS 配置不完整：fail-closed，拒绝启动（MPC-P0 安全修复）</li>
 * </ul>
 *
 * <p>JUnit 5（jupiter）。</p>
 */
public class GrpcMpcCryptoEngineTlsConfigTest {

    /**
     * 验证 usePlaintext=true 时 init 成功（明文模式）。
     */
    @Test
    public void testInitWithPlaintextSuccess() {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        ReflectionTestUtils.setField(engine, "host", "localhost");
        ReflectionTestUtils.setField(engine, "port", 50051);
        ReflectionTestUtils.setField(engine, "deadlineTimeoutMillis", 30000L);
        ReflectionTestUtils.setField(engine, "usePlaintext", true);
        ReflectionTestUtils.setField(engine, "tlsTrustCertPath", "");
        ReflectionTestUtils.setField(engine, "tlsClientCertPath", "");
        ReflectionTestUtils.setField(engine, "tlsClientKeyPath", "");

        engine.init();

        // channel 应被创建（明文模式总是成功，即使引擎不可达，channel 是惰性的）
        Object channel = ReflectionTestUtils.getField(engine, "channel");
        assertTrue(channel != null, "channel should be created in plaintext mode");
        Object stub = ReflectionTestUtils.getField(engine, "blockingStub");
        assertTrue(stub != null, "blockingStub should be created in plaintext mode");

        engine.shutdown();
    }

    /**
     * 验证 usePlaintext=false + TLS 配置全空时，init 回退到明文（容错启动）。
     *
     * <p>MPC-P0-02 关键场景：默认配置（use-plaintext=false）但在开发环境未配置 TLS 证书，
     * 应回退到明文并记录错误，而非抛异常阻塞启动。</p>
     */
    @Test
    public void testInitWithTlsDisabledButNoCertFallsBackToPlaintext() {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        ReflectionTestUtils.setField(engine, "host", "localhost");
        ReflectionTestUtils.setField(engine, "port", 50051);
        ReflectionTestUtils.setField(engine, "deadlineTimeoutMillis", 30000L);
        ReflectionTestUtils.setField(engine, "usePlaintext", false);
        ReflectionTestUtils.setField(engine, "tlsTrustCertPath", "");
        ReflectionTestUtils.setField(engine, "tlsClientCertPath", "");
        ReflectionTestUtils.setField(engine, "tlsClientKeyPath", "");

        // 不应抛异常，应回退到明文
        engine.init();

        // MPC-P0 fail-closed：TLS 配置不完整不再回退明文（init 内部捕获异常并置空 channel）
        Object channel = ReflectionTestUtils.getField(engine, "channel");
        assertFalse(channel != null,
                "channel should NOT be created when TLS config incomplete (fail-closed)");

        engine.shutdown();
    }

    /**
     * 验证 usePlaintext=false + TLS 配置指向不存在的文件时，init fail-closed。
     */
    @Test
    public void testInitWithTlsConfiguredButFileNotFoundFallsBack() {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        ReflectionTestUtils.setField(engine, "host", "localhost");
        ReflectionTestUtils.setField(engine, "port", 50051);
        ReflectionTestUtils.setField(engine, "deadlineTimeoutMillis", 30000L);
        ReflectionTestUtils.setField(engine, "usePlaintext", false);
        ReflectionTestUtils.setField(engine, "tlsTrustCertPath", "/nonexistent/ca.pem");
        ReflectionTestUtils.setField(engine, "tlsClientCertPath", "/nonexistent/client.pem");
        ReflectionTestUtils.setField(engine, "tlsClientKeyPath", "/nonexistent/client.key");

        // 不应抛异常（init 内部 catch），但必须 fail-closed：channel=null
        engine.init();

        Object channel = ReflectionTestUtils.getField(engine, "channel");
        assertFalse(channel != null, "channel should be null when TLS config invalid");

        engine.shutdown();
    }

    /**
     * 验证 usePlaintext=false + 部分TLS配置（仅 trust-cert）时，init fail-closed 拒绝启动。
     *
     * <p>MPC-P0 安全修复：TLS 配置不完整不再回退明文（原 fail-open 行为允许
     * 攻击者通过配置缺失降级为明文连接绕过 mTLS），必须显式设置
     * {@code use-plaintext=true}（仅限开发环境）或提供完整 TLS 配置。</p>
     */
    @Test
    public void testInitWithPartialTlsConfigFallsBackToPlaintext() {
        GrpcMpcCryptoEngine engine = new GrpcMpcCryptoEngine();
        ReflectionTestUtils.setField(engine, "host", "localhost");
        ReflectionTestUtils.setField(engine, "port", 50051);
        ReflectionTestUtils.setField(engine, "deadlineTimeoutMillis", 30000L);
        ReflectionTestUtils.setField(engine, "usePlaintext", false);
        ReflectionTestUtils.setField(engine, "tlsTrustCertPath", "/etc/ca.pem");
        ReflectionTestUtils.setField(engine, "tlsClientCertPath", "");
        ReflectionTestUtils.setField(engine, "tlsClientKeyPath", "");

        engine.init();

        // 配置不完整应 fail-closed（init 内部捕获 IllegalStateException 并置空 channel）
        Object channel = ReflectionTestUtils.getField(engine, "channel");
        assertFalse(channel != null,
                "channel should NOT be created when TLS config incomplete (fail-closed)");

        engine.shutdown();
    }
}