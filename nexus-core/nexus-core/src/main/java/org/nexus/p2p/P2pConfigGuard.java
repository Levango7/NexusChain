package org.nexus.p2p;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * P2P 配置启动校验。
 *
 * <p>2026-10-27 配置覆盖审查发现两类静默失效：</p>
 * <ul>
 *   <li>{@code p2p.mode} 只认 {@code grpc} / {@code rest}。写错（例如 {@code http}、
 *       空串）时 {@link PeerServer}、{@code SyncClient}、{@code ProposalHandler}
 *       的 {@code @ConditionalOnProperty} 全部不成立，进程照常启动但 P2P 完全不通，
 *       只会在运行期表现为"节点不同步"。</li>
 *   <li>{@code p2p.tls.*} 只作用于 gRPC 形态（{@code GRPCClient} / {@link PeerServer}）。
 *       rest 形态的 {@code RPCClient} 走硬编码的 {@code http://}，没有 TLS 通道。
 *       此时开启 TLS 会让运维误以为链路已加密，实际是明文传输。</li>
 * </ul>
 * <p>两类都是"配置写了但行为不符"，因此在启动期直接失败，不做静默降级。</p>
 */
@Component
public class P2pConfigGuard {

    static final Set<String> SUPPORTED_MODES = Set.of("grpc", "rest");

    private static final String MODE_REST = "rest";

    @Value("${p2p.mode}")
    private String mode;

    @Value("${p2p.tls.enabled:false}")
    private boolean tlsEnabled;

    @PostConstruct
    public void validate() {
        String normalized = mode == null ? "" : mode.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_MODES.contains(normalized)) {
            throw new IllegalStateException("p2p.mode=" + mode + " 非法，仅支持 grpc / rest；"
                    + "当前取值不会装配任何 P2P 组件，节点将无法同步");
        }
        if (MODE_REST.equals(normalized) && tlsEnabled) {
            throw new IllegalStateException("p2p.mode=rest 不支持 TLS（RPCClient 使用明文 HTTP），"
                    + "但 p2p.tls.enabled=true；请改用 p2p.mode=grpc，或显式关闭 p2p.tls.enabled");
        }
    }
}

