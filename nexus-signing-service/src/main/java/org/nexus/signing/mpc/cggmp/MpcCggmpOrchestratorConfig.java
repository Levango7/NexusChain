package org.nexus.signing.mpc.cggmp;

import io.grpc.ManagedChannel;
import org.nexus.signing.mpc.crypto.MpcEngineRouter;
import org.nexus.signing.mpc.crypto.grpc.MpcCryptoServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Objects;

/**
 * CGGMP21 orchestrator 装配（H 批）。
 *
 * <p>在 Spring 容器中构造：</p>
 * <ul>
 *   <li>{@link MpcCggmpClient} per party：每个 mpc-engine 端点对应一个 client
 *       （绑对应 ManagedChannel）</li>
 *   <li>{@link MpcCggmpOrchestrator} per party：本方 client + 协调器 client
 *       （协调器是 endpoint 0 绑的 client）</li>
 * </ul>
 *
 * <p>每方实例（生产 K8s StatefulSet 3 副本）只属于自己方 —— {@link MpcEngineRouter}
 * 在某副本视角下只有"自己"的 endpoint 可用（其它方 IP 经 P2P gRPC 走本方 engine
 * 不会触达——H 批协调器是 endpoint 0）。多进程部署时，各副本的 endpoints
 * 配置相同但 router 视角内自己 channel 即可；其它 channel 不创建。</p>
 *
 * <p>H 批：单进程内只有本方一个 orchestrator（单方视角）。多进程端到端在
 * I 批集群集成时验证。</p>
 */
@Configuration
public class MpcCggmpOrchestratorConfig {

    private static final Logger log = LoggerFactory.getLogger(MpcCggmpOrchestratorConfig.class);

    /** CGGMP21 deadline 默认 60s（sign 阶段涉及 7+ 轮 P2P）。 */
    private static final long DEFAULT_DEADLINE_MS = 60_000L;

    private final MpcEngineRouter mpcEngineRouter;

    @Autowired
    public MpcCggmpOrchestratorConfig(MpcEngineRouter mpcEngineRouter) {
        this.mpcEngineRouter = Objects.requireNonNull(mpcEngineRouter, "mpcEngineRouter");
    }

    /**
     * 本方 CGGMP21 client。
     *
     * <p>本方 gRPC channel 选 router 的 endpoint 0（每方实例的"自己 channel"）。
     * 多端点模式下 router 已按本方 IP 过滤。</p>
     */
    @Bean
    public MpcCggmpClient cggmpLocalClient() {
        return new MpcCggmpClient(requireLocalStub(), DEFAULT_DEADLINE_MS);
    }

    /**
     * 协调器 CGGMP21 client。
     *
     * <p>协调器是 endpoint 0。生产部署：endpoint 0 = 协调方进程。
     * 其它方进程的协调器 client 走 endpoint 0（远端调用）。</p>
     */
    @Bean
    public MpcCggmpClient cggmpCoordinatorClient() {
        return new MpcCggmpClient(requireCoordinatorStub(), DEFAULT_DEADLINE_MS);
    }

    /**
     * 本方 orchestrator：负责本方协议路由循环（publish→pull→pump）。
     */
    @Bean
    public MpcCggmpOrchestrator mpcCggmpOrchestrator(
            MpcCggmpClient cggmpLocalClient,
            MpcCggmpClient cggmpCoordinatorClient) {
        return new MpcCggmpOrchestrator(cggmpLocalClient, cggmpCoordinatorClient);
    }

    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub requireLocalStub() {
        ManagedChannel channel = mpcEngineRouter.getChannel(0);
        if (channel == null || channel.isShutdown()) {
            throw new IllegalStateException(
                    "MpcEngineRouter has no local channel (party 0); CGGMP21 client cannot start. "
                            + "Check mpc.engine.endpoints config and engine process readiness");
        }
        log.info("CGGMP21 local client: bound to channel (party 0)");
        return MpcCryptoServiceGrpc.newBlockingStub(channel);
    }

    private MpcCryptoServiceGrpc.MpcCryptoServiceBlockingStub requireCoordinatorStub() {
        // 协调器是 endpoint 0（同本方进程）—— H 批单进程视角
        // 多进程部署：协调方进程在 endpoint 0，远端调用；其它方调 endpoint 0
        return requireLocalStub();
    }

    /** @return 是否处于多端点模式（用于诊断） */
    public int endpointCount() {
        return mpcEngineRouter.getEndpointCount();
    }
}
