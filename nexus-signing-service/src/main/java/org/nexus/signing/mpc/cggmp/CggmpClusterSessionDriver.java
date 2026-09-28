package org.nexus.signing.mpc.cggmp;

import org.nexus.signing.mpc.MpcProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * CGGMP21 集群会话驱动（生产多方可编排，P0-1 三进程分布式）。
 *
 * <p>把 {@code CggmpMpcE2EClusterTest} 中经 CI 实证的多方编排规则生产化：
 * 单进程（signing-service）持有每方一个 {@link MpcCggmpClient}（各绑独立
 * mpc-engine 端点）+ 一个协调器 client（relay 池 = endpoint 0），
 * 在内存中驱动 keygen → aux → assemble → sign 全流水线。
 * 私钥份额始终留在各 mpc-engine 进程内，本驱动只搬运协议消息字节。</p>
 *
 * <h2>实测编排规则（逐条移植自集群 E2E 测试，勿随意调整）</h2>
 * <ol>
 *   <li><b>keygen 串行 start</b>：逐方调用 {@code cg_start_keygen}，全部返回后
 *       才进入统一 publish→pull→pump 循环。</li>
 *   <li><b>aux 并发 start</b>：Paillier 安全素数生成为每方 ~80s（CI 慢机），
 *       串行叠加会触发连接空闲断连（30s idle）；并发 = 总时长 max 而非 sum，
 *       且连接全程活跃。</li>
 *   <li><b>阶段边界</b>：{@code cg_start_aux} / {@code cg_start_sign} 在服务端
 *       clear_session 清 relay 池——所有方 start 必须全部返回后才能发布首波
 *       outgoing（并发 start 时先发布方的消息会被后一方 start 清掉，竞态死锁）。</li>
 *   <li><b>传输抖动重试一次</b>：Netty 连接层偶发 "HTTP status code 200"
 *       报错（请求已到达且处理完成，仅响应回传失败）；start 幂等守卫容忍重试。</li>
 *   <li><b>pump 轮次守卫</b>：200 轮上限（协议实际 4-5 轮），超限时输出每方
 *       finished/outgoing 诊断。</li>
 * </ol>
 *
 * <h2>线程安全</h2>
 * <p>本类无可变共享状态，多会话可并发调用（引擎按 session_id 隔离）。
 * 同一 session_id 的并发调用由调用方串行化。</p>
 *
 * <h2>失败语义</h2>
 * <p>与 {@link MpcCggmpClient} 一致：协议/gRPC 失败返回 {@code success=false}
 * 的结果对象（不抛异常）；参数非法抛 {@link MpcProtocolException}（fail-closed）。</p>
 *
 * @see MpcCggmpClient
 * @see org.nexus.signing.mpc.cggmp.CggmpMpcCryptoEngine
 * @since 2.52.0
 */
public final class CggmpClusterSessionDriver {

    private static final Logger log = LoggerFactory.getLogger(CggmpClusterSessionDriver.class);

    /** 单阶段 pump 轮次上限（协议实际 4-5 轮；端口自集群 E2E 测试）。 */
    private static final int MAX_PUMP_ROUNDS = 200;

    /** 并发 aux start 的安全网等待上限（单 RPC 自身有 deadline，此为兜底）。 */
    private static final long AUX_START_WAIT_SECONDS = 300;

    /** 传输抖动特征（Netty 连接层问题，非协议错误；端口自集群 E2E 测试）。 */
    private static final String TRANSPORT_GLITCH_MARKER = "HTTP status code";

    /** 每方一个 client，索引 = keygen 时的 0-based party index。 */
    private final List<MpcCggmpClient> partyClients;

    /** 协调器 client（relay 池宿主，生产 = endpoint 0）。 */
    private final MpcCggmpClient coordinatorClient;

    /**
     * 协调器默认为 endpoint 0（与集群 E2E 拓扑一致）。
     *
     * @param partyClients 每方一个 client（索引 = party index，至少 2 个）
     */
    public CggmpClusterSessionDriver(List<MpcCggmpClient> partyClients) {
        this(partyClients, null);
    }

    /**
     * @param partyClients     每方一个 client（索引 = party index，至少 2 个）
     * @param coordinatorClient relay 池宿主；{@code null} 时取 {@code partyClients.get(0)}
     */
    public CggmpClusterSessionDriver(
            List<MpcCggmpClient> partyClients, MpcCggmpClient coordinatorClient) {
        Objects.requireNonNull(partyClients, "partyClients");
        if (partyClients.size() < 2) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "CGGMP cluster driver requires >=2 party clients, got " + partyClients.size());
        }
        for (int i = 0; i < partyClients.size(); i++) {
            Objects.requireNonNull(partyClients.get(i), "partyClients[" + i + "]");
        }
        this.partyClients = Collections.unmodifiableList(new ArrayList<>(partyClients));
        this.coordinatorClient = coordinatorClient != null
                ? coordinatorClient
                : partyClients.get(0);
    }

    // ============================================================
    // 对外 API
    // ============================================================

    /**
     * 跑完 keygen → aux → assembleShare 三阶段。
     *
     * <p>{@code totalParties} 必须与本驱动持有的 client 数一致（fail-closed，
     * 防端点/参与方错配导致静默漏方）。</p>
     *
     * @param sessionId    会话 ID（跨方一致）
     * @param counter      eid 防重放序号（首次 0；同 session 重跑需单调递增）
     * @param totalParties n（= client 数）
     * @param threshold    t（1 &lt;= t &lt;= n）
     * @return 成功时携带聚合公钥（压缩 SEC1 hex）
     */
    public SetupOutcome runKeygenAuxAssemble(
            String sessionId, int counter, int totalParties, int threshold) {
        if (totalParties != partyClients.size()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "totalParties=" + totalParties + " != driven clients=" + partyClients.size()
                            + " — endpoint/party mismatch");
        }
        if (threshold < 1 || threshold > totalParties) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                    "threshold=" + threshold + " out of range [1, " + totalParties + "]");
        }

        // ---------- Phase 1: keygen（串行 start，统一循环） ----------
        List<CgPumpResult> keygenStates = new ArrayList<>(totalParties);
        for (int i = 0; i < totalParties; i++) {
            CgPumpResult r = startKeygenWithRetry(i, sessionId, counter, totalParties, threshold);
            if (!r.isSuccess()) {
                return SetupOutcome.failure(
                        "keygen start party " + i + " failed: " + r.getError());
            }
            keygenStates.add(r);
        }
        KeygenAuxRun keygenRun = pumpAllKeygenAux(
                keygenStates, sessionId, allParties(totalParties), true, "keygen");
        if (keygenRun.error != null) {
            return SetupOutcome.failure(keygenRun.error);
        }
        for (int i = 0; i < totalParties; i++) {
            if (!keygenRun.states.get(i).isFinished()) {
                return SetupOutcome.failure("keygen party " + i + " not finished");
            }
        }
        String aggPk = keygenRun.states.get(0).getAggregatePublicKey();
        if (aggPk == null || aggPk.isEmpty()) {
            return SetupOutcome.failure("keygen produced empty aggregate public key");
        }
        for (int i = 1; i < totalParties; i++) {
            String pki = keygenRun.states.get(i).getAggregatePublicKey();
            if (!aggPk.equals(pki)) {
                return SetupOutcome.failure(
                        "keygen agg pubkey mismatch party " + i + " (got " + pki
                                + " vs " + aggPk + ")");
            }
        }
        log.info("CGGMP keygen done: session={}, n={}, t={}, aggPk={}",
                sessionId, totalParties, threshold, aggPk);

        // ---------- Phase 2: aux（并发 start，统一循环） ----------
        AuxStartRun auxStart = startAuxConcurrently(sessionId, counter, totalParties);
        if (auxStart.error != null) {
            return SetupOutcome.failure(auxStart.error);
        }
        KeygenAuxRun auxRun = pumpAllKeygenAux(
                auxStart.states, sessionId, allParties(totalParties), false, "aux");
        if (auxRun.error != null) {
            return SetupOutcome.failure(auxRun.error);
        }
        for (int i = 0; i < totalParties; i++) {
            if (!auxRun.states.get(i).isFinished()) {
                return SetupOutcome.failure("aux party " + i + " not finished");
            }
        }
        log.info("CGGMP aux done: session={}, n={}", sessionId, totalParties);

        // ---------- Phase 3: assembleShare ×n（IncompleteKeyShare + aux → KeyShare） ----------
        for (int i = 0; i < totalParties; i++) {
            if (!partyClients.get(i).assembleShare(sessionId)) {
                return SetupOutcome.failure("assembleShare failed for party " + i);
            }
        }
        log.info("CGGMP setup complete: session={}, aggPk={}", sessionId, aggPk);
        return SetupOutcome.success(aggPk);
    }

    /**
     * 跑完 sign 阶段（t-of-n，signersAtKeygen 恰为 t 个 keygen 原始索引）。
     *
     * @param sessionId       已完成 setup 的会话 ID
     * @param counter         eid 防重放序号（首次 0）
     * @param signersAtKeygen 本批签名方在 keygen 时的 0-based 索引
     * @param messageHash     32 字节消息哈希
     * @return 成功时携带 r/s（32 字节大端 hex）
     */
    public SignOutcome runSign(
            String sessionId, int counter, int[] signersAtKeygen, byte[] messageHash) {
        Objects.requireNonNull(signersAtKeygen, "signersAtKeygen");
        Objects.requireNonNull(messageHash, "messageHash");
        if (signersAtKeygen.length == 0) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.ILLEGAL_ARGUMENT, "signersAtKeygen is empty");
        }
        boolean[] seen = new boolean[partyClients.size()];
        for (int idx : signersAtKeygen) {
            if (idx < 0 || idx >= partyClients.size()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "signer index " + idx + " out of range [0," + (partyClients.size() - 1) + "]");
            }
            if (seen[idx]) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "duplicate signer index " + idx + " in signersAtKeygen");
            }
            seen[idx] = true;
        }

        // ---------- sign：串行 start（阶段边界清池，start 全部返回后才发布） ----------
        List<CgSignPumpResult> signStates = new ArrayList<>(signersAtKeygen.length);
        for (int b = 0; b < signersAtKeygen.length; b++) {
            CgSignPumpResult r = startSignWithRetry(
                    b, signersAtKeygen[b], sessionId, counter, signersAtKeygen, messageHash);
            if (!r.isSuccess()) {
                return SignOutcome.failure(
                        "sign start signer " + b + " (party " + signersAtKeygen[b]
                                + ") failed: " + r.getError());
            }
            signStates.add(r);
        }
        SignRun signRun = pumpAllSign(signStates, sessionId, signersAtKeygen);
        if (signRun.error != null) {
            return SignOutcome.failure(signRun.error);
        }
        String rHex = signRun.states.get(0).getRHex();
        String sHex = signRun.states.get(0).getSHex();
        if (rHex == null || sHex == null || rHex.length() != 64 || sHex.length() != 64) {
            return SignOutcome.failure(
                    "sign produced malformed signature: r=" + rHex + ", s=" + sHex);
        }
        for (int b = 1; b < signersAtKeygen.length; b++) {
            if (!rHex.equals(signRun.states.get(b).getRHex())
                    || !sHex.equals(signRun.states.get(b).getSHex())) {
                return SignOutcome.failure(
                        "signature mismatch across signers: signer " + b + " r/s differ");
            }
        }
        log.info("CGGMP sign done: session={}, signers={}, r={}",
                sessionId, Arrays.toString(signersAtKeygen), rHex);
        return SignOutcome.success(rHex, sHex);
    }

    /**
     * 验签（走协调器 client；引擎持聚合公钥）。
     */
    public CgVerifyResult verify(String sessionId, byte[] r, byte[] s, byte[] messageHash) {
        return coordinatorClient.verifySignature(sessionId, r, s, messageHash);
    }

    /**
     * 会话状态查询（走协调器 client）。
     *
     * <p>未知 session 返回 {@code success=true} + 全 false——可作引擎活性探针。</p>
     */
    public CgStatus status(String sessionId) {
        return coordinatorClient.status(sessionId);
    }

    /** 本驱动持有的参与方数量（= client 数 = n）。 */
    public int parties() {
        return partyClients.size();
    }

    // ============================================================
    // start 原语（含传输抖动重试一次）
    // ============================================================

    private CgPumpResult startKeygenWithRetry(
            int partyIdx, String sessionId, int counter, int n, int t) {
        CgPumpResult r = partyClients.get(partyIdx).startKeygen(sessionId, counter, partyIdx, n, t);
        if (!r.isSuccess() && isTransportGlitch(r.getError())) {
            log.warn("startKeygen party {} transport glitch ({}), retry once",
                    partyIdx, r.getError());
            r = partyClients.get(partyIdx).startKeygen(sessionId, counter, partyIdx, n, t);
        }
        return r;
    }

    /**
     * aux 各方并发 start（每方重试一次传输抖动）。
     *
     * @return 全部成功时携带 states；任一失败时 {@code error != null}
     */
    private AuxStartRun startAuxConcurrently(String sessionId, int counter, int n) {
        ExecutorService exec = Executors.newFixedThreadPool(n, daemonThreadFactory("cg-aux-start"));
        try {
            List<Future<CgPumpResult>> futures = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(exec.submit(() -> {
                    CgPumpResult r = partyClients.get(idx).startAux(sessionId, counter, idx, n);
                    if (!r.isSuccess() && isTransportGlitch(r.getError())) {
                        log.warn("startAux party {} transport glitch ({}), retry once "
                                + "(idempotent guard on engine)", idx, r.getError());
                        r = partyClients.get(idx).startAux(sessionId, counter, idx, n);
                    }
                    return r;
                }));
            }
            List<CgPumpResult> states = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                CgPumpResult r;
                try {
                    r = futures.get(i).get(AUX_START_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("startAux party {} await failed: {}", i, e.toString());
                    return AuxStartRun.failure("aux start party " + i + " await failed: " + e);
                }
                if (!r.isSuccess()) {
                    log.error("startAux party {} failed: {}", i, r.getError());
                    return AuxStartRun.failure("aux start party " + i + " failed: " + r.getError());
                }
                states.add(r);
            }
            return AuxStartRun.ok(states);
        } finally {
            exec.shutdownNow();
        }
    }

    private CgSignPumpResult startSignWithRetry(
            int indexInSigners,
            int partyIdx,
            String sessionId,
            int counter,
            int[] signersAtKeygen,
            byte[] messageHash) {
        CgSignPumpResult r = partyClients.get(partyIdx)
                .startSign(sessionId, counter, indexInSigners, signersAtKeygen, messageHash);
        if (!r.isSuccess() && isTransportGlitch(r.getError())) {
            log.warn("startSign signer {} (party {}) transport glitch ({}), retry once",
                    indexInSigners, partyIdx, r.getError());
            r = partyClients.get(partyIdx)
                    .startSign(sessionId, counter, indexInSigners, signersAtKeygen, messageHash);
        }
        return r;
    }

    // ============================================================
    // pump 循环（统一 publish→pull→pump；端口自集群 E2E 测试）
    // ============================================================

    private KeygenAuxRun pumpAllKeygenAux(
            List<CgPumpResult> initial,
            String sessionId,
            int[] parties,
            boolean isKeygen,
            String phase) {
        List<CgPumpResult> states = initial;
        for (int round = 0; round < MAX_PUMP_ROUNDS; round++) {
            boolean allDone = true;
            for (CgPumpResult st : states) {
                allDone &= st.isFinished();
            }
            if (allDone) {
                return KeygenAuxRun.ok(states);
            }
            // 1. 未完成方的 outgoing 全部发布到协调器 relay 池
            for (int i = 0; i < parties.length; i++) {
                CgPumpResult st = states.get(i);
                if (st.isFinished()) {
                    continue;
                }
                for (CgRelayMessageDto m : st.getOutgoing()) {
                    if (!coordinatorClient.publishRelay(m)) {
                        return KeygenAuxRun.failure(phase + ": publish failed sender="
                                + m.getSenderIndex());
                    }
                }
            }
            // 2. 各方按自己的 index 拉取 + 3. pump 到各自引擎
            List<CgPumpResult> next = new ArrayList<>(parties.length);
            for (int i = 0; i < parties.length; i++) {
                CgPumpResult st = states.get(i);
                if (st.isFinished()) {
                    next.add(st);
                    continue;
                }
                int partyIdx = parties[i];
                List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(sessionId, partyIdx);
                if (incoming == null) {
                    incoming = new ArrayList<>();
                }
                CgPumpResult r = isKeygen
                        ? partyClients.get(partyIdx).pumpKeygen(sessionId, incoming)
                        : partyClients.get(partyIdx).pumpAux(sessionId, incoming);
                if (!r.isSuccess()) {
                    return KeygenAuxRun.failure(
                            phase + ": pump party " + partyIdx + " failed: " + r.getError());
                }
                next.add(r);
            }
            states = next;
        }
        return KeygenAuxRun.failure(phase + " stuck after " + MAX_PUMP_ROUNDS
                + " rounds;" + renderKeygenAuxDiagnostics(states, parties));
    }

    private SignRun pumpAllSign(
            List<CgSignPumpResult> initial, String sessionId, int[] signers) {
        List<CgSignPumpResult> states = initial;
        for (int round = 0; round < MAX_PUMP_ROUNDS; round++) {
            boolean allDone = true;
            for (CgSignPumpResult st : states) {
                allDone &= st.isFinished();
            }
            if (allDone) {
                return SignRun.ok(states);
            }
            for (int b = 0; b < signers.length; b++) {
                CgSignPumpResult st = states.get(b);
                if (st.isFinished()) {
                    continue;
                }
                for (CgRelayMessageDto m : st.getOutgoing()) {
                    if (!coordinatorClient.publishRelay(m)) {
                        return SignRun.failure("sign: publish failed sender=" + m.getSenderIndex());
                    }
                }
            }
            List<CgSignPumpResult> next = new ArrayList<>(signers.length);
            for (int b = 0; b < signers.length; b++) {
                CgSignPumpResult st = states.get(b);
                if (st.isFinished()) {
                    next.add(st);
                    continue;
                }
                int keygenIdx = signers[b];
                List<CgRelayMessageDto> incoming = coordinatorClient.pullRelay(sessionId, keygenIdx);
                if (incoming == null) {
                    incoming = new ArrayList<>();
                }
                CgSignPumpResult r = partyClients.get(keygenIdx).pumpSign(sessionId, incoming);
                if (!r.isSuccess()) {
                    return SignRun.failure(
                            "sign: pump signer " + b + " (party " + keygenIdx + ") failed: "
                                    + r.getError());
                }
                next.add(r);
            }
            states = next;
        }
        return SignRun.failure("sign stuck after " + MAX_PUMP_ROUNDS + " rounds");
    }

    // ============================================================
    // 工具
    // ============================================================

    /** 传输抖动判定：Netty 连接层问题（请求已到达引擎），非协议错误。 */
    private static boolean isTransportGlitch(String error) {
        return error != null && error.contains(TRANSPORT_GLITCH_MARKER);
    }

    private static int[] allParties(int n) {
        int[] all = new int[n];
        for (int i = 0; i < n; i++) {
            all[i] = i;
        }
        return all;
    }

    /** 超限诊断：每方 finished / outgoing 计数（定位未完成方与消息堆积点）。 */
    private static String renderKeygenAuxDiagnostics(List<CgPumpResult> states, int[] parties) {
        StringBuilder diag = new StringBuilder();
        for (int i = 0; i < states.size(); i++) {
            CgPumpResult st = states.get(i);
            diag.append("\n  party=").append(parties[i])
                    .append(" finished=").append(st.isFinished())
                    .append(" outgoing=")
                    .append(st.getOutgoing() == null ? 0 : st.getOutgoing().size());
        }
        return diag.toString();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ============================================================
    // 结果与内部持有类型
    // ============================================================

    /** keygen→aux→assemble 三阶段结果。 */
    public static final class SetupOutcome {
        private final boolean success;
        private final String error;
        private final String aggregatePublicKeyHex;

        private SetupOutcome(boolean success, String error, String aggregatePublicKeyHex) {
            this.success = success;
            this.error = error == null ? "" : error;
            this.aggregatePublicKeyHex = aggregatePublicKeyHex;
        }

        static SetupOutcome success(String aggregatePublicKeyHex) {
            return new SetupOutcome(true, "", aggregatePublicKeyHex);
        }

        static SetupOutcome failure(String error) {
            return new SetupOutcome(false, error, null);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public String getAggregatePublicKeyHex() { return aggregatePublicKeyHex; }
    }

    /** sign 阶段结果（r/s 为 32 字节大端 hex）。 */
    public static final class SignOutcome {
        private final boolean success;
        private final String error;
        private final String rHex;
        private final String sHex;

        private SignOutcome(boolean success, String error, String rHex, String sHex) {
            this.success = success;
            this.error = error == null ? "" : error;
            this.rHex = rHex;
            this.sHex = sHex;
        }

        static SignOutcome success(String rHex, String sHex) {
            return new SignOutcome(true, "", rHex, sHex);
        }

        static SignOutcome failure(String error) {
            return new SignOutcome(false, error, null, null);
        }

        public boolean isSuccess() { return success; }
        public String getError() { return error; }
        public String getRHex() { return rHex; }
        public String getSHex() { return sHex; }
    }

    /** pump 循环返回值（error == null 表示成功）。 */
    private static final class KeygenAuxRun {
        final List<CgPumpResult> states;
        final String error;

        private KeygenAuxRun(List<CgPumpResult> states, String error) {
            this.states = states;
            this.error = error;
        }

        static KeygenAuxRun ok(List<CgPumpResult> states) { return new KeygenAuxRun(states, null); }
        static KeygenAuxRun failure(String error) { return new KeygenAuxRun(null, error); }
    }

    /** 并发 aux start 返回值（error == null 表示成功）。 */
    private static final class AuxStartRun {
        final List<CgPumpResult> states;
        final String error;

        private AuxStartRun(List<CgPumpResult> states, String error) {
            this.states = states;
            this.error = error;
        }

        static AuxStartRun ok(List<CgPumpResult> states) { return new AuxStartRun(states, null); }
        static AuxStartRun failure(String error) { return new AuxStartRun(null, error); }
    }

    /** sign pump 循环返回值（error == null 表示成功）。 */
    private static final class SignRun {
        final List<CgSignPumpResult> states;
        final String error;

        private SignRun(List<CgSignPumpResult> states, String error) {
            this.states = states;
            this.error = error;
        }

        static SignRun ok(List<CgSignPumpResult> states) { return new SignRun(states, null); }
        static SignRun failure(String error) { return new SignRun(null, error); }
    }
}
