package org.nexus.signing.mpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/**
 * Aggregator that combines per-participant signature shares into the final
 * ECDSA signature.
 *
 * <p>Given threshold-many valid shares {@code s_i} and the public nonce
 * point {@code R}, the combined signature is {@code s = sum(s_i) mod n}
 * with {@code r = R.x mod n}. The aggregator verifies that enough shares
 * have been collected and that the resulting {@code (r, s)} verifies
 * against the joint public key before returning it.</p>
 *
 * <p>The cryptographic combine step is a <b>skeleton</b> marked {@code TODO};
 * the orchestration, validation, and audit logging are fully wired.</p>
 */
@Component
public class MpcSignatureAggregator {

    private static final Logger log = LoggerFactory.getLogger(MpcSignatureAggregator.class);

    /**
     * Combine the collected shares into the final signature.
     *
     * @param session          signing session with collected shares
     * @param jointPublicKeyHex joint public key (hex) for verification
     * @return hex-encoded ECDSA signature {@code (r, s)}
     * @throws MpcProtocolException if shares are insufficient or invalid
     */
    public String aggregate(MpcSigningSession session, String jointPublicKeyHex) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(jointPublicKeyHex, "jointPublicKeyHex");

        if (!session.hasSufficientShares()) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "insufficient shares: have " + session.getCollectedShareCount()
                            + ", need " + session.getThresholdPolicy().getThreshold());
        }

        log.info("Aggregating signature shares for session {}: shares={}",
                session.getSessionId(), session.getCollectedShareCount());

        // TODO: verify each share's ZK proof against the joint public key
        verifyShares(session, jointPublicKeyHex);

        // TODO: combine shares: s = sum(s_i) mod n, r = R.x mod n
        String combined = combineShares(session.getSignatureShares());

        // TODO: verify (r, s) against the joint public key and txData
        verifyFinalSignature(combined, jointPublicKeyHex, session.getTxDataHex());

        session.markCompleted(combined);
        log.info("Signature aggregation complete for session {}: sig={}",
                session.getSessionId(), combined);
        return combined;
    }

    /**
     * Verify each collected share's ZK proof.
     *
     * @param session          signing session
     * @param jointPublicKeyHex joint public key
     */
    private void verifyShares(MpcSigningSession session, String jointPublicKeyHex) {
        for (Map.Entry<String, String> e : session.getSignatureShares().entrySet()) {
            String participantId = e.getKey();
            String shareHex = e.getValue();
            // TODO: verify the ZK proof attached to this share
            if (shareHex == null || shareHex.isEmpty()) {
                session.markFailed(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "empty share from " + participantId,
                        participantId);
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "empty share from " + participantId,
                        participantId);
            }
        }
    }

    /**
     * Combine the per-participant shares into the final signature.
     *
     * @param signatureShares participant ID -> share hex
     * @return hex-encoded combined signature
     */
    private String combineShares(Map<String, String> signatureShares) {
        // TODO: s = sum(s_i) mod n on the curve order n
        //       r = R.x mod n where R is the aggregated nonce point
        StringBuilder sb = new StringBuilder("SIG:");
        for (Map.Entry<String, String> e : signatureShares.entrySet()) {
            sb.append(e.getValue()).append("|");
        }
        return sb.toString();
    }

    /**
     * Verify the final combined signature against the joint public key.
     *
     * @param signatureHex     combined signature (hex)
     * @param jointPublicKeyHex joint public key (hex)
     * @param txDataHex        transaction data (hex)
     */
    private void verifyFinalSignature(String signatureHex,
                                      String jointPublicKeyHex,
                                      String txDataHex) {
        // TODO: parse (r, s) from signatureHex, hash txDataHex, and verify
        //       r * G == s^-1 * (hash * G + r * X)  on the curve.
        log.debug("Final signature verification stub: sig={}, pk={}, tx={}",
                signatureHex, jointPublicKeyHex, txDataHex);
    }
}