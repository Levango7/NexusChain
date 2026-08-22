package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

@Component
public class GRPCClient {

    private static final Logger logger = LoggerFactory.getLogger(GRPCClient.class);

    public GRPCClient withExecutor(Executor executor) {
        this.executor = executor;
        return this;
    }

    @Value("${p2p.enable-message-log}")
    private boolean enableMessageLog;

    // === REQ-19 安全加固：P2P gRPC Client TLS 配置 ===
    @Value("${p2p.tls.enabled:${GRPC_TLS_ENABLED:false}}")
    private boolean tlsEnabled;

    @Value("${p2p.tls.trust-store-path:${GRPC_TLS_TRUST_STORE:}}")
    private String tlsTrustStorePath;

    @Value("${p2p.tls.cert-chain-path:${GRPC_TLS_CERT_CHAIN:}}")
    private String tlsCertChainPath;

    @Value("${p2p.tls.private-key-path:${GRPC_TLS_PRIVATE_KEY:}}")
    private String tlsPrivateKeyPath;

    private Executor executor;

    private ConcurrentMap<HostPort, ManagedChannel> channelCache;

    private static final int RPC_TIMEOUT = 3;

    private Peer self;

    public long getNonce() {
        return nonce.incrementAndGet();
    }

    private AtomicLong nonce;

    private int timeout;

    public GRPCClient withTimeout(int timeout) {
        this.timeout = timeout;
        return this;
    }

    private ManagedChannel getChannel(HostPort hostPort){
        ManagedChannel channel = channelCache.get(hostPort);
        if (channel != null && !channel.isShutdown()){
            return channel;
        }
        ManagedChannel ch;
        // === REQ-19 安全加固：P2P gRPC Client 启用 TLS（mTLS 双向认证） ===
        if (tlsEnabled) {
            // 安全加固：tlsEnabled=true 时必须配置信任库，禁止静默降级为 InsecureTrustManagerFactory（MITM 风险）
            if (tlsTrustStorePath == null || tlsTrustStorePath.isEmpty()) {
                throw new IllegalStateException(
                        "P2P TLS enabled (p2p.tls.enabled=true) but trust store path is not configured "
                                + "(p2p.tls.trust-store-path / GRPC_TLS_TRUST_STORE). "
                                + "Refusing to start with insecure trust manager to avoid MITM risk.");
            }
            try {
                SslContextBuilder sslContextBuilder = SslContextBuilder.forClient();
                // 信任库：验证服务端证书
                sslContextBuilder.trustManager(new File(tlsTrustStorePath));
                // 客户端证书（mTLS）
                if (tlsCertChainPath != null && !tlsCertChainPath.isEmpty()
                        && tlsPrivateKeyPath != null && !tlsPrivateKeyPath.isEmpty()) {
                    sslContextBuilder.keyManager(
                            new File(tlsCertChainPath), new File(tlsPrivateKeyPath));
                }
                SslContext sslContext = GrpcSslContexts.configure(sslContextBuilder).build();
                ch = NettyChannelBuilder.forAddress(hostPort.getHost(), hostPort.getPort())
                        .sslContext(sslContext)
                        .negotiationType(NegotiationType.TLS)
                        .build();
            } catch (RuntimeException | java.io.IOException e) {
                throw new RuntimeException("Failed to build gRPC TLS channel: " + e.getMessage(), e);
            }
        } else {
            // 兼容模式：未启用 TLS 时使用明文（仅 dev/test 环境）
            ch = ManagedChannelBuilder.forAddress(hostPort.getHost(), hostPort.getPort())
                    .usePlaintext()
                    .build();
        }
        channelCache.put(hostPort, ch);
        return ch;
    }

    public GRPCClient(){
        this.nonce = new AtomicLong();
        this.timeout = RPC_TIMEOUT;
        this.executor = Executors.newCachedThreadPool();
        this.channelCache = new ConcurrentLinkedHashMap.Builder<HostPort, ManagedChannel>().maximumWeightedCapacity(PeersCache.MAX_PEERS * 2).build();
    }

    public GRPCClient(Peer self){
        this();
        this.self = self;
    }

    public GRPCClient withSelf(Peer self){
        this.self = self;
        return this;
    }

    public NexusChainOuterClass.Message buildMessage(long ttl, AbstractMessage msg){
        return Util.buildMessage(self, nonce.incrementAndGet(), ttl, msg);
    }

    private static class SimpleObserver implements StreamObserver<NexusChainOuterClass.Message> {

        private ManagedChannel channel;

        private BiConsumer<NexusChainOuterClass.Message, Throwable> function;

        private boolean enableExceptionStackTrace;

        public SimpleObserver withExceptionStackTrance(boolean enableExceptionStackTrance) {
            this.enableExceptionStackTrace = enableExceptionStackTrace;
            return this;
        }

        public SimpleObserver(ManagedChannel channel, BiConsumer<NexusChainOuterClass.Message, Throwable> function) {
            this.channel = channel;
            this.function = function;
        }

        @Override
        public void onNext(NexusChainOuterClass.Message value) {
            function.accept(value, null);
        }

        @Override
        public void onError(Throwable t) {
            function.accept(null, t);
            channel.shutdown();
        }

        @Override
        public void onCompleted() { }
    }

    public  CompletableFuture<NexusChainOuterClass.Message> dialWithTTL(String host, int port, long ttl, AbstractMessage msg){
        if(msg instanceof NexusChainOuterClass.Message){
            return dial(host, port, (NexusChainOuterClass.Message) msg);
        }
        return dial(host, port, buildMessage(ttl, msg));
    }

    public void dialAsyncWithTTL(String host, int port, long ttl, AbstractMessage msg, BiConsumer<NexusChainOuterClass.Message, Throwable> function){
        if(msg instanceof NexusChainOuterClass.Message){
            dialAsync(host, port, (NexusChainOuterClass.Message) msg, function);
            return;
        }
        dialAsync(host, port, buildMessage(ttl, msg), function);
    }

    private CompletableFuture<NexusChainOuterClass.Message> dial(String host, int port, NexusChainOuterClass.Message msg) {
        ManagedChannel ch = getChannel(new HostPort(host, port));

        NexusChainGrpc.NexusChainBlockingStub stub = NexusChainGrpc.newBlockingStub(
                ch).withDeadlineAfter(timeout, TimeUnit.SECONDS);
        
        return CompletableFuture
                .supplyAsync(() -> {
                    try{
                        return stub.entry(msg);
                    }catch (RuntimeException e){
                        throw new RuntimeException(e.getMessage());
                    } finally {
                        ch.shutdown();
                    }
                }, executor);
    }

    private void dialAsync(String host, int port, NexusChainOuterClass.Message msg, BiConsumer<NexusChainOuterClass.Message, Throwable> function) {
        ManagedChannel ch = getChannel(new HostPort(host, port));
        NexusChainGrpc.NexusChainStub stub = NexusChainGrpc.newStub(
                ch).withDeadlineAfter(timeout, TimeUnit.SECONDS);

        stub.entry(msg, new SimpleObserver(ch, function).withExceptionStackTrance(enableMessageLog));
    }
}
