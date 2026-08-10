package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Groth16 证明实体：三个椭圆曲线点 (A, B, C) + R1CS 满足性证明。
 *
 * <p>真实 Groth16 证明为 (A ∈ G1, B ∈ G2, C ∈ G1)；本简化版将 B 也放在 G1 上
 * （secp256k1 无 G2 群）。证明可序列化为字节数组供 {@link org.nexus.l2.zk.ZkProof} 包装。</p>
 *
 * <h3>ZK-P0-01 修复（2.1.1）</h3>
 * <p>自 2.1.1 起证明附加 {@link R1csSatisfactionProof} 字段，包含每条 R1CS 约束的
 * 满足性证据（Schnorr 知识证明）。verifier 验证每条约束的 {@code aVal * bVal == cVal}
 * 及对应 Schnorr 证明，防止恶意 prover 构造通过 Schnorr 但不满足 R1CS 的证明。</p>
 *
 * <h3>序列化格式</h3>
 * <p>格式（v2 含 R1CS 证明）：</p>
 * <pre>
 * [magic(3)="G16"] [version(1)=0x02] [circuitIdLen(2)] [circuitId] [A] [B] [C]
 * [r1csProofFlag(1)] [r1csProof(若 flag=1)]
 * </pre>
 * <p>格式（v1 兼容，无 R1CS 证明）：</p>
 * <pre>
 * [magic(3)="G16"] [version(1)=0x01] [circuitIdLen(2)] [circuitId] [A] [B] [C]
 * </pre>
 * <p>每个点编码为 [len(2)] [pointBytes]</p>
 *
 * @since 1.5
 */
public final class Groth16Proof {

    /** 序列化版本 v1（无 R1CS 证明，向后兼容） */
    private static final byte VERSION_V1 = 0x01;
    /** 序列化版本 v2（含 R1CS 满足性证明） */
    private static final byte VERSION_V2 = 0x02;

    private final ECPoint a;
    private final ECPoint b;
    private final ECPoint c;
    private final String circuitId;
    /** R1CS 满足性证明（ZK-P0-01 修复）；null 表示旧格式证明 */
    private final R1csSatisfactionProof r1csSatisfactionProof;

    public Groth16Proof(ECPoint a, ECPoint b, ECPoint c, String circuitId) {
        this(a, b, c, circuitId, null);
    }

    public Groth16Proof(ECPoint a, ECPoint b, ECPoint c, String circuitId,
                        R1csSatisfactionProof r1csSatisfactionProof) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.circuitId = circuitId;
        this.r1csSatisfactionProof = r1csSatisfactionProof;
    }

    public ECPoint getA() { return a; }
    public ECPoint getB() { return b; }
    public ECPoint getC() { return c; }
    public String getCircuitId() { return circuitId; }

    /**
     * 获取 R1CS 满足性证明。
     *
     * @return R1CS 满足性证明；旧格式证明返回 null
     * @since 2.1.1
     */
    public R1csSatisfactionProof getR1csSatisfactionProof() {
        return r1csSatisfactionProof;
    }

    /**
     * 判断是否包含 R1CS 满足性证明。
     *
     * @return 包含返回 true
     * @since 2.1.1
     */
    public boolean hasR1csSatisfactionProof() {
        return r1csSatisfactionProof != null;
    }

    /**
     * 将证明编码为字节数组（用于 ZkProof 包装）。
     *
     * <p>含 R1CS 满足性证明时使用 v2 格式，否则使用 v1 格式（向后兼容）。</p>
     *
     * @return 编码字节
     */
    public byte[] encode() {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            // magic "G16"
            bos.write('G');
            bos.write('1');
            bos.write('6');
            // version
            byte version = (r1csSatisfactionProof != null) ? VERSION_V2 : VERSION_V1;
            bos.write(version);
            // circuitId
            byte[] idBytes = circuitId.getBytes(StandardCharsets.UTF_8);
            bos.write(idBytes.length & 0xFF);
            bos.write((idBytes.length >> 8) & 0xFF);
            bos.write(idBytes);
            // points
            writePoint(bos, a);
            writePoint(bos, b);
            writePoint(bos, c);
            // R1CS 满足性证明（v2）
            if (r1csSatisfactionProof != null) {
                writeR1csProof(bos, r1csSatisfactionProof);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private static void writePoint(ByteArrayOutputStream bos, ECPoint p) throws IOException {
        byte[] enc = ZkCurveParams.encodePoint(p);
        bos.write(enc.length & 0xFF);
        bos.write((enc.length >> 8) & 0xFF);
        bos.write(enc);
    }

    private static void writeR1csProof(ByteArrayOutputStream bos,
                                       R1csSatisfactionProof proof) throws IOException {
        R1csSatisfactionProof.ConstraintProof[] proofs = proof.getConstraintProofs();
        int count = proofs.length;
        bos.write(count & 0xFF);
        bos.write((count >> 8) & 0xFF);
        for (R1csSatisfactionProof.ConstraintProof cp : proofs) {
            // aVal, bVal, cVal (8 bytes each, little-endian)
            bos.write(longToBytesLE(cp.getAVal()));
            bos.write(longToBytesLE(cp.getBVal()));
            bos.write(longToBytesLE(cp.getCVal()));
            // commitment, tPoint
            writePoint(bos, cp.getCommitment());
            writePoint(bos, cp.getTPoint());
            // zA, zB, zC, zR (BigInteger as [len(4)][bytes])
            writeBigInteger(bos, cp.getZA());
            writeBigInteger(bos, cp.getZB());
            writeBigInteger(bos, cp.getZC());
            writeBigInteger(bos, cp.getZR());
        }
    }

    private static byte[] longToBytesLE(long v) {
        return ByteBuffer.allocate(8).putLong(v).array();
    }

    private static void writeBigInteger(ByteArrayOutputStream bos, BigInteger bi) throws IOException {
        byte[] bytes = bi == null ? new byte[0] : bi.toByteArray();
        int len = bytes.length;
        bos.write(len & 0xFF);
        bos.write((len >> 8) & 0xFF);
        bos.write((len >> 16) & 0xFF);
        bos.write((len >> 24) & 0xFF);
        bos.write(bytes);
    }

    // ==================== 解码 ====================

    /**
     * 从字节数组解码证明。
     *
     * <p>支持 v1（无 R1CS 证明）和 v2（含 R1CS 证明）格式。
     * v1 格式解码后的证明 {@link #hasR1csSatisfactionProof()} 返回 false。</p>
     *
     * @param bytes 编码字节（含 magic "G16"）
     * @return 解码后的证明
     * @throws IllegalArgumentException 格式错误
     * @since 2.1.1
     */
    public static Groth16Proof decode(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new IllegalArgumentException("proof bytes too short");
        }
        // 检查 magic "G16"
        if (bytes[0] != 'G' || bytes[1] != '1' || bytes[2] != '6') {
            throw new IllegalArgumentException("invalid magic, expected 'G16'");
        }
        int pos = 3;
        byte version = bytes[pos];
        pos += 1;
        if (version != VERSION_V1 && version != VERSION_V2) {
            throw new IllegalArgumentException("unsupported version: " + version);
        }

        // circuitId
        int idLen = readUShortLE(bytes, pos);
        pos += 2;
        String circuitId = new String(bytes, pos, idLen, StandardCharsets.UTF_8);
        pos += idLen;

        // 读取三个点 A, B, C
        ECPoint aPoint = readPoint(bytes, pos);
        pos += 2 + readUShortLE(bytes, pos);

        ECPoint bPoint = readPoint(bytes, pos);
        pos += 2 + readUShortLE(bytes, pos);

        ECPoint cPoint = readPoint(bytes, pos);
        pos += 2 + readUShortLE(bytes, pos);

        // v2: 读取 R1CS 满足性证明
        R1csSatisfactionProof r1csProof = null;
        if (version == VERSION_V2 && pos < bytes.length) {
            r1csProof = readR1csProof(bytes, pos);
        }

        return new Groth16Proof(aPoint, bPoint, cPoint, circuitId, r1csProof);
    }

    private static R1csSatisfactionProof readR1csProof(byte[] bytes, int pos) {
        int count = readUShortLE(bytes, pos);
        pos += 2;
        R1csSatisfactionProof.ConstraintProof[] proofs =
                new R1csSatisfactionProof.ConstraintProof[count];
        for (int i = 0; i < count; i++) {
            long aVal = readLongLE(bytes, pos);
            pos += 8;
            long bVal = readLongLE(bytes, pos);
            pos += 8;
            long cVal = readLongLE(bytes, pos);
            pos += 8;

            ECPoint commitment = readPoint(bytes, pos);
            pos += 2 + readUShortLE(bytes, pos);

            ECPoint tPoint = readPoint(bytes, pos);
            pos += 2 + readUShortLE(bytes, pos);

            BigInteger zA = readBigInteger(bytes, pos);
            pos += 4 + readUIntLE(bytes, pos);

            BigInteger zB = readBigInteger(bytes, pos);
            pos += 4 + readUIntLE(bytes, pos);

            BigInteger zC = readBigInteger(bytes, pos);
            pos += 4 + readUIntLE(bytes, pos);

            BigInteger zR = readBigInteger(bytes, pos);
            pos += 4 + readUIntLE(bytes, pos);

            proofs[i] = new R1csSatisfactionProof.ConstraintProof(
                    aVal, bVal, cVal, commitment, tPoint, zA, zB, zC, zR);
        }
        return new R1csSatisfactionProof(proofs);
    }

    private static ECPoint readPoint(byte[] bytes, int pos) {
        int len = readUShortLE(bytes, pos);
        byte[] pointBytes = java.util.Arrays.copyOfRange(bytes, pos + 2, pos + 2 + len);
        return ZkCurveParams.decodePoint(pointBytes);
    }

    private static int readUShortLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readUIntLE(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static long readLongLE(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, 8).getLong();
    }

    private static BigInteger readBigInteger(byte[] bytes, int pos) {
        int len = readUIntLE(bytes, pos);
        if (len == 0) {
            return BigInteger.ZERO;
        }
        byte[] biBytes = java.util.Arrays.copyOfRange(bytes, pos + 4, pos + 4 + len);
        return new BigInteger(1, biBytes);
    }

    @Override
    public String toString() {
        return "Groth16Proof{circuitId='" + circuitId + "'"
                + ", aInf=" + (a == null || a.isInfinity())
                + ", bInf=" + (b == null || b.isInfinity())
                + ", cInf=" + (c == null || c.isInfinity())
                + ", hasR1csProof=" + hasR1csSatisfactionProof() + '}';
    }
}
