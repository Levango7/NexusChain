package org.nexus.signing.mpc.transport;

import org.nexus.signing.mpc.MpcParticipant;
import org.nexus.signing.mpc.MpcProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * gRPC over HTTP/2 传输层占位实现。
 *
 * <p>该类定义了未来切换到真实 gRPC（io.grpc:grpc-stub + protobuf-generated
 * stub）所需的全部方法签名与生命周期。当前实现以 <b>HTTP/内存回退</b> 方式
 * 工作，避免在 composite build 阶段引入 gRPC 编译插件依赖。</p>
 *
 * <p><b>切换到真实 gRPC 的步骤</b>：</p>
 * <ol>
 *   <li>在 {@code build.gradle} 中添加：
 *       <pre>
 *       implementation "io.grpc:grpc-netty-shaded:${grpcVersion}"
 *       implementation "io.grpc:grpc-protobuf:${grpcVersion}"
 *       implementation "io.grpc:grpc-stub:${grpcVersion}"
 *       implementation "com.google.protobuf:protobuf-java:${protobufVersion}"
 *       </pre>
 *   </li>
 *   <li>添加 protobuf / grpc Gradle 插件，生成 stub 类。</li>
 *   <li>将 {@link #send(MpcMessage)} 内部实现替换为
 *       {@code stub.withDeadlineAfter(...).send(message.toByteArray())}。</li>
 *   <li>将 {@link #receive(String, int, String, long)} 替换为
 *       StreamObserver 回调写入邮箱。</li>
 *   <li>{@link MpcMessage} 的 {@code toByteArray/fromByteArray} 可直接替换为
 *       protobuf-generated 的 {@code toByteArray/parseFrom}。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：与 {@link InMemoryMpcTransport} 一致。</p>
 */
public class GrpcMpcTransportStub implements MpcTransport {

    private static final Logger log = LoggerFactory.getLogger(GrpcMpcTransportStub.class);

    /** 当前回退使用的内存传输。 */
    private final InMemoryMpcTransport fallback = new InMemoryMpcTransport();

    /** 已建立的 gRPC channel（participantId -> channel 描述），占位用字符串。 */
    private final Map<String, String> channels = new ConcurrentHashMap<>();

    /** 是否启用真实 gRPC（默认 false，使用 HTTP/内存回退）。 */
    private final boolean realGrpcEnabled;

    /**
     * 构造 gRPC 传输占位。
     *
     * @param realGrpcEnabled {@code true} 时尝试启用真实 gRPC（当前版本会回退并告警）；
     *                        {@code false} 显式使用内存回退
     */
    public GrpcMpcTransportStub(boolean realGrpcEnabled) {
        this.realGrpcEnabled = realGrpcEnabled;
        if (realGrpcEnabled) {
            log.warn("GrpcMpcTransportStub: real gRPC not wired yet, falling back to in-memory");
        }
    }

    /** 默认构造：使用内存回退。 */
    public GrpcMpcTransportStub() {
        this(false);
    }

    @Override
    public void connect(List<MpcParticipant> participants) {
        // 占位：建立 channel 描述
        for (MpcParticipant p : participants) {
            channels.put(p.getParticipantId(), "grpc://" + p.getEndpoint());
        }
        // 回退到内存实现
        fallback.connect(participants);
        log.info("GrpcMpcTransportStub connected: {} participants (fallback=in-memory)",
                participants.size());
    }

    @Override
    public void send(MpcMessage message) {
        if (realGrpcEnabled) {
            // TODO: 替换为 grpc stub 调用：
            //   ManagedChannel channel = channels.get(message.getToParticipantId());
            //   MpcSignerGrpc.MpcSignerFutureStub stub =
            //       MpcSignerGrpc.newFutureStub(channel)
            //           .withDeadlineAfter(timeoutMillis, TimeUnit.MILLISECONDS);
            //   stub.sendRound(MpcMessageProto.MpcMessage.parseFrom(message.toByteArray()));
            log.debug("gRPC send would route to channel: {}", channels.get(message.getToParticipantId()));
        }
        // 当前回退
        fallback.send(message);
    }

    @Override
    public MpcMessage receive(String sessionId, int round, String fromParticipantId, long timeoutMillis) {
        // 当前回退
        return fallback.receive(sessionId, round, fromParticipantId, timeoutMillis);
    }

    @Override
    public void close() {
        // TODO: 关闭所有 ManagedChannel
        channels.clear();
        fallback.close();
    }

    @Override
    public boolean isConnected() {
        return fallback.isConnected();
    }

    /**
     * @return 当前已建立的 channel 描述（participantId -> "grpc://endpoint"）
     */
    public Map<String, String> getChannels() {
        return channels;
    }
}