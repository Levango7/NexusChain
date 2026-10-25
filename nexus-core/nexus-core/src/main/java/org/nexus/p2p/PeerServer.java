package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import io.grpc.*;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.nexus.sync.SyncManager;
import org.nexus.sync.TransactionHandler;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * @author sal 1564319846@qq.com
 * nexus protocol implementation
 */
@Component
@ConditionalOnProperty(name = "p2p.mode", havingValue = "grpc")
public class PeerServer extends NexusChainGrpc.NexusChainImplBase {

    private static final int HALF_RATE = 60;

    private static final int MAX_PEERS_PER_PING = 6;


    private static final NexusChainOuterClass.Ping PING = NexusChainOuterClass.Ping.newBuilder().build();
    private static final NexusChainOuterClass.Lookup LOOKUP = NexusChainOuterClass.Lookup.newBuilder().build();
    private static final NexusChainOuterClass.Nothing NOTHING = NexusChainOuterClass.Nothing.newBuilder().build();

    private Server server;
    private static final Logger logger = LoggerFactory.getLogger(PeerServer.class);
    private static final int MAX_TTL = 8;
    private List<Plugin> pluginList;

    @Autowired
    private PeersStorage peersCache;

    @Autowired
    private MessageFilter filter;

    @Autowired
    private PeersManager pmgr;

    @Autowired
    private MessageLogger messageLogger;

    @Autowired
    private SyncManager syncManager;

    @Autowired
    private TransactionHandler transactionHandler;

    @Autowired
    private MerkleHandler merkleHandler;

    @Autowired
    private GRPCClient gRPCClient;

    @Value("${p2p.enable-message-log}")
    private boolean enableMessageLog;

    @Value("${p2p.enable-discovery}")
    private boolean enableDiscovery;

    // === REQ-19 安全加固：P2P gRPC TLS 配置 ===
    // 证书通过环境变量注入（节点本地证书+定期轮换脚本，非 Nacos 管理）
    @Value("${p2p.tls.enabled:${GRPC_TLS_ENABLED:false}}")
    private boolean tlsEnabled;

    @Value("${p2p.tls.cert-chain-path:${GRPC_TLS_CERT_CHAIN:}}")
    private String tlsCertChainPath;

    @Value("${p2p.tls.private-key-path:${GRPC_TLS_PRIVATE_KEY:}}")
    private String tlsPrivateKeyPath;

    @Value("${p2p.tls.trust-store-path:${GRPC_TLS_TRUST_STORE:}}")
    private String tlsTrustStorePath;

    public PeerServer(
    ) throws Exception {
        pluginList = new ArrayList<>();
    }

    public PeerServer use(Plugin plugin) {
        pluginList.add(plugin);
        return this;
    }

    /**
     * 加载插件，启动服务
     */
    @PostConstruct
    public void init() throws Exception {
        this.use(messageLogger)
                .use(filter)
                .use(syncManager)
                .use(transactionHandler)
                .use(pmgr)
                .use(merkleHandler);
        gRPCClient.withSelf(peersCache.getSelf());
        startListening();
    }

    public void startListening() throws Exception {
        logger.info("peer server is listening on " +
                Peer.PROTOCOL_NAME + "://" +
                Hex.encodeHexString(peersCache.getSelf().privateKey.getEncoded()) +
                Hex.encodeHexString(peersCache.getSelf().peerID) + "@" + peersCache.getSelf().hostPort());
        logger.info("provide address to your peers to connect " +
                Peer.PROTOCOL_NAME + "://" +
                Hex.encodeHexString(peersCache.getSelf().peerID) +
                "@" + peersCache.getSelf().hostPort());
        for (Plugin p : pluginList) {
            p.onStart(this);
        }
        if(!enableMessageLog){
            java.util.logging.Logger.getLogger("io.grpc").setLevel(Level.OFF);
        }
        // === REQ-19 安全加固：P2P gRPC Server 启用 TLS（mTLS 双向认证） ===
        int port = peersCache.getSelf().port;
        if (tlsEnabled) {
            // 启用 TLS：加载证书链与私钥，要求客户端证书（mTLS）
            if (tlsCertChainPath == null || tlsCertChainPath.isEmpty()
                    || tlsPrivateKeyPath == null || tlsPrivateKeyPath.isEmpty()) {
                throw new IllegalStateException(
                    "P2P TLS enabled but cert-chain-path or private-key-path is empty. "
                    + "Set GRPC_TLS_CERT_CHAIN and GRPC_TLS_PRIVATE_KEY environment variables.");
            }
            SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(
                    new File(tlsCertChainPath), new File(tlsPrivateKeyPath));
            // 信任库配置（mTLS：验证客户端证书）
            if (tlsTrustStorePath != null && !tlsTrustStorePath.isEmpty()) {
                sslContextBuilder.trustManager(new File(tlsTrustStorePath));
                sslContextBuilder.clientAuth(ClientAuth.REQUIRE);
            }
            SslContext sslContext = GrpcSslContexts.configure(sslContextBuilder).build();
            this.server = NettyServerBuilder.forPort(port)
                    .sslContext(sslContext)
                    .addService(this)
                    .build()
                    .start();
            logger.info("P2P gRPC server started with TLS (mTLS) on port {}", port);
        } else {
            // 兼容模式：未启用 TLS 时使用明文（仅 dev/test 环境）
            this.server = ServerBuilder.forPort(port).addService(this).build().start();
            logger.warn("P2P gRPC server started WITHOUT TLS on port {} — "
                    + "this is insecure; enable p2p.tls.enabled in production", port);
        }
    }

    @Scheduled(fixedRate = HALF_RATE * 1000)
    public void resolve() {
        peersCache.getUnresolved().forEach(h -> {
            dialWithTTL(h.getHost(), h.getPort(), 1, PING);
        });
    }

    @Scheduled(fixedRate = HALF_RATE * 1000)
    public void startHalf() {
        if (!enableDiscovery) {
            return;
        }

        peersCache.half();

        for (Peer p : peersCache.getPeers(MAX_PEERS_PER_PING)) {
            dial(p, PING); // keep alive
        }

        if (peersCache.isFull() || !enableDiscovery) {
            return;
        }

        // discover peers when bucket is not full
        for (Peer p : peersCache.getPeers(MAX_PEERS_PER_PING)) {
            dial(p, LOOKUP);
        }

        for (Peer p : peersCache.popPended()) {
            dial(p, NexusChainOuterClass.Ping.newBuilder().build());
        }
    }

    public List<Peer> getBootstraps() {
        return peersCache.getBootstraps();
    }

    public PeersCache getPeersCache() {
        return peersCache;
    }

    public Peer getSelf() {
        return peersCache.getSelf();
    }

    private NexusChainOuterClass.Message onMessage(NexusChainOuterClass.Message message) {
        try {
            Payload payload = new Payload(message);
            if (peersCache.getBlocked().contains(payload.getRemote())) {
                logger.error("the remote had been blocked");
                return gRPCClient.buildMessage(1, NOTHING);
            }
            Context ctx = new Context();
            ctx.payload = payload;
            // P2P 修复（ADR-031 决策 8 附录）：入站合法消息默认登记对端，
            // 否则单向连接（B bootstrap→A）中 A 不记住 B，broadcast 无对端可发，
            // 跨节点投票/区块广播无法传播。PING/PONG 等 keep 语义保持不变。
            Peer inbound = payload.getRemote();
            if (inbound != null && !peersCache.getBlocked().contains(inbound)) {
                peersCache.keepPeer(inbound);
            }
            for (Plugin p : pluginList) {
                p.onMessage(ctx, this);
                if (ctx.broken) {
                    break;
                }
            }
            if (ctx.remove) {
                peersCache.removePeer(payload.getRemote());
            }
            if (ctx.pending) {
                peersCache.pend(payload.getRemote());
            }
            if (ctx.keep) {
                peersCache.keepPeer(payload.getRemote());
            }
            if (ctx.block) {
                peersCache.blockPeer(payload.getRemote());
            }
            if (ctx.relay) {
                relay(payload);
            }
            if (ctx.response != null) {
                return gRPCClient.buildMessage(1, ctx.response);
            }
        } catch (Exception e) {
            logger.error("fail to parse message", e);
        }
        return gRPCClient.buildMessage(1, NOTHING);
    }

    @Override
    public void entry(NexusChainOuterClass.Message request, StreamObserver<NexusChainOuterClass.Message> responseObserver) {
        // 入站连接登记（广播互达最后一环修复）：收到对端消息时把发送者登记为已知 peer，
        // 否则本节点 peers 为空 → 广播无人接收（真机实证：A 收 B 广播但 A 的 peers
        // 空、不转发，B/C 收 0）。
        try {
            String remotePeer = request.getRemotePeer();
            if (remotePeer != null && !remotePeer.isEmpty()) {
                Peer remote = Peer.parse(remotePeer);
                if (!remote.equals(peersCache.getSelf())) {
                    peersCache.keepPeer(remote);
                    logger.debug("P2P inbound peer registered: {}:{}", remote.host, remote.port);
                }
            }
        } catch (Exception e) {
            logger.debug("P2P inbound peer register skipped: {}", e.getMessage());
        }
        NexusChainOuterClass.Message resp = onMessage(request);
        responseObserver.onNext(resp);
        responseObserver.onCompleted();
    }

    private void dialWithTTL(Peer peer, long ttl, AbstractMessage msg) {
        gRPCClient.dialAsyncWithTTL(peer.host, peer.port, ttl, msg, (m, e) -> {
            if (m != null) {
                onMessage(m);
//                return;
            }
            if (e != null) {
                logger.warn("P2P dial failed to {}:{} error={}", peer.host, peer.port, e.getMessage());
                // PLAN-034：对端广告地址在异构网络不可达时（如 WSL↔容器群的
                // host.docker.internal 广告地址），按同一 peerID 的可达备选地址
                // （bootstrap 配置地址）重试一次——上述场景实测导致追块窗口请求
                // 全部打不到对端。重试不递归，避免拨号风暴。
                redialOnAlternateAddress(peer, ttl, msg);
            }
//            e.printStackTrace();
//            logger.error("cannot connect to " + peer.toString());
//            if (!enableDiscovery) {
//                return;
//            }
//            peersCache.half(peer);
        });
    }

    /** 同 peerID 备选地址轮换游标（peerID hex → index）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>
            dialFallbackCursor = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 拨号兜底（PLAN-034）：用同一 peerID 的备选地址重试一次。
     *
     * <p>问题：多机/NAT 拓扑下对端广告的地址（{@code p2p.address}）对本节点所在
     * 网络不一定可达（实测：A 广告 {@code host.docker.internal:9235}，WSL 内解析到
     * 防火墙挡掉的 LAN IP；B 对 A 的 GetStatus/GetBlocks 外拨全部失败，仅 inbound
     * 推送可用 ⇒ 追块窗口请求打不到对端）。兜底源取 {@link #getBootstraps()} 中
     * 同 peerID 的地址（启动配置里"已知可达"的一组）。</p>
     *
     * <p>约束：只重试一次（失败回调里不再递归）；无同 peerID 备选 / 备选与当前
     * 地址相同 ⇒ 不动作。</p>
     */
    private void redialOnAlternateAddress(Peer peer, long ttl, AbstractMessage msg) {
        if (peer == null) {
            return;
        }
        List<Peer> alternates;
        try {
            alternates = getBootstraps();
        } catch (RuntimeException ex) {
            return; // 未启动/无 bootstraps：静默跳过（不放大故障）
        }
        if (alternates == null || alternates.isEmpty()) {
            return;
        }
        // 首选：同 peerID 的备选（精确身份匹配）
        List<Peer> sameId = new ArrayList<>();
        for (Peer b : alternates) {
            if (b != null && peer.peerID != null && b.peerID != null
                    && Arrays.equals(peer.peerID, b.peerID)) {
                sameId.add(b);
            }
        }
        // 兜底降级（2026-10-25 验收补充）：学习到的"广告地址 Peer"常不带 peerID
        // （多机实测：B 学到的 A@host.docker.internal peerID=null）——此时若无同 ID
        // 匹配，退化为"任意地址不同的 bootstrap"（协议消息对等端可达即可，仍只重试一次）。
        List<Peer> candidates = sameId;
        if (candidates.isEmpty()) {
            candidates = new ArrayList<>();
            for (Peer b : alternates) {
                if (b != null && !(b.host.equals(peer.host) && b.port == peer.port)) {
                    candidates.add(b);
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        String key = peer.peerID != null ? Hex.encodeHexString(peer.peerID)
                : (peer.host + ":" + peer.port);
        java.util.concurrent.atomic.AtomicInteger cursor =
                dialFallbackCursor.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger());
        Peer alt = candidates.get(Math.floorMod(cursor.getAndIncrement(), candidates.size()));
        if (alt.host.equals(peer.host) && alt.port == peer.port) {
            return; // 同一地址，重试无意义
        }
        logger.info("P2P dial fallback: {}:{} unreachable, retrying same peer at {}:{}",
                peer.host, peer.port, alt.host, alt.port);
        // PLAN-035：兜底拨号必须在**脱离取消继承**的 Context 里发起——本方法运行在
        // 第一次拨号的 observer 回调里，gRPC Java 在该回调链返回时会取消该 call 的
        // Context；就地发起的新 RPC 继承这个已取消的 Context ⇒ 毫秒级
        // `CANCELLED: io.grpc.Context was cancelled without error`（实测：v2 兜底
        // 触发 ×50、目标却全部 CANCELLED）。`Context.current().fork()` 生子独立
        // Context，父取消不会传导——新 RPC 因此可正常完成。
        io.grpc.Context forked = io.grpc.Context.current().fork();
        io.grpc.Context previous = forked.attach();
        try {
            gRPCClient.dialAsyncWithTTL(alt.host, alt.port, ttl, msg, (m, e) -> {
                // 与正常拨路语义一致：响应消息必须回灌 onMessage——GetStatus 的
                // STATUS 响应正是靠它驱动「对端更高 ⇒ 请求下一窗口」（v3 修复：
                // v2 只处理 error、把 m 丢掉 ⇒ 兜底"成功"却无追块）。
                if (m != null) {
                    onMessage(m);
                }
                if (e != null) {
                    logger.warn("P2P dial fallback also failed: {}:{} error={}",
                            alt.host, alt.port, e.getMessage());
                }
            });
        } finally {
            io.grpc.Context.current().detach(previous);
        }
    }

    private void dialWithTTL(String host, int port, long ttl, AbstractMessage msg) {
        gRPCClient.dialAsyncWithTTL(host, port, ttl, msg, (m, e) -> {
            if (m != null) {
                onMessage(m);
//                e.printStackTrace();
//                logger.error("cannot connect to " + host + ":" + port);
            }
        });
    }

    public void dial(Peer p, AbstractMessage msg) {
        dialWithTTL(p, 1, msg);
    }

    public void broadcast(AbstractMessage msg) {
        for (Peer p : getPeers()) {
            dialWithTTL(p, MAX_TTL, msg);
        }
    }

    public void relay(Payload payload) {
        if (payload.getTtl() <= 0) {
            return;
        }
        for (Peer p : getPeers()) {
            if (p.equals(payload.getRemote())) {
                continue;
            }
            try {
                dialWithTTL(p, payload.getTtl() - 1, payload.getBody());
            } catch (RuntimeException e) {
                logger.error("parse body fail");
            }
        }
    }

    public List<Peer> getPeers() {
        return peersCache.getPeers();
    }

    public boolean hasPeer(Peer peer) {
        return peersCache.hasPeer(peer);
    }


    public String getNodePubKey() {
        return Peer.PROTOCOL_NAME + "://" +
                Hex.encodeHexString(getSelf().peerID) +
                "@" + getSelf().hostPort();
    }

    public String getIP() {
        InetAddress address = null;
        try {
            address = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            logger.warn("Failed to resolve local host address", e);
        }
        return Objects.requireNonNull(address).getHostAddress();
    }

    public int getPort() {
        return getSelf().port;
    }

    void pend(Peer peer) {
        this.peersCache.pend(peer);
    }
}
