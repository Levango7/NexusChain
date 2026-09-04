package org.nexus.signing.mpc.cggmp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CGGMP21 协议路由循环顶层门面（G 批）。
 *
 * <p>扮演与 Rust 端 e2e 测试同等的「协议执行者 + 协调器路由」职责：</p>
 * <ul>
 *   <li>本方：{@link MpcCggmpClient}（每方一个 stub，绑对应 endpoint）</li>
 *   <li>协调器：单独的 {@link MpcCggmpClient}（绑 node0 endpoint；用其
 *       relay 池做消息汇聚）</li>
 * </ul>
 *
 * <h2>循环协议</h2>
 * <pre>
 *   1. 启动阶段：localClient.startXxx(...) → localOutgoing[]
 *   2. 把 localOutgoing[] publish 到协调器（coordinatorClient.publishRelay）
 *   3. 从协调器拉本方入站消息（coordinatorClient.pullRelay）
 *   4. 喂入 localClient.pumpXxx(incoming) → localOutgoing[]（重复 2-4 直到 finished）
 *   5. 阶段间不切换——同 session 在同一协调器实例下串行 keygen→aux→assemble→sign
 * </pre>
 *
 * <h2>错误处理</h2>
 * <p>任一阶段 {@code success=false} 立即返回失败结果（不再继续后续阶段）。
 * 上层编排（MpcSigner / ColdWalletMultiSigService 等）据此决定熔断 / 重试。</p>
 */
public final class MpcCggmpOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MpcCggmpOrchestrator.class);

    private final MpcCggmpClient localClient;
    private final MpcCggmpClient coordinatorClient;

    public MpcCggmpOrchestrator(
            MpcCggmpClient localClient,
            MpcCggmpClient coordinatorClient) {
        this.localClient = Objects.requireNonNull(localClient, "localClient");
        this.coordinatorClient = Objects.requireNonNull(coordinatorClient, "coordinatorClient");
    }

    /**
     * 跑完 keygen 阶段。
     *
     * @return 完成时携带聚合公钥的 {@link CgPumpResult}
     */
    public CgPumpResult runKeygen(
            String sessionId, int counter, int myIndex, int totalParties, int threshold) {
        log.info("CGGMP keygen start: session={}, my={}, n={}, t={}",
                sessionId, myIndex, totalParties, threshold);

        CgPumpResult first = localClient.startKeygen(
                sessionId, counter, myIndex, totalParties, threshold);
        if (!first.isSuccess()) {
            log.error("CGGMP keygen start failed: session={}, err={}", sessionId, first.getError());
            return first;
        }
        return pumpLoop(sessionId, first, true);
    }

    /**
     * 跑完 aux 阶段。
     */
    public CgPumpResult runAux(
            String sessionId, int counter, int myIndex, int totalParties) {
        log.info("CGGMP aux start: session={}, my={}, n={}", sessionId, myIndex, totalParties);

        CgPumpResult first = localClient.startAux(
                sessionId, counter, myIndex, totalParties);
        if (!first.isSuccess()) {
            log.error("CGGMP aux start failed: session={}, err={}", sessionId, first.getError());
            return first;
        }
        return pumpLoop(sessionId, first, false);
    }

    /**
     * 跑完 sign 阶段。
     *
     * @param signersAtKeygen 本批签名方在 keygen 时的 0-based 索引
     * @param messageHash     32 字节消息哈希
     */
    public CgSignPumpResult runSign(
            String sessionId,
            int counter,
            int myIndexInSigners,
            int[] signersAtKeygen,
            byte[] messageHash) {
        log.info("CGGMP sign start: session={}, myIdxInSigners={}, t={}",
                sessionId, myIndexInSigners,
                signersAtKeygen == null ? 0 : signersAtKeygen.length);

        CgSignPumpResult first = localClient.startSign(
                sessionId, counter, myIndexInSigners, signersAtKeygen, messageHash);
        if (!first.isSuccess()) {
            log.error("CGGMP sign start failed: session={}, err={}", sessionId, first.getError());
            return first;
        }
        return signPumpLoop(sessionId, first);
    }

    // ============================================================
    // 内部循环
    // ============================================================

    /**
     * 通用阶段（keygen/aux）泵循环。
     *
     * <p>复用 start 已产出的 outgoing 作为循环起点（避免一次额外 publish
     * 又被下次拉回的开销）。</p>
     */
    private CgPumpResult pumpLoop(
            String sessionId, CgPumpResult first, boolean isKeygen) {
        CgPumpResult last = first;
        int iter = 0;
        while (!last.isFinished()) {
            iter++;
            if (iter > 10_000) {
                log.error("CGGMP pump loop runaway guard: session={}, iter={}", sessionId, iter);
                return CgPumpResult.failure("pump loop exceeded 10000 iterations");
            }

            // 1. publish 本方 outgoing 到协调器
            for (CgRelayMessageDto m : last.getOutgoing()) {
                if (!coordinatorClient.publishRelay(m)) {
                    log.error("publish relay failed: session={}, msg={}", sessionId, m);
                    return CgPumpResult.failure(
                            "publish relay failed for sender=" + m.getSenderIndex());
                }
            }

            // 2. pull 本方入站（协调器按 receiver 过滤 + 排除自发）
            List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(
                    sessionId, myIndexOf(last.getOutgoing()));
            if (incoming == null) {
                incoming = new ArrayList<>();
            }

            // 3. pump
            last = isKeygen
                    ? localClient.pumpKeygen(sessionId, incoming)
                    : localClient.pumpAux(sessionId, incoming);
            if (!last.isSuccess()) {
                log.error("CGGMP pump failed: session={}, iter={}, err={}",
                        sessionId, iter, last.getError());
                return last;
            }
        }
        log.info("CGGMP phase finished: session={}, iter={}, isKeygen={}",
                sessionId, iter, isKeygen);
        return last;
    }

    private CgSignPumpResult signPumpLoop(String sessionId, CgSignPumpResult first) {
        CgSignPumpResult last = first;
        int iter = 0;
        while (!last.isFinished()) {
            iter++;
            if (iter > 10_000) {
                log.error("CGGMP sign pump loop runaway: session={}, iter={}", sessionId, iter);
                return CgSignPumpResult.failure("sign pump loop exceeded 10000 iterations");
            }
            for (CgRelayMessageDto m : last.getOutgoing()) {
                if (!coordinatorClient.publishRelay(m)) {
                    log.error("publish relay failed (sign): session={}, msg={}", sessionId, m);
                    return CgSignPumpResult.failure(
                            "publish relay failed for sender=" + m.getSenderIndex());
                }
            }
            List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(
                    sessionId, myIndexOf(last.getOutgoing()));
            if (incoming == null) {
                incoming = new ArrayList<>();
            }
            last = localClient.pumpSign(sessionId, incoming);
            if (!last.isSuccess()) {
                log.error("CGGMP sign pump failed: session={}, iter={}, err={}",
                        sessionId, iter, last.getError());
                return last;
            }
        }
        log.info("CGGMP sign finished: session={}, iter={}", sessionId, iter);
        return last;
    }

    /**
     * 推断本方 index（用于 pullRelay）。优先取最后一条 outgoing 的 senderIndex。
     * 留空取 -1（服务端会做兜底）。
     */
    private static int myIndexOf(List<CgRelayMessageDto> outgoing) {
        if (outgoing == null || outgoing.isEmpty()) {
            return -1;
        }
        for (int i = outgoing.size() - 1; i >= 0; i--) {
            int idx = outgoing.get(i).getSenderIndex();
            if (idx >= 0) {
                return idx;
            }
        }
        return -1;
    }
}
