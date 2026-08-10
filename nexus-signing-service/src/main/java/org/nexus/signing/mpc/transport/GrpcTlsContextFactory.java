package org.nexus.signing.mpc.transport;

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Objects;

/**
 * gRPC mTLS（双向 TLS）配置构建工具（MPC-P0-02 修复）。
 *
 * <p>提供统一的 {@link SslContext} 构建方法，供 gRPC 客户端
 * （{@link GrpcMpcCryptoEngine} / {@link GrpcMpcTransportStub}）与
 * gRPC 服务端（{@link MpcTransportGrpcServer}）共用。</p>
 *
 * <h2>配置项</h2>
 * <p>mTLS 需要以下三类文件（PEM 格式）：</p>
 * <ul>
 *   <li><b>trust-cert-path</b>：信任证书（CA 证书或对端证书），用于验证对端身份</li>
 *   <li><b>client-cert-path</b>：本节点证书，用于向对端证明身份</li>
 *   <li><b>client-key-path</b>：本节点私钥，与 client-cert 配对</li>
 * </ul>
 *
 * <h2>客户端 mTLS</h2>
 * <p>客户端需同时提供 trust-cert（验证服务端）+ client-cert + client-key
 * （向服务端证明客户端身份）。由 {@link #buildClientSslContext} 构建。</p>
 *
 * <h2>服务端 mTLS</h2>
 * <p>服务端需提供 client-cert（作为服务端证书）+ client-key（作为服务端私钥）
 * + trust-cert（用于验证客户端证书，强制客户端认证）。由 {@link #buildServerSslContext}
 * 构建。</p>
 *
 * <h2>安全约束</h2>
 * <ul>
 *   <li>所有路径必须非空且指向可读文件，否则抛出 {@link IllegalArgumentException}</li>
 *   <li>私钥文件权限应由部署方通过 OS 文件权限控制（chmod 600）</li>
 *   <li>TLS 协议版本由 {@link GrpcSslContexts} 默认配置（TLSv1.2 / TLSv1.3）</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>本类仅提供静态工厂方法，无共享状态，线程安全。</p>
 *
 * @see GrpcMpcCryptoEngine
 * @see GrpcMpcTransportStub
 * @see MpcTransportGrpcServer
 */
public final class GrpcTlsContextFactory {

    private static final Logger log = LoggerFactory.getLogger(GrpcTlsContextFactory.class);

    private GrpcTlsContextFactory() {
        // 工具类，禁止实例化
    }

    /**
     * 构建 gRPC 客户端 mTLS {@link SslContext}。
     *
     * <p>客户端使用 trust-cert 验证服务端证书，使用 client-cert + client-key
     * 向服务端证明自身身份（双向认证）。</p>
     *
     * @param trustCertPath 信任证书路径（PEM，CA 证书或服务端证书），非空
     * @param clientCertPath 客户端证书路径（PEM），非空
     * @param clientKeyPath  客户端私钥路径（PEM，未加密），非空
     * @return 已配置的客户端 {@link SslContext}
     * @throws IllegalArgumentException 若任一路径为空或文件不存在
     * @throws IllegalStateException     若 SSL 上下文构建失败
     */
    public static SslContext buildClientSslContext(String trustCertPath,
                                                   String clientCertPath,
                                                   String clientKeyPath) {
        Objects.requireNonNull(trustCertPath, "trustCertPath");
        Objects.requireNonNull(clientCertPath, "clientCertPath");
        Objects.requireNonNull(clientKeyPath, "clientKeyPath");

        File trustCert = requireReadableFile(trustCertPath, "trust-cert-path");
        File clientCert = requireReadableFile(clientCertPath, "client-cert-path");
        File clientKey = requireReadableFile(clientKeyPath, "client-key-path");

        try {
            SslContextBuilder builder = GrpcSslContexts.forClient()
                    .trustManager(trustCert)
                    .keyManager(clientCert, clientKey);
            SslContext ctx = builder.build();
            log.info("gRPC client mTLS SslContext built: trustCert={}, clientCert={}, clientKey={}",
                    trustCertPath, clientCertPath, clientKeyPath);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to build gRPC client mTLS SslContext: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 gRPC 服务端 mTLS {@link SslContext}。
     *
     * <p>服务端使用 client-cert + client-key 作为服务端证书与私钥，
     * 使用 trust-cert 验证客户端证书（{@link ClientAuth#REQUIRE}），
     * 强制客户端提供有效证书（双向认证）。</p>
     *
     * @param trustCertPath 信任证书路径（PEM，用于验证客户端证书），非空
     * @param serverCertPath 服务端证书路径（PEM），非空
     * @param serverKeyPath  服务端私钥路径（PEM，未加密），非空
     * @return 已配置的服务端 {@link SslContext}
     * @throws IllegalArgumentException 若任一路径为空或文件不存在
     * @throws IllegalStateException     若 SSL 上下文构建失败
     */
    public static SslContext buildServerSslContext(String trustCertPath,
                                                   String serverCertPath,
                                                   String serverKeyPath) {
        Objects.requireNonNull(trustCertPath, "trustCertPath");
        Objects.requireNonNull(serverCertPath, "serverCertPath");
        Objects.requireNonNull(serverKeyPath, "serverKeyPath");

        File trustCert = requireReadableFile(trustCertPath, "trust-cert-path");
        File serverCert = requireReadableFile(serverCertPath, "server-cert-path");
        File serverKey = requireReadableFile(serverKeyPath, "server-key-path");

        try {
            SslContextBuilder builder = SslContextBuilder.forServer(serverCert, serverKey)
                    .trustManager(trustCert)
                    .clientAuth(ClientAuth.REQUIRE);
            SslContext ctx = builder.build();
            log.info("gRPC server mTLS SslContext built: trustCert={}, serverCert={}, serverKey={}",
                    trustCertPath, serverCertPath, serverKeyPath);
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "failed to build gRPC server mTLS SslContext: " + e.getMessage(), e);
        }
    }

    /**
     * 检查 mTLS 配置是否完整（三个路径都非空）。
     *
     * <p>用于在 {@code usePlaintext=false} 时验证 TLS 配置是否齐全。
     * 若不齐全应回退到明文并记录警告，或抛出异常阻止启动。</p>
     *
     * @param trustCertPath  信任证书路径
     * @param clientCertPath 客户端证书路径
     * @param clientKeyPath  客户端私钥路径
     * @return {@code true} 若三个路径都非空且非空白
     */
    public static boolean isTlsConfigComplete(String trustCertPath,
                                              String clientCertPath,
                                              String clientKeyPath) {
        return isNonEmpty(trustCertPath)
                && isNonEmpty(clientCertPath)
                && isNonEmpty(clientKeyPath);
    }

    /**
     * 校验路径非空且指向可读文件。
     *
     * @param path      文件路径
     * @param configKey 配置键名（用于错误消息）
     * @return {@link File} 对象
     * @throws IllegalArgumentException 若路径为空或文件不存在/不可读
     */
    private static File requireReadableFile(String path, String configKey) {
        if (!isNonEmpty(path)) {
            throw new IllegalArgumentException(
                    "TLS config '" + configKey + "' is empty but usePlaintext=false — "
                            + "set " + configKey + " or set use-plaintext=true for dev mode");
        }
        File f = new File(path);
        if (!f.exists() || !f.isFile() || !f.canRead()) {
            throw new IllegalArgumentException(
                    "TLS config '" + configKey + "' = '" + path
                            + "' is not a readable file");
        }
        return f;
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }
}