package org.nexus.signing.mpc;

import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * MPC signer executing the GG18/GG20 signing rounds.
 *
 * <p>GG18/GG20 signing proceeds in {@link MpcSigningSession#SIGN_ROUNDS}
 * rounds of point-to-point and broadcast messages, after which each
 * participant produces a local signature share {@code s_i}. The shares are
 * then combined by {@link MpcSignatureAggregator} into the final ECDSA
 * signature {@code (r, s)}.</p>
 *
 * <p>P3-T5：在签名编排主链路添加业务 span（signing.orchestrate →
 * signing.mpc.round × N → signing.mpc.commit / signing.mpc.verify），
 * span 树结构见 docs/tracing-business-span.md。</p>
 *
 * <p>This class is a <b>skeleton</b>: the round orchestration and message
 * bookkeeping are fully wired, but the cryptographic body of each round
 * (Paillier homomorphic operations, ZK proofs, MtA protocol) is marked
 * {@code FROZEN} per ADR-001 and must be backed by a proven library.
 * 解冻条件见 docs/adr/ADR-001-research-layer-freeze.md</p>
 */
@Component
public class MpcSigner {

    private static final Logger log = LoggerFactory.getLogger(MpcSigner.class);

    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    private final Tracer tracer;

    @Autowired
    public MpcSigner(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 测试用兼容构造器：不注入 Tracer，业务 span 降级为 no-op。 */
    public MpcSigner() {
        this(null);
    }

    /**
     * Execute all signing rounds for the given session, populating each
     * participant's signature share.
     *
     * @param session the signing session to drive
     * @param shares  the per-participant key shares (must match session participants)
     * @throws MpcProtocolException on any round failure
     */
    public void runSigningRounds(MpcSigningSession session, List<MpcKeyShare> shares) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(shares, "shares");

        // P3-T5：签名编排主 span（signing.orchestrate）
        try (BusinessSpan orchSpan = BusinessSpan.start(tracer, "signing.orchestrate")
                .attr("signing.session.id", session.getSessionId())
                .attr("signing.participants", session.getParticipants().size())
                .attr("signing.rounds.total", MpcSigningSession.SIGN_ROUNDS)) {
            try {
                log.info("Starting GG18/GG20 signing: session={}, participants={}",
                        session.getSessionId(), session.getParticipants().size());

                if (!session.getThresholdPolicy().isQuorumReached(session.getParticipants())) {
                    throw new MpcProtocolException(
                            MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                            "insufficient online participants to reach threshold "
                                    + session.getThresholdPolicy().getThreshold());
                }
                orchSpan.attr("signing.threshold", session.getThresholdPolicy().getThreshold());

                for (int round = 1; round <= MpcSigningSession.SIGN_ROUNDS; round++) {
                    session.advanceToRound(round);
                    // P3-T5：MPC 单轮 span（signing.mpc.round）
                    try (BusinessSpan roundSpan = BusinessSpan.start(tracer, "signing.mpc.round")
                            .attr("signing.session.id", session.getSessionId())
                            .attr("signing.round.index", round)
                            .attr("signing.round.total", MpcSigningSession.SIGN_ROUNDS)) {
                        try {
                            executeRound(round, session, shares);
                            log.debug("Signing round {}/{} completed for session {}",
                                    round, MpcSigningSession.SIGN_ROUNDS, session.getSessionId());
                            roundSpan.attr("signing.round.messages",
                                    session.getParticipants().size()).success();
                        } catch (Exception e) {
                            roundSpan.error(e);
                            throw e;
                        }
                    }
                }

                // After the final round each participant holds a local signature share s_i.
                // FROZEN per ADR-001: extract s_i from the local state and record it on the session.
                // 解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
                for (MpcParticipant p : session.getParticipants()) {
                    session.recordSignatureShare(
                            p.getParticipantId(),
                            "FROZEN-sig-share-" + p.getParticipantId());
                }
                session.markAggregating();
                log.info("Signing rounds complete for session {}; shares collected={}",
                        session.getSessionId(), session.getCollectedShareCount());
                orchSpan.attr("signing.shares.collected", session.getCollectedShareCount())
                        .success();
            } catch (Exception e) {
                orchSpan.error(e);
                throw e;
            }
        }
    }

    /**
     * Execute one signing round.
     *
     * @param round   1-based round number
     * @param session signing session
     * @param shares  per-participant key shares
     */
    private void executeRound(int round,
                              MpcSigningSession session,
                              List<MpcKeyShare> shares) {
        // FROZEN per ADR-001: implement the cryptographic body of each GG18/GG20 signing round:
        //   round 1: sample local k_i, broadcast R_i = k_i * G
        //   round 2: MtA (Multiplicative-to-Additive) protocol for k_i * x_j
        //   round 3: MtA for k_i * k_j
        //   round 4: aggregate R = sum(R_i), derive r = R.x mod n
        //   round 5: compute local share s_i = k_i * m + r * x_i (mod n)
        //   round 6: ZK proof of correct share
        //   round 7: broadcast s_i
        //   解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
        for (MpcParticipant p : session.getParticipants()) {
            // FROZEN per ADR-001: perform local computation for participant p in this round
            //       and broadcast / receive messages via the transport layer.
            session.recordMessage(round, p.getParticipantId(), "FROZEN-msg-r" + round + "-" + p.getParticipantId());
        }
    }
}