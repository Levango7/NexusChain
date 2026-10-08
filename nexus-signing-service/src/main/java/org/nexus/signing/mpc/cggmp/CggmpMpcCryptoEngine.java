package org.nexus.signing.mpc.cggmp;

import org.nexus.signing.mpc.MpcProtocolException;
import org.nexus.signing.mpc.crypto.AggregateRequest;
import org.nexus.signing.mpc.crypto.AggregateResponse;
import org.nexus.signing.mpc.crypto.DkgRequest;
import org.nexus.signing.mpc.crypto.DkgResponse;
import org.nexus.signing.mpc.crypto.MpcCryptoEngine;
import org.nexus.signing.mpc.crypto.SignRequest;
import org.nexus.signing.mpc.crypto.SignResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * CGGMP21 路径的 {@link MpcCryptoEngine} SPI 实现（P0-1：三进程分布式生产化）。
 *
 * <p>编排层（{@code ColdWalletMultiSigService}）通过本类调用 CGGMP21 协议。
 * 内部委托 {@link CggmpClusterSessionDriver}——**单进程驱动全部 n 个
 * mpc-engine 节点**（Model A）：私钥份额 AES-256-GCM 加密后驻留各引擎
 * 进程磁盘（NXC1 信封），永不离开引擎进程，本类不含任何份额路径。</p>
 *
 * <h2>SPI 映射（与 GG20 路径的差异）</h2>
 * <ul>
 *   <li><b>dkg</b>：调用即跑完整集群仪式（keygen → aux → assembleShare ×n），
 *       返回聚合公钥。份额不出引擎（{@code DkgResponse.keyShare} 恒为 null）。</li>
 *   <li><b>sign</b>：调用即驱动 t 个签名方跑完 sign 协议，直接产出 (r, s)；
 *       拼接 r||s（64 字节 hex）填入 {@link SignResponse#getPartialSignature()}
 *       （语义"完整签名"）。</li>
 *   <li><b>aggregate</b>：noop success——拆出 r/s 供编排层记账。</li>
 * </ul>
 *
 * <h2>会话 ID 语义（生产关键）</h2>
 * <p>mpc-engine 按 session_id 持久化/恢复份额（"keygen 仪式一次、长期反复签名"，
 * {@code cggmp_state.rs} StartSign 读守卫）。因此 **签名用的 session_id 必须
 * 与 keygen 时一致**。钱包场景用 {@link #walletSessionId(String)} 从 walletId
 * 派生稳定 ID（如 {@code cw-<sha256 前16字节>}），钱包初始化（DKG）与每笔
 * 转账签名（sign）共用同一 ID；转账自身的 sessionId（随机 UUID）与引擎会话
 * 解耦。同一会话的多次签名以递增 counter（eid 序号）区分协议执行——
 * {@link #signCounter}。</p>
 *
 * <h2>配置</h2>
 * <pre>
 * mpc:
 *   engine:
 *     cggmp-enabled: true          # 启用 CGGMP21 路径（需 3 端点分布式集群）
 *     cggmp:
 *       deadline-ms: 120000        # 单 RPC deadline（aux 素数生成慢）
 *       signers: "0,1"             # 签名方 keygen 索引；空 = 全体参与方
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>本类无可变状态；{@link CggmpClusterSessionDriver} 多会话并发安全。
 * 同一钱包的并发签名由编排层串行化（同 session_id 在引擎侧单状态机）。</p>
 *
 * @see CggmpClusterSessionDriver
 * @since 2.52.0
 */
@Component
public class CggmpMpcCryptoEngine implements MpcCryptoEngine {

    private static final Logger log = LoggerFactory.getLogger(CggmpMpcCryptoEngine.class);

    /** 驱动缺失（未配置分布式集群）时的统一失败消息。 */
    private static final String DRIVER_UNAVAILABLE =
            "CGGMP21 cluster driver unavailable — requires >=2 mpc.engine.endpoints "
                    + "(production: 3 nodes, mpc.engine.distributed-mode=true)";

    /**
     * CGGMP21 引擎开关。
     *
     * <p>{@code true} — 编排层启用真实 MPC 引擎（CGGMP21 集群）；
     * {@code false}（默认）— 引擎上下文不装配，编排层降级为
     * FROZEN skeleton 记账流程（GG20 退役后无第二路径可回退）。</p>
     */
    @Value("${mpc.engine.cggmp-enabled:false}")
    private boolean cggmpEnabled;

    /**
     * 本批签名方在 keygen 时的 0-based 索引（逗号分隔，如 {@code "0,1"}）。
     *
     * <p>空 = 全体参与方（n-of-n，要求全部引擎在线）。生产 2-of-3 配
     * {@code "0,1"}，容忍 1 个离线方。</p>
     */
    @Value("${mpc.engine.cggmp.signers:}")
    private String signersConfig;

    private final ObjectProvider<CggmpClusterSessionDriver> driverProvider;

    /**
     * sign 执行的 eid 序号（u32 位模式传递）。
     *
     * <p>cggmp21 上游契约：ExecutionId "每次协议执行必须唯一"。钱包场景下
     * session_id 长期复用（份额恢复的前提），唯一性只能由 counter 提供——
     * 每笔签名取一个新值。本进程内单调递增 + 随机基点：进程内严格唯一，
     * 跨重启/多副本的序列重叠概率 ~1e-9 量级（u32 生日界）。</p>
     *
     * <p>残余风险（记录在案）：counter=0 是 keygen/aux 的固定 eid（仪式幂等
     * 需要）；签名 counter 若跨进程碰撞，后果是两次执行共享 eid（协议非密钥
     * 泄漏级问题——nonce 每轮随机）。根治 = Rust 侧持久化序号或扩宽 eid。</p>
     */
    private final java.util.concurrent.atomic.AtomicInteger signCounter =
            new java.util.concurrent.atomic.AtomicInteger(ThreadLocalRandom.current().nextInt());

    @Autowired
    public CggmpMpcCryptoEngine(ObjectProvider<CggmpClusterSessionDriver> driverProvider) {
        this.driverProvider = Objects.requireNonNull(driverProvider, "driverProvider");
        log.info("CggmpMpcCryptoEngine initialised: cggmpEnabled={}", cggmpEnabled);
    }

    public boolean isCggmpEnabled() {
        return cggmpEnabled;
    }

    /**
     * 钱包维度的引擎会话 ID（keygen 与签名必须一致）。
     *
     * <p>派生规则：{@code "cw-" + SHA-256(walletId) 前 16 字节 hex}——确定性、
     * 满足引擎 session_id 字符集（{@code [0-9a-zA-Z-]}，≤128），不含 walletId
     * 原文（避免外部 ID 形态污染引擎命名空间）。</p>
     *
     * @param walletId 钱包 ID（非空）
     * @return 引擎会话 ID
     */
    public static String walletSessionId(String walletId) {
        Objects.requireNonNull(walletId, "walletId");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(walletId.getBytes(StandardCharsets.UTF_8));
            return "cw-" + HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * DKG（CGGMP21 集群仪式：keygen → aux → assembleShare ×n）。
     *
     * <p>{@code request.getSessionId()} 必须是钱包维度的稳定 ID（见
     * {@link #walletSessionId(String)}），签名阶段复用同值。
     * {@code getTotalParties()} 须与集群端点数一致（fail-closed）。
     * {@code getPartyIndex()} 不参与——驱动方持有全部参与方。</p>
     *
     * @return 成功时 {@code publicKey} = 聚合公钥（压缩 SEC1 hex）；
     *         {@code keyShare}/{@code proof} 恒 null（份额驻留引擎进程）
     */
    @Override
    public DkgResponse dkg(DkgRequest request) {
        Objects.requireNonNull(request, "request");
        CggmpClusterSessionDriver driver = driverOrNull();
        if (driver == null) {
            return new DkgResponse(null, null, null, false, DRIVER_UNAVAILABLE);
        }
        log.info("CGGMP21 dkg: session={}, n={}, t={}",
                request.getSessionId(), request.getTotalParties(), request.getThreshold());
        CggmpClusterSessionDriver.SetupOutcome outcome = driver.runKeygenAuxAssemble(
                request.getSessionId(), 0, request.getTotalParties(), request.getThreshold());
        if (!outcome.isSuccess()) {
            log.error("CGGMP21 dkg failed: session={}, err={}",
                    request.getSessionId(), outcome.getError());
            return new DkgResponse(null, null, null, false, outcome.getError());
        }
        log.info("CGGMP21 dkg done: session={}, aggPk={}",
                request.getSessionId(), outcome.getAggregatePublicKeyHex());
        return new DkgResponse(outcome.getAggregatePublicKeyHex(), null, null, true, "");
    }

    /**
     * 签名（CGGMP21 集群 sign：t 个签名方直接产出 r/s）。
     *
     * <p>签名方集合由 {@code mpc.engine.cggmp.signers} 指定（空 = 全体）；
     * {@code request.getPartyIndex()} 不参与路由（驱动层固定按配置的
     * 签名方集合驱动机群）。</p>
     *
     * @return 成功时 {@code partialSignature} = r||s（64 字节 hex）
     */
    @Override
    public SignResponse sign(SignRequest request) {
        Objects.requireNonNull(request, "request");
        String sessionId = request.getSessionId();
        byte[] messageHash = hexToBytes(request.getMessageHash());
        if (messageHash == null || messageHash.length != 32) {
            log.error("CGGMP21 sign: messageHash must be 64 hex chars (32 bytes), session={}",
                    sessionId);
            return new SignResponse(null, null, false,
                    "CGGMP21 sign requires 32-byte messageHash hex");
        }
        CggmpClusterSessionDriver driver = driverOrNull();
        if (driver == null) {
            return new SignResponse(null, null, false, DRIVER_UNAVAILABLE);
        }
        int[] signersAtKeygen = resolveSigners(driver.parties());
        int counter = signCounter.getAndIncrement();
        log.info("CGGMP21 sign: session={}, signers={}, eidCounter={}",
                sessionId, java.util.Arrays.toString(signersAtKeygen), counter);
        CggmpClusterSessionDriver.SignOutcome outcome = driver.runSign(
                sessionId, counter, signersAtKeygen, messageHash);
        if (!outcome.isSuccess()) {
            log.error("CGGMP21 sign failed: session={}, err={}", sessionId, outcome.getError());
            return new SignResponse(null, null, false, outcome.getError());
        }
        String concat = outcome.getRHex() + outcome.getSHex();  // 64 字节 hex 拼接
        log.info("CGGMP21 sign done: session={}, r={}..., s={}...",
                sessionId,
                outcome.getRHex().substring(0, Math.min(8, outcome.getRHex().length())),
                outcome.getSHex().substring(0, Math.min(8, outcome.getSHex().length())));
        return new SignResponse(concat, "", true, "");
    }

    /**
     * 聚合（CGGMP21 路径）。
     *
     * <p>sign 阶段已直接产出 r/s——本方法为 noop success，
     * 把入参 partialSignatures[0]（r||s 拼接）解出 r/s 供编排层记账。</p>
     */
    @Override
    public AggregateResponse aggregate(AggregateRequest request) {
        Objects.requireNonNull(request, "request");
        var partials = request.getPartialSignatures();
        if (partials == null || partials.isEmpty()) {
            return new AggregateResponse(null, null, null, 0, false,
                    "CGGMP21 aggregate: no partial signatures");
        }
        // 集群场景：partialSignatures[0] 是 r||s 拼接
        String concat = partials.get(0);
        if (concat == null || concat.length() != 128) {
            return new AggregateResponse(null, null, null, 0, false,
                    "CGGMP21 aggregate: partial signature must be 128 hex chars (64 bytes)");
        }
        String r = concat.substring(0, 64);
        String s = concat.substring(64, 128);
        // recovery_id 留 0（CGGMP21 自身不输出恢复 ID；调用方若有需求可从 secp256k1
        // 标准 v 值推算，不实现）
        return new AggregateResponse(concat, r, s, 0, true, "");
    }

    /**
     * 健康检查：对协调器（endpoint 0）发一次状态 RPC 探测。
     *
     * <p>未知 session 返回 {@code success=true}（引擎存活即视为健康）；
     * 驱动缺失或 gRPC 失败返回 false（编排层据此回退 GG20 / FROZEN）。</p>
     */
    @Override
    public boolean healthCheck() {
        CggmpClusterSessionDriver driver = driverOrNull();
        if (driver == null) {
            return false;
        }
        String probeSessionId = "hc-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
        CgStatus status = driver.status(probeSessionId);
        if (!status.isSuccess()) {
            log.debug("CGGMP21 healthCheck failed: {}", status.getError());
            return false;
        }
        return true;
    }

    // ============================================================
    // 内部
    // ============================================================

    private CggmpClusterSessionDriver driverOrNull() {
        return driverProvider.getIfAvailable();
    }

    /**
     * 解析签名方索引配置；空 = 全体参与方 0..n-1。
     *
     * <p>格式错误 / 越界 / 重复 → {@link MpcProtocolException}（fail-closed：
     * 配置错误不静默降级签名方集合）。</p>
     */
    private int[] resolveSigners(int parties) {
        if (signersConfig == null || signersConfig.isBlank()) {
            int[] all = new int[parties];
            for (int i = 0; i < parties; i++) {
                all[i] = i;
            }
            return all;
        }
        String[] parts = signersConfig.split(",");
        int[] signers = new int[parts.length];
        boolean[] seen = new boolean[parties];
        for (int i = 0; i < parts.length; i++) {
            int idx;
            try {
                idx = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "mpc.engine.cggmp.signers contains non-numeric entry: '"
                                + parts[i] + "'");
            }
            if (idx < 0 || idx >= parties) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "mpc.engine.cggmp.signers index " + idx + " out of range [0,"
                                + (parties - 1) + "]");
            }
            if (seen[idx]) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.ILLEGAL_ARGUMENT,
                        "mpc.engine.cggmp.signers contains duplicate index " + idx);
            }
            seen[idx] = true;
            signers[i] = idx;
        }
        return signers;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
