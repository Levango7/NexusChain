package org.nexus.l2.zk.groth16;

import org.bouncycastle.math.ec.ECPoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Groth16 证明实体：三个椭圆曲线点 (A, B, C)。
 *
 * <p>真实 Groth16 证明为 (A ∈ G1, B ∈ G2, C ∈ G1)；本简化版将 B 也放在 G1 上
 * （secp256k1 无 G2 群）。证明可序列化为字节数组供 {@link org.nexus.l2.zk.ZkProof} 包装。</p>
 *
 * @since 1.5
 */
public final class Groth16Proof {

    private final ECPoint a;
    private final ECPoint b;
    private final ECPoint c;
    private final String circuitId;

    public Groth16Proof(ECPoint a, ECPoint b, ECPoint c, String circuitId) {
        this.a = a;
        this.b = b;
        this.c = c;
        this.circuitId = circuitId;
    }

    public ECPoint getA() { return a; }
    public ECPoint getB() { return b; }
    public ECPoint getC() { return c; }
    public String getCircuitId() { return circuitId; }

    /**
     * 将证明编码为字节数组（用于 ZkProof 包装）。
     *
     * <p>格式：[magic(2)] [circuitIdLen(2)] [circuitId] [A] [B] [C]
     * 每个点编码为 [len(2)] [pointBytes]</p>
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
            // circuitId
            byte[] idBytes = circuitId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            bos.write(idBytes.length & 0xFF);
            bos.write((idBytes.length >> 8) & 0xFF);
            bos.write(idBytes);
            // points
            writePoint(bos, a);
            writePoint(bos, b);
            writePoint(bos, c);
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

    @Override
    public String toString() {
        return "Groth16Proof{circuitId='" + circuitId + "'"
                + ", aInf=" + (a == null || a.isInfinity())
                + ", bInf=" + (b == null || b.isInfinity())
                + ", cInf=" + (c == null || c.isInfinity()) + '}';
    }
}