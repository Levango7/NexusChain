package org.nexus.signing.mpc.transport;

import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.stub.MetadataUtils;
import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.transport.grpc.MpcTransportProto;
import org.nexus.signing.mpc.transport.grpc.MpcTransportServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * gRPC over HTTP/2 传输层实现（P5-T3 真实化）。
 *
 * <p>该类通过 gRPC 在 Java 参与方之间传递 {@link MpcMessage}（KeyGenRound /
 * SignRound / AggregateRound / Control）。每个 signing-service 实例应启动一个
 * {@code MpcTransportGrpcServer} 接收消息，本类作为 gRPC 客户端向其他参与方发送消息
 * 并从本地邮箱接收消息。</p>
 *
 * <h2>架构</h2>
 * <pre>
 *   Participant A (signing-service)              Participant B (signing-service)
 *   ┌─────────────────────────────┐              ┌─────────────────────────────┐
 *   │ GrpcMpcTransportStub        │              │ GrpcMpcTransportStub        │
 *   │  ├─ channels: {B → channel} │──send(msg)──►│  └─ localMailbox ◄─┐       │
 *   │  └─ localMailbox ◄─┐        │              │                    │       │
 *   │                    │        │              │ MpcTransportGrpcServer      │
 *   │ MpcTransportGrpcServer      │◄─send(msg)───│  └─ stub.send(msg)─┘       │
 *   │  └─ stub.send(msg)─┘        │              └─────────────────────────────┘
 *   └─────────────────────────────┘
 *
 *   A.send(msg.to=B) → B.server.SendMpcMessage(msg) → B.localMailbox.offer(msg)
 *   B.receive(sessionId, round, fromId=A) → B.localMailbox.poll(...)
 * </pre>
 *
 * <h2>工作模式</h2>
 * <ul>
 *   <li><b>{@code realGrpcEnabled=true}</b>（生产模式）：{@link #send} 通过 gRPC stub
 *       调用对端 {@code MpcTransportService.SendMpcMessage}；{@link #receive} 从本地邮箱
 *       取消息（本地邮箱由本节点的 {@code MpcTransportGrpcServer} 填充）。</li>
 *   <li><b>{@code realGrpcEnabled=false}</b>（开发/测试模式）：回退到
 *       {@link InMemoryMpcTransport}，所有参与者位于同一 JVM，不经过网络层。</li>
 * </ul>
 *
 * <h2>本地邮箱</h2>
 * <p>{@link #receive} 从 {@link #localMailbox} 取消息。{@link #deliverLocal} 方法
 * 供本节点的 {@code MpcTransportGrpcServer} 在收到 gRPC 消息时调用，将消息放入邮箱。
 * 邮箱按 (sessionId, round, fromParticipantId) 索引，支持多会话并发。</p>
 *
 * <h2>线程安全</h2>
 * <p>{@link #channels} 与 {@link #localMailbox} 均为并发安全容器；
 * {@link Mailbox} 内部用 {@link ReentrantLock} + {@link Condition} 实现阻塞等待。
 * {@link #send} 与 {@link #receive} 可被不同线程并发调用。</p>
 *
 * <h2>配置</h2>
 * <p>从 {@code application.yml} 的 {@code mpc.transport} 前缀读取：</p>
 * <pre>
 * mpc:
 *   transport:
 *     real-grpc-enabled: false      # 默认 false（开发模式），生产环境设为 true
 *     deadline-timeout: 10000       # gRPC 调用超时（毫秒）
 *     use-plaintext: false          # MPC-P0-02: 默认 false（安全）；开发环境设为 true
 *     tls:                          # mTLS 配置（use-plaintext=false 时生效）
 *       trust-cert-path: /etc/nexus/mpc/ca.pem
 *       client-cert-path: /etc/nexus/mpc/client.pem
 *       client-key-path:  /etc/nexus/mpc/client.key
 * </pre>
 *
 * <h2>mTLS 行为（MPC-P0-02 修复）</h2>
 * <ul>
 *   <li>{@code usePlaintext=true}：使用明文 gRPC（仅开发环境，记录警告）</li>
 *   <li>{@code usePlaintext=false}（默认）+ TLS 配置完整：使用 mTLS（双向认证）</li>
 *   <li>{@code usePlaintext=false} + TLS 配置不完整：抛出 {@link IllegalStateException}
 *       拒绝启动（fail-closed，MPC-P0 修复：防止攻击者通过配置缺失降级明文绕过 mTLS）</li>
 * </ul>
 *
 * @see MpcTransport
 * @see InMemoryMpcTransport
 * @see MpcTransportServiceGrpc
 */
public class GrpcMpcTransportStub implements MpcTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcMpcTransportStub.class);

    /** 默认 gRPC 调用超时（毫秒）。 */
    private static final long DEFAULT_DEADLINE_TIMEOUT_MILLIS = 10_000L;

    /**
     * gRPC metadata 中的 Authorization 头键（MPC-P1-03）。
     *
     * <p>gRPC 使用 ASCII header name，{@link Metadata.Key} 需指定是否为 binary。
     * {@code Authorization} 是 ASCII 字符串头，使用 {@link Metadata.Key#of(String, Metadata.AsciiMarshaller)}。</p>
     */
    private static final Metadata.Key<String> AUTHORIZATION_METADATA_KEY =
            Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);

    /**
     * Authorization 头的 Bearer 前缀（MPC-P1-03）。
     *
     * <p>格式：{@code Authorization: Bearer <token>}，与 OAuth 2.0 / RFC 6750 一致。</p>
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 当前回退使用的内存传输（realGrpcEnabled=false 时使用）。 */
    private final InMemoryMpcTransport fallback = new InMemoryMpcTransport();

    /** 已建立的 gRPC channel（participantId -> ManagedChannel）。 */
    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    /** 已连接的参与者端点描述（participantId -> "grpc://endpoint"），用于诊断。 */
    private final Map<String, String> channelDescriptors = new ConcurrentHashMap<>();

    /** 本地邮箱：(sessionId|round|fromParticipantId) -> Mailbox，供 receive() 取消息。 */
    private final Map<String, Mailbox> localMailbox = new ConcurrentHashMap<>();

    /** 已连接的参与者集合（按 ID 索引），用于广播。 */
    private final Map<String, MpcParticipant> connectedParticipants = new ConcurrentHashMap<>();

    /** 是否启用真实 gRPC（默认 false，使用内存回退）。 */
    private final boolean realGrpcEnabled;

    /** gRPC 调用 deadline 超时（毫秒）。 */
    private final long deadlineTimeoutMillis;

    /** 是否使用明文传输（开发环境）。 */
    private final boolean usePlaintext;

    /** mTLS 信任证书路径（PEM）。usePlaintext=false 且非空时启用 mTLS。 */
    private final String tlsTrustCertPath;

    /** mTLS 客户端证书路径（PEM）。usePlaintext=false 且非空时启用 mTLS。 */
    private final String tlsClientCertPath;

    /** mTLS 客户端私钥路径（PEM，未加密）。usePlaintext=false 且非空时启用 mTLS。 */
    private final String tlsClientKeyPath;

    /**
     * 应用层认证 token（MPC-P1-03）。
     *
     * <p>非空时，{@link #send} 会在 gRPC metadata 中添加
     * {@code Authorization: Bearer <token>} 头，供对端
     * {@link MpcTransportGrpcServer} 校验。生产环境（{@code usePlaintext=false}）
     * 必填；开发环境（{@code usePlaintext=true}）可留空。</p>
     */
    private final String authToken;

    /** 连接状态。 */
    private volatile boolean connected = false;

    /**
     * 构造 gRPC 传输 stub（含 mTLS 配置，MPC-P0-02 修复）。
     *
     * @param realGrpcEnabled      {@code true} 启用真实 gRPC 传输；{@code false} 使用内存回退
     * @param deadlineTimeoutMillis gRPC 调用超时（毫秒），仅 realGrpcEnabled=true 时生效
     * @param usePlaintext         是否使用明文传输（开发环境），仅 realGrpcEnabled=true 时生效
     * @param tlsTrustCertPath     mTLS 信任证书路径（PEM），可为 null/空（明文模式）
     * @param tlsClientCertPath    mTLS 客户端证书路径（PEM），可为 null/空（明文模式）
     * @param tlsClientKeyPath     mTLS 客户端私钥路径（PEM），可为 null/空（明文模式）
     */
    public GrpcMpcTransportStub(boolean realGrpcEnabled,
                                long deadlineTimeoutMillis,
                                boolean usePlaintext,
                                String tlsTrustCertPath,
                                String tlsClientCertPath,
                                String tlsClientKeyPath) {
        this(realGrpcEnabled, deadlineTimeoutMillis, usePlaintext,
                tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath, null);
    }

    /**
     * 构造 gRPC 传输 stub（含 mTLS + 应用层 token 认证，MPC-P0-02 + MPC-P1-03 修复）。
     *
     * <p>MPC-P1-03 修复：添加 {@code authToken} 参数，非空时在 gRPC metadata 中
     * 添加 {@code Authorization: Bearer <token>} 头。生产环境
     * （{@code usePlaintext=false}）应配置非空 token；若 token 为空且
     * {@code usePlaintext=false}，记录 WARN 日志。</p>
     *
     * @param realGrpcEnabled      {@code true} 启用真实 gRPC 传输；{@code false} 使用内存回退
     * @param deadlineTimeoutMillis gRPC 调用超时（毫秒），仅 realGrpcEnabled=true 时生效
     * @param usePlaintext         是否使用明文传输（开发环境），仅 realGrpcEnabled=true 时生效
     * @param tlsTrustCertPath     mTLS 信任证书路径（PEM），可为 null/空（明文模式）
     * @param tlsClientCertPath    mTLS 客户端证书路径（PEM），可为 null/空（明文模式）
     * @param tlsClientKeyPath     mTLS 客户端私钥路径（PEM），可为 null/空（明文模式）
     * @param authToken            应用层认证 token（MPC-P1-03），可为 null/空（开发模式）
     */
    public GrpcMpcTransportStub(boolean realGrpcEnabled,
                                long deadlineTimeoutMillis,
                                boolean usePlaintext,
                                String tlsTrustCertPath,
                                String tlsClientCertPath,
                                String tlsClientKeyPath,
                                String authToken) {
        this.realGrpcEnabled = realGrpcEnabled;
        this.deadlineTimeoutMillis = deadlineTimeoutMillis > 0
                ? deadlineTimeoutMillis : DEFAULT_DEADLINE_TIMEOUT_MILLIS;
        this.usePlaintext = usePlaintext;
        this.tlsTrustCertPath = tlsTrustCertPath;
        this.tlsClientCertPath = tlsClientCertPath;
        this.tlsClientKeyPath = tlsClientKeyPath;
        this.authToken = authToken;
        if (realGrpcEnabled) {
            log.info("GrpcMpcTransportStub: real gRPC mode enabled, deadline={}ms, plaintext={}, tls={}, authToken={}",
                    this.deadlineTimeoutMillis, usePlaintext,
                    !usePlaintext && GrpcTlsContextFactory.isTlsConfigComplete(
                            tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath),
                    authToken != null && !authToken.isEmpty() ? "(set)" : "(empty)");
            // MPC-P1-03: token 为空且 usePlaintext=false 时记录 WARN
            if (!usePlaintext && (authToken == null || authToken.isEmpty())) {
                log.warn("GrpcMpcTransportStub: authToken is empty but usePlaintext=false "
                        + "(MPC-P1-03: production should set mpc.transport.auth-token)");
            }
        } else {
            log.info("GrpcMpcTransportStub: in-memory fallback mode (realGrpcEnabled=false)");
        }
    }

    /**
     * 构造 gRPC 传输 stub（向后兼容，默认无 mTLS）。
     *
     * <p>保留此构造函数以兼容现有测试代码（{@code new GrpcMpcTransportStub(true, 5000, true)}）。
     * 新代码应使用含 TLS 参数的构造函数。</p>
     *
     * @param realGrpcEnabled      {@code true} 启用真实 gRPC 传输；{@code false} 使用内存回退
     * @param deadlineTimeoutMillis gRPC 调用超时（毫秒），仅 realGrpcEnabled=true 时生效
     * @param usePlaintext         是否使用明文传输（开发环境），仅 realGrpcEnabled=true 时生效
     */
    public GrpcMpcTransportStub(boolean realGrpcEnabled,
                                long deadlineTimeoutMillis,
                                boolean usePlaintext) {
        this(realGrpcEnabled, deadlineTimeoutMillis, usePlaintext, null, null, null);
    }

    /**
     * 构造 gRPC 传输 stub（使用默认超时与明文）。
     *
     * @param realGrpcEnabled {@code true} 启用真实 gRPC 传输；{@code false} 使用内存回退
     */
    public GrpcMpcTransportStub(boolean realGrpcEnabled) {
        this(realGrpcEnabled, DEFAULT_DEADLINE_TIMEOUT_MILLIS, true);
    }

    /** 默认构造：使用内存回退（开发模式）。 */
    public GrpcMpcTransportStub() {
        this(false);
    }

    @Override
    public void connect(List<MpcParticipant> participants) {
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no participants to connect");
        }

        // 记录所有参与者（用于广播）
        connectedParticipants.clear();
        for (MpcParticipant p : participants) {
            connectedParticipants.put(p.getParticipantId(), p);
        }

        if (realGrpcEnabled) {
            // 真实 gRPC 模式：为每个 participant 创建 ManagedChannel
            // MPC-P0-02: usePlaintext=false 时尝试配置 mTLS
            SslContext sslContext = null;
            if (!usePlaintext) {
                if (GrpcTlsContextFactory.isTlsConfigComplete(
                        tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath)) {
                    sslContext = GrpcTlsContextFactory.buildClientSslContext(
                            tlsTrustCertPath, tlsClientCertPath, tlsClientKeyPath);
                } else {
                    // MPC-P0 修复：TLS 配置不完整时 fail-closed（拒绝启动），不再回退明文。
                    // 原 fail-open 行为允许攻击者通过配置缺失降级为明文连接，绕过 mTLS。
                    // 保留 use-plaintext=true 作为显式开发模式开关；默认必须 fail-closed。
                    throw new IllegalStateException("MPC transport TLS config incomplete. "
                            + "Set mpc.transport.use-plaintext=true explicitly for development only. "
                            + "Production deployment requires complete TLS configuration "
                            + "(mpc.transport.tls.trust-cert-path / client-cert-path / client-key-path). "
                            + "(MPC-P0 fail-closed)");
                }
            }

            for (MpcParticipant p : participants) {
                String endpoint = p.getEndpoint();
                String[] hostPort = parseEndpoint(endpoint);
                String host = hostPort[0];
                int port = Integer.parseInt(hostPort[1]);

                NettyChannelBuilder builder = NettyChannelBuilder
                        .forAddress(host, port)
                        .enableRetry()
                        .maxRetryAttempts(3);
                if (usePlaintext || sslContext == null) {
                    builder.usePlaintext();
                    if (usePlaintext) {
                        log.warn("GrpcMpcTransportStub: PLAINTEXT to {} (dev mode, MPC-P0-02)",
                                endpoint);
                    }
                } else {
                    builder.sslContext(sslContext);
                }
                ManagedChannel channel = builder.build();
                channels.put(p.getParticipantId(), channel);
                channelDescriptors.put(p.getParticipantId(), "grpc://" + endpoint);
            }
            connected = true;
            log.info("GrpcMpcTransportStub connected: {} participants (real gRPC, plaintext={}, tls={})",
                    participants.size(), usePlaintext, sslContext != null);
        } else {
            // 内存回退模式
            fallback.connect(participants);
            // 同步 channel 描述用于诊断
            for (MpcParticipant p : participants) {
                channelDescriptors.put(p.getParticipantId(), "grpc://" + p.getEndpoint());
            }
            connected = true;
            log.info("GrpcMpcTransportStub connected: {} participants (fallback=in-memory)",
                    participants.size());
        }
    }

    @Override
    public void send(MpcMessage message) {
        ensureConnected();
        Objects.requireNonNull(message, "message");

        if (!realGrpcEnabled) {
            fallback.send(message);
            return;
        }

        if (message.isBroadcast()) {
            // 广播：发送给所有其他参与者
            int sent = 0;
            for (String toId : connectedParticipants.keySet()) {
                if (!toId.equals(message.getFromParticipantId())) {
                    sendToPoint(message, toId);
                    sent++;
                }
            }
            log.debug("Broadcast message {} to {} recipients via gRPC",
                    message.getMessageId(), sent);
        } else {
            // 点对点
            String toId = message.getToParticipantId();
            if (!connectedParticipants.containsKey(toId)) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                        "unknown participant: " + toId);
            }
            sendToPoint(message, toId);
            log.debug("Sent message {} to {} via gRPC", message.getMessageId(), toId);
        }
    }

    /**
     * 通过 gRPC stub 发送一条点对点消息到指定接收者。
     *
     * <p>MPC-P1-03 修复：若 {@link #authToken} 非空，在 gRPC metadata 中添加
     * {@code Authorization: Bearer <token>} 头，供对端
     * {@link MpcTransportGrpcServer} 校验。</p>
     *
     * @param message 待发送消息
     * @param toId    接收者参与者 ID
     */
    private void sendToPoint(MpcMessage message, String toId) {
        ManagedChannel channel = channels.get(toId);
        if (channel == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no gRPC channel to participant: " + toId);
        }

        MpcTransportServiceGrpc.MpcTransportServiceBlockingStub stub =
                MpcTransportServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(deadlineTimeoutMillis, TimeUnit.MILLISECONDS);

        // MPC-P1-03: 若 authToken 非空，附加 Authorization: Bearer <token> metadata
        if (authToken != null && !authToken.isEmpty()) {
            Metadata metadata = new Metadata();
            metadata.put(AUTHORIZATION_METADATA_KEY, BEARER_PREFIX + authToken);
            ClientInterceptor authInterceptor = MetadataUtils.newAttachHeadersInterceptor(metadata);
            stub = stub.withInterceptors(authInterceptor);
        }

        MpcTransportProto.MpcMessageProto proto = toProto(message);
        try {
            MpcTransportProto.Ack ack = stub.sendMpcMessage(proto);
            if (!ack.getSuccess()) {
                log.warn("gRPC SendMpcMessage to {} returned failure: {}",
                        toId, ack.getError());
            }
        } catch (StatusRuntimeException e) {
            Status status = e.getStatus();
            log.error("gRPC send to {} failed: status={}, message={}",
                    toId, status, e.getMessage(), e);
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "gRPC send to " + toId + " failed: " + status, e);
        }
    }

    @Override
    public MpcMessage receive(String sessionId, int round, String fromParticipantId, long timeoutMillis) {
        ensureConnected();
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(fromParticipantId, "fromParticipantId");

        if (!realGrpcEnabled) {
            return fallback.receive(sessionId, round, fromParticipantId, timeoutMillis);
        }

        // 真实 gRPC 模式：从本地邮箱取消息（由 MpcTransportGrpcServer 填充）
        String key = mailboxKey(sessionId, round, fromParticipantId);
        Mailbox mb = localMailbox.computeIfAbsent(key, k -> new Mailbox());

        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            MpcMessage msg = mb.poll();
            if (msg != null) {
                return msg;
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.TIMEOUT,
                        "receive timeout: session=" + sessionId + ", round=" + round
                                + ", from=" + fromParticipantId);
            }
            try {
                mb.awaitMessage(Math.min(50, remaining));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.TIMEOUT,
                        "receive interrupted", e);
            }
        }
    }

    @Override
    public void close() {
        if (realGrpcEnabled) {
            // 关闭所有 ManagedChannel
            for (Map.Entry<String, ManagedChannel> entry : channels.entrySet()) {
                String id = entry.getKey();
                ManagedChannel ch = entry.getValue();
                try {
                    ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Channel shutdown interrupted for participant {}", id);
                }
            }
            channels.clear();
            channelDescriptors.clear();
            localMailbox.clear();
            connectedParticipants.clear();
            connected = false;
            log.info("GrpcMpcTransportStub closed (real gRPC mode)");
        } else {
            channelDescriptors.clear();
            connectedParticipants.clear();
            connected = false;
            fallback.close();
        }
    }

    @Override
    public boolean isConnected() {
        if (realGrpcEnabled) {
            return connected && !channels.isEmpty();
        }
        return fallback.isConnected();
    }

    // =========================================================================
    // 本地邮箱投递（供 MpcTransportGrpcServer 调用）
    // =========================================================================

    /**
     * 将一条消息投递到本地邮箱，供 {@link #receive} 取出。
     *
     * <p>该方法由本节点的 {@code MpcTransportGrpcServer} 在收到 gRPC 消息时调用。
     * 消息按 (sessionId, round, fromParticipantId) 索引到对应邮箱。
     * 重复消息（相同 messageId）会被去重。</p>
     *
     * @param message 待投递的消息
     * @return {@code true} 若消息被成功投递（非重复）；{@code false} 若消息为重复
     */
    public boolean deliverLocal(MpcMessage message) {
        Objects.requireNonNull(message, "message");
        String key = mailboxKey(message.getSessionId(), message.getRound(),
                message.getFromParticipantId());
        Mailbox mb = localMailbox.computeIfAbsent(key, k -> new Mailbox());
        boolean offered = mb.offerIfAbsent(message);
        if (offered) {
            log.debug("Delivered local message {} to mailbox ({},{},{})",
                    message.getMessageId(), message.getSessionId(),
                    message.getRound(), message.getFromParticipantId());
        }
        return offered;
    }

    // =========================================================================
    // 诊断方法
    // =========================================================================

    /**
     * @return 当前已建立的 channel 描述（participantId -> "grpc://endpoint"）
     */
    public Map<String, String> getChannels() {
        return new HashMap<>(channelDescriptors);
    }

    /**
     * @return 是否启用真实 gRPC 模式
     */
    public boolean isRealGrpcEnabled() {
        return realGrpcEnabled;
    }

    // =========================================================================
    // 内部工具方法
    // =========================================================================

    /**
     * 确保 transport 已连接。
     *
     * @throws MpcProtocolException 若未连接
     */
    private void ensureConnected() {
        if (!connected) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_STATE,
                    "transport not connected");
        }
    }

    /**
     * 解析 endpoint（host:port）为 [host, port]。
     *
     * @param endpoint host:port 格式的端点
     * @return 长度 2 的数组：[0]=host, [1]=port
     */
    private static String[] parseEndpoint(String endpoint) {
        int idx = endpoint.lastIndexOf(':');
        if (idx <= 0 || idx == endpoint.length() - 1) {
            throw new IllegalArgumentException(
                    "invalid endpoint format, expected host:port, got: " + endpoint);
        }
        return new String[]{endpoint.substring(0, idx), endpoint.substring(idx + 1)};
    }

    /**
     * 构造邮箱键。
     *
     * @param sessionId         会话 ID
     * @param round             轮次
     * @param fromParticipantId 发送者 ID
     * @return 邮箱键
     */
    private static String mailboxKey(String sessionId, int round, String fromParticipantId) {
        return sessionId + "|" + round + "|" + fromParticipantId;
    }

    /**
     * 将 {@link MpcMessage} 转换为 protobuf {@link MpcTransportProto.MpcMessageProto}。
     *
     * @param msg Java 消息
     * @return protobuf 消息
     */
    static MpcTransportProto.MpcMessageProto toProto(MpcMessage msg) {
        MpcTransportProto.MpcMessageProto.Builder b = MpcTransportProto.MpcMessageProto.newBuilder()
                .setMessageId(msg.getMessageId())
                .setSessionId(msg.getSessionId())
                .setRound(msg.getRound())
                .setType(msg.getType().name())
                .setFromParticipantId(msg.getFromParticipantId())
                .setToParticipantId(msg.getToParticipantId() == null ? "" : msg.getToParticipantId())
                .setPayloadHex(msg.getPayloadHex() == null ? "" : msg.getPayloadHex())
                .setTimestamp(msg.getTimestamp())
                .setNonce(msg.getNonce() == null ? "" : msg.getNonce())
                .setHmacHex(msg.getHmacHex() == null ? "" : msg.getHmacHex());
        return b.build();
    }

    /**
     * 将 protobuf {@link MpcTransportProto.MpcMessageProto} 转换为 {@link MpcMessage}。
     *
     * @param proto protobuf 消息
     * @return Java 消息
     */
    static MpcMessage fromProto(MpcTransportProto.MpcMessageProto proto) {
        String toId = proto.getToParticipantId();
        String payload = proto.getPayloadHex();
        String nonce = proto.getNonce();
        String hmac = proto.getHmacHex();

        MpcMessage msg = MpcMessage.create(
                proto.getSessionId(),
                proto.getRound(),
                MpcMessage.Type.valueOf(proto.getType()),
                proto.getFromParticipantId(),
                toId.isEmpty() ? null : toId,
                payload.isEmpty() ? null : payload);
        if (hmac != null && !hmac.isEmpty()) {
            msg = msg.withHmac(hmac);
        }
        return msg;
    }

    // =========================================================================
    // 邮箱实现
    // =========================================================================

    /**
     * 单个 (sessionId, round, fromParticipantId) 的邮箱。
     *
     * <p>线程安全：用 {@link ConcurrentLinkedQueue} 存储消息，
     * {@link ReentrantLock} + {@link Condition} 实现阻塞等待。
     * 基于 messageId 去重。</p>
     */
    private static final class Mailbox {
        private final ConcurrentLinkedQueue<MpcMessage> queue = new ConcurrentLinkedQueue<>();
        private final Set<String> seenMessageIds = ConcurrentHashMap.newKeySet();
        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();

        /**
         * 投递消息（去重）。
         *
         * @param m 待投递消息
         * @return {@code true} 若消息非重复被投递；{@code false} 若消息为重复
         */
        boolean offerIfAbsent(MpcMessage m) {
            if (!seenMessageIds.add(m.getMessageId())) {
                return false; // 重复消息
            }
            queue.offer(m);
            lock.lock();
            try {
                condition.signalAll();
            } finally {
                lock.unlock();
            }
            return true;
        }

        /**
         * 非阻塞取消息。
         *
         * @return 队首消息，或 {@code null} 若队列为空
         */
        MpcMessage poll() {
            return queue.poll();
        }

        /**
         * 阻塞等待消息，最多等待 {@code millis} 毫秒。
         *
         * @param millis 最大等待时间
         * @throws InterruptedException 若被中断
         */
        void awaitMessage(long millis) throws InterruptedException {
            lock.lock();
            try {
                if (queue.isEmpty()) {
                    condition.await(millis, TimeUnit.MILLISECONDS);
                }
            } finally {
                lock.unlock();
            }
        }
    }
}
