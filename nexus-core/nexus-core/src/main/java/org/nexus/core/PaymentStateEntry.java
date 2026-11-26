package org.nexus.core;

import org.nexus.encoding.BigEndian;
import org.nexus.util.ByteUtil;

import java.nio.charset.StandardCharsets;

/** Canonical payment substate entry committed by V3 (ADR-038). */
public final class PaymentStateEntry {

    public enum Kind {
        BATCH(1), CHANNEL(2), POSITION(3), BRIDGE(4), IDENTITY(5), SUBSCRIPTION(6), REPLAY_KEY(7);

        private final int code;

        Kind(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    private final Kind kind;
    private final String id;
    private final byte[] payload;

    public PaymentStateEntry(Kind kind, String id, byte[] payload) {
        if (kind == null || id == null || id.isEmpty() || payload == null) {
            throw new IllegalArgumentException("payment state kind, id, and payload are required");
        }
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > BigEndian.MAX_UINT_16) {
            throw new IllegalArgumentException("payment state id exceeds uint16 length");
        }
        if (payload.length > 10 * 1024 * 1024) {
            throw new IllegalArgumentException("payment state payload exceeds 10MB");
        }
        this.kind = kind;
        this.id = id;
        this.payload = payload.clone();
    }

    public Kind getKind() {
        return kind;
    }

    public String getId() {
        return id;
    }

    public byte[] getPayload() {
        return payload.clone();
    }

    public byte[] encode() {
        byte[] idBytes = id.getBytes(StandardCharsets.UTF_8);
        return ByteUtil.merge(
                new byte[]{0x03, (byte) kind.getCode()},
                BigEndian.encodeUint16(idBytes.length),
                idBytes,
                BigEndian.encodeUint32(payload.length),
                payload);
    }
}
