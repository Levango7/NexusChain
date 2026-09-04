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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HexFormat;
import java.util.Objects;

/**
 * CGGMP21 路径的 {@link MpcCryptoEngine} SPI 实现（H 批）。
 *
 * <p>编排层（{@code ColdWalletMultiSigService}）通过 {@link MpcCryptoEngine} SPI
 * 调用本类，内部委托给 {@link MpcCggmpOrchestrator} 跑 CGGMP21 协议循环。
 * 与 {@code GrpcMpcCryptoEngine}（GG20 路径）并存，由
 * {@code mpc.engine.cggmp-enabled} 配置选择。</p>
 *
 * <h2>SPI 映射（H 批关键）</h2>
 * <p>CGGMP21 协议模型与 GG20 不同：</p>
 * <ul>
 *   <li>GG20：每方各调一次 {@link #sign} → 产 partialSig；再调一次
 *       {@link #aggregate} → 产 (r, s)。</li>
 *   <li>CGGMP21：每方各调一次 {@link #sign} → 本方驱动 publish/pull/pump 直到
 *       sign 完成；CGGMP21 sign 阶段**直接产出 (r, s)**——
 *       mpc-engine 进程内已知道完整签名（r/s 一致跨三方）。</li>
 * </ul>
 *
 * <p>为兼容 {@link MpcCryptoEngine} SPI 的「sign 产 partial → aggregate 聚合」
 * 旧契约，本实现将 r/s 拼接填入 {@link SignResponse#getPartialSignature()}
 * （语义"完整签名 r||s"），{@link #aggregate} 视为 noop success（已聚合完成）。</p>
 *
 * <p>关键约束：</p>
 * <ul>
 *   <li>本类为单进程单方 — 每个 signing-service 实例代表一个 MPC 参与方
 *       （K8s StatefulSet 3 副本）</li>
 *   <li>本方 index 由 {@link MpcCggmpOrchestrator} 推断
 *       （myIndexOf：取本方最近一条 outgoing 的 senderIndex）</li>
 *   <li>本类的 {@link MpcCggmpOrchestrator#runKeygen} / {@link MpcCggmpOrchestrator#runAux}
 *       / {@link MpcCggmpOrchestrator#runSign} 各自直接产出 CGGMP21 协议结果，
 *       sign 输出 (r, s) 是 keygen 已在 mpc-engine 进程内合成 core share / aux info
 *       / key share 的前提</li>
 * </ul>
 *
 * <h2>配置</h2>
 * <pre>
 * mpc:
 *   engine:
 *     cggmp-enabled: true          # H 批新增：启用 CGGMP21 路径
 * </pre>
 *
 * <h2>线程安全</h2>
 * <p>本类不持有可变状态；委托给 {@link MpcCggmpOrchestrator} 内的
 * {@link MpcCggmpClient}（gRPC blocking stub 本身线程安全）。多线程并发调用安全。</p>
 */
@Component
public class CggmpMpcCryptoEngine implements MpcCryptoEngine {

    private static final Logger log = LoggerFactory.getLogger(CggmpMpcCryptoEngine.class);

    /**
     * CGGMP21 路径开关（H 批新增）。
     *
     * <p>{@code true} — 编排层走 CGGMP21 路径（F 批 RPC）；{@code false} —
     * 编排层回退到 GG20 路径（GrpcMpcCryptoEngine）。</p>
     */
    @Value("${mpc.engine.cggmp-enabled:false}")
    private boolean cggmpEnabled;

    private final MpcCggmpOrchestrator orchestrator;

    @Autowired
    public CggmpMpcCryptoEngine(MpcCggmpOrchestrator orchestrator) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
        log.info("CggmpMpcCryptoEngine initialised: cggmpEnabled={}", cggmpEnabled);
    }

    public boolean isCggmpEnabled() {
        return cggmpEnabled;
    }

    /**
     * DKG（CGGMP21 路径）—— 暂以失败上报，需要先在 keygen 端做协商。
     *
     * <p>H 批范围：仅实现 sign/aggregate SPI 适配；DKG/aux 在 v2.2.0
     * 阶段二下一批（I 批：冷启动）实施。本方法返失败不抛异常，保持 SPI 兼容。</p>
     */
    @Override
    public DkgResponse dkg(DkgRequest request) {
        log.warn("CGGMP21 dkg not yet wired into H batch; session={}", request.getSessionId());
        return new DkgResponse(null, null, null, false,
                "CGGMP21 DKG not yet implemented in H batch (planned for I batch)");
    }

    /**
     * 部分签名（CGGMP21 路径）。
     *
     * <p>把 GG20 风格的 sign 调用映射为 CGGMP21 全流程：</p>
     * <ol>
     *   <li>从 {@link SignRequest} 取 messageHash（hex，64 字符）</li>
     *   <li>把 32 字节原始 hash 喂给
     *       {@link MpcCggmpOrchestrator#runSign} —— 它驱动本方在
     *       mpc-engine 进程内的 CGGMP21 完整 sign 循环（publish/pull/pump）</li>
     *   <li>完成后 (r, s) 已产出；拼接 r||s（64 字节）作
     *       {@link SignResponse#getPartialSignature()}（与 GG20 路径的
     *       aggregate().signature 同义）</li>
     *   <li>threshold=1 场景：signerAtKeygen = [partyIndex]（仅本方参与）</li>
     * </ol>
     */
    @Override
    public SignResponse sign(SignRequest request) {
        Objects.requireNonNull(request, "request");
        String sessionId = request.getSessionId();
        int partyIndex = request.getPartyIndex();
        byte[] messageHash = hexToBytes(request.getMessageHash());
        if (messageHash == null || messageHash.length != 32) {
            log.error("sign: messageHash must be 64 hex chars (32 bytes), got session={}", sessionId);
            return new SignResponse(null, null, false,
                    "CGGMP21 sign requires 32-byte messageHash hex");
        }

        // H 批：单方签名（threshold=1 对应 1-of-1 路径，signerAtKeygen 仅含本方）。
        // 真实多签批（t-of-n 中 t>1）由编排层调用方在 I 批通过 runMultiPartySign
        // 显式传 signersAtKeygen —— 本 SPI 入口为「单方驱动本进程」语义。
        int[] signersAtKeygen = new int[]{partyIndex};

        log.info("CGGMP21 sign: session={}, party={}", sessionId, partyIndex);
        CgSignPumpResult result = orchestrator.runSign(
                sessionId, 0, 0, signersAtKeygen, messageHash);
        if (!result.isSuccess()) {
            log.error("CGGMP21 sign failed: session={}, err={}", sessionId, result.getError());
            return new SignResponse(null, null, false, result.getError());
        }
        String rHex = result.getRHex();
        String sHex = result.getSHex();
        if (rHex == null || sHex == null) {
            return new SignResponse(null, null, false, "CGGMP21 sign returned null r/s");
        }
        String concat = rHex + sHex;  // 64 字节 hex 拼接
        log.info("CGGMP21 sign done: session={}, r={}..., s={}...",
                sessionId,
                rHex.substring(0, Math.min(8, rHex.length())),
                sHex.substring(0, Math.min(8, sHex.length())));
        return new SignResponse(concat, "", true, "");
    }

    /**
     * 聚合（CGGMP21 路径）。
     *
     * <p>CGGMP21 sign 阶段已直接产出 r/s——本方法为 noop success，
     * 把入参 partialSignatures[0]（应为 r||s 拼接）解出 r/s。
     * 若编排层先调 sign 再调 aggregate，本方法对聚合后字段做完整性恢复。</p>
     */
    @Override
    public AggregateResponse aggregate(AggregateRequest request) {
        Objects.requireNonNull(request, "request");
        var partials = request.getPartialSignatures();
        if (partials == null || partials.isEmpty()) {
            return new AggregateResponse(null, null, null, 0, false,
                    "CGGMP21 aggregate: no partial signatures");
        }
        // 单方场景：partialSignatures[0] 是 r||s 拼接
        String concat = partials.get(0);
        if (concat == null || concat.length() != 128) {
            return new AggregateResponse(null, null, null, 0, false,
                    "CGGMP21 aggregate: partial signature must be 128 hex chars (64 bytes)");
        }
        String r = concat.substring(0, 64);
        String s = concat.substring(64, 128);
        // recovery_id 留 0（CGGMP21 自身不输出恢复 ID；调用方若有需求可从 secp256k1
        // 标准 v 值推算，H 批不实现）
        return new AggregateResponse(concat, r, s, 0, true, "");
    }

    /**
     * 健康检查。
     *
     * <p>H 批暂用 mpc-engine 引擎健康检查（经 cggmp client 的 healthCheck 路径
     * 由 I 批提供）。当前直接返回 true —— 编排层以 {@link #isCggmpEnabled()} 作路径选择，
     * 引擎健康由 Spring Actuator 兜底。</p>
     */
    @Override
    public boolean healthCheck() {
        return true;
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
