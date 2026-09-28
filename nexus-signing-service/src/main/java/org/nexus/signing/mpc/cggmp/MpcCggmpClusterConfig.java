package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CGGMP21 集群路径装配（P0-1：三进程分布式生产化）。
 *
 * <p>替代 H 批的 {@code MpcCggmpOrchestratorConfig}（单方 orchestrator 视角）。
 * 按 Model A 拓扑装配：**单个 signing-service 进程驱动全部 n 个 mpc-engine
 * 节点**，每方一个 {@link MpcCggmpClient}（绑 router 的对应 endpoint channel
 * + Bearer auth），协调器（relay 池）= endpoint 0；聚合为
 * {@link CggmpClusterSessionDriver}。私钥份额永驻各引擎进程。</p>
 *
 * <h2>装配规则（fail-closed）</h2>
 * <ul>
 *   <li>端点数 &lt; 2：{@code cggmp-enabled=true} 时启动失败（分布式门限签名
 *       至少 2 方；生产 3 方）；{@code false} 时不建驱动（CGGMP 路径空闲，
 *       走 GG20 默认路径）</li>
 *   <li>任一 endpoint channel 缺失/已关闭：同上（不静默降级到子集——
 *       参与方索引错位会路由到错误的方）</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <pre>
 * mpc:
 *   engine:
 *     cggmp-enabled: true
 *     cggmp:
 *       deadline-ms: 120000      # aux 安全素数生成可达 ~80s
 * </pre>
 *
 * @see CggmpClusterSessionDriver
 * @since 2.52.0
 */
@Configuration
public class MpcCggmpClusterConfig {

    private static final Logger log = LoggerFactory.getLogger(MpcCggmpClusterConfig.class);

    /**
     * CGGMP21 单 RPC deadline（毫秒）。
     *
     * <p>与集群 E2E 测试同值（120s）：aux 首轮 Paillier 安全素数生成在慢机
     * 上每方 ~80s，默认 30s 会超时。</p>
     */
    private static final long DEFAULT_CGGMP_DEADLINE_MS = 120_000L;

    private final MpcEngineRouter mpcEngineRouter;

    @Value("${mpc.engine.cggmp.deadline-ms:120000}")
    private long cggmpDeadlineMs;

    /** 与 {@link CggmpMpcCryptoEngine} 同键；此处用于装配期 fail-closed 判定。 */
    @Value("${mpc.engine.cggmp-enabled:false}")
    private boolean cggmpEnabled;

    @Autowired
    public MpcCggmpClusterConfig(MpcEngineRouter mpcEngineRouter) {
        this.mpcEngineRouter = Objects.requireNonNull(mpcEngineRouter, "mpcEngineRouter");
    }

    /**
     * CGGMP21 集群会话驱动（每方一个 client + 协调器 = endpoint 0）。
     *
     * @return 驱动实例；端点数不足或通道不可用时返回 {@code null}
     *         （{@link CggmpMpcCryptoEngine} 经 ObjectProvider 容忍缺失，
     *         CGGMP 路径报不可用，不影响 GG20 路径）
     * @throws IllegalStateException {@code cggmp-enabled=true} 但集群不完整
     *         （fail-closed，拒绝以残缺参与方集合启动）
     */
    @Bean
    public CggmpClusterSessionDriver cggmpClusterSessionDriver() {
        long deadline = cggmpDeadlineMs > 0 ? cggmpDeadlineMs : DEFAULT_CGGMP_DEADLINE_MS;
        int count = mpcEngineRouter.getEndpointCount();
        List<MpcCggmpClient> clients = new ArrayList<>(count);
        String unavailableReason = null;
        for (int i = 0; i < count; i++) {
            ManagedChannel channel = mpcEngineRouter.getChannel(i);
            if (channel == null || channel.isShutdown()) {
                unavailableReason = "channel[" + i + "] unavailable (endpoint="
                        + mpcEngineRouter.getEndpointDescription(i) + ")";
                break;
            }
            clients.add(new MpcCggmpClient(mpcEngineRouter.newBlockingStub(i), deadline));
        }
        if (unavailableReason == null && clients.size() < 2) {
            unavailableReason = clients.size() + " endpoint(s) < 2";
        }
        if (unavailableReason != null) {
            if (cggmpEnabled) {
                throw new IllegalStateException(
                        "CGGMP21 cluster driver unavailable: " + unavailableReason
                                + " — mpc.engine.cggmp-enabled=true requires a complete "
                                + "distributed cluster (>=2 endpoints, all channels up; "
                                + "production: 3 nodes via mpc.engine.endpoints). "
                                + "Refusing to start with a partial party set (B2).");
            }
            log.warn("CGGMP21 cluster driver not created: {} (cggmp-enabled=false — idle)",
                    unavailableReason);
            return null;
        }
        log.info("CGGMP21 cluster driver ready: {} parties, deadline={}ms, coordinator=endpoint 0",
                clients.size(), deadline);
        return new CggmpClusterSessionDriver(clients);
    }
}
