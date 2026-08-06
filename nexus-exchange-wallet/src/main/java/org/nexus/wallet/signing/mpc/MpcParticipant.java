package org.nexus.wallet.signing.mpc;

import java.util.Objects;

/**
 * A participant in an MPC (GG18/GG20) threshold-signature protocol.
 *
 * <p>Each participant is identified by a stable {@code participantId} (e.g. a
 * node ID or operator ID), exposes a network {@code endpoint} (host:port or
 * gRPC target) for the protocol transport, and publishes a public key share
 * used to verify the joint public key and signature shares.</p>
 *
 * <p>Participants are value objects: equality is by {@code participantId}
 * only, so a participant can be looked up in a set/map regardless of which
 * key share it currently advertises.</p>
 */
public final class MpcParticipant {

    /** Stable participant identifier (e.g. node ID). */
    private final String participantId;

    /** Network endpoint (host:port or gRPC target string). */
    private final String endpoint;

    /** Public key share (hex-encoded curve point) advertised by this participant. */
    private final String publicKeyShareHex;

    /** Whether this participant is currently reachable. */
    private final boolean online;

    /**
     * Construct a participant.
     *
     * @param participantId     stable ID
     * @param endpoint          network endpoint
     * @param publicKeyShareHex public key share (hex)
     * @param online            reachability flag
     */
    public MpcParticipant(String participantId,
                          String endpoint,
                          String publicKeyShareHex,
                          boolean online) {
        this.participantId = Objects.requireNonNull(participantId, "participantId");
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.publicKeyShareHex = Objects.requireNonNull(publicKeyShareHex, "publicKeyShareHex");
        this.online = online;
    }

    /**
     * Convenience constructor for an online participant.
     *
     * @param participantId     stable ID
     * @param endpoint          network endpoint
     * @param publicKeyShareHex public key share (hex)
     */
    public MpcParticipant(String participantId, String endpoint, String publicKeyShareHex) {
        this(participantId, endpoint, publicKeyShareHex, true);
    }

    /**
     * @return the stable participant ID
     */
    public String getParticipantId() {
        return participantId;
    }

    /**
     * @return the network endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * @return the advertised public key share (hex)
     */
    public String getPublicKeyShareHex() {
        return publicKeyShareHex;
    }

    /**
     * @return {@code true} if the participant is currently reachable
     */
    public boolean isOnline() {
        return online;
    }

    /**
     * Return a copy of this participant with the given online flag.
     *
     * @param online new reachability flag
     * @return a new participant instance
     */
    public MpcParticipant withOnline(boolean online) {
        return new MpcParticipant(participantId, endpoint, publicKeyShareHex, online);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MpcParticipant)) return false;
        return participantId.equals(((MpcParticipant) o).participantId);
    }

    @Override
    public int hashCode() {
        return participantId.hashCode();
    }

    @Override
    public String toString() {
        return "MpcParticipant{id='" + participantId + "', endpoint='" + endpoint + "', online=" + online + "}";
    }
}