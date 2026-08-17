package org.nexus.core.crypto.bls;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.bouncycastle.math.ec.ECPoint;

/**
 * 基于secp256k1曲线的BLS-like签名实现。
 *
 * <p>NOTE: 纯Java环境使用secp256k1 EC点实现BLS-like签名验证。
 * 生产环境应接入blst原生库做完整BLS12-381配对验签。</p>
 */
public class Secp256k1BlsSignature implements BlsSignature {
    /** 域分离因子，防止不同用途的哈希碰撞。 */
    private static final String DST = "NEXUS_BLS_V1";

    /** 聚合签名列表大小上限，防止 DoS 攻击。 */
    private static final int MAX_AGGREGATE_SIZE = 1024;

    /**
     * ThreadLocal 缓存的 SHA-256 MessageDigest，避免每次哈希都做 Provider 查找。
     *
     * <p>MessageDigest 非线程安全，故用 ThreadLocal 隔离；使用前需 {@code reset()}。</p>
     */
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    });

    private final ECPoint signaturePoint;

    public Secp256k1BlsSignature(ECPoint signaturePoint) {
        this.signaturePoint = signaturePoint.normalize();
    }

    @Override
    public byte[] toBytesCompressed() {
        return signaturePoint.getEncoded(true);
    }

    @Override
    public byte[] serialize() {
        return toBytesCompressed();
    }

    @Override
    public boolean verify(byte[] message, BlsPublicKey publicKey) {
        if (message == null || message.length == 0) {
            return false;
        }
        if (publicKey == null || !(publicKey instanceof Secp256k1BlsPublicKey)) {
            return false;
        }
        // 验证签名是曲线上的有效点 + 非无穷远点
        if (signaturePoint.isInfinity()) {
            return false;
        }
        // BLS-like验证：检查 e(G, σ) == e(pk, H(m))
        // 在secp256k1下简化为：σ == pk * H(m)
        try {
            BigInteger h = hashToScalar(message);
            ECPoint expected = ((Secp256k1BlsPublicKey) publicKey).multiply(h);
            return signaturePoint.equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 聚合多个签名（EC点加法）。
     *
     * <p>NOTE: 此方法不包含 rogue-key attack 防护，仅适用于所有签名方可信的场景。
     * 对于不可信/公开聚合场景，应使用 {@link #aggregateWithCoefficients}。</p>
     */
    public static Secp256k1BlsSignature aggregate(java.util.List<Secp256k1BlsSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate empty signature list");
        }
        if (signatures.size() > MAX_AGGREGATE_SIZE) {
            throw new IllegalArgumentException("Signature list too large (max " + MAX_AGGREGATE_SIZE + ")");
        }
        ECPoint aggregated = signatures.get(0).signaturePoint;
        for (int i = 1; i < signatures.size(); i++) {
            aggregated = aggregated.add(signatures.get(i).signaturePoint);
        }
        return new Secp256k1BlsSignature(aggregated.normalize());
    }

    /**
     * 带rogue-key防护的聚合签名。
     *
     * <p>每个签名用系数 {@code coeff_i = hash(pk_i || all_pks_sorted)} 加权，
     * 实现 coefficients-based aggregation（基于 proof-of-possession 思想），
     * 防止恶意验证者构造 {@code pk' = pk - sk'*G} 后用 sk' 单独签名伪造聚合签名。</p>
     *
     * <p>聚合签名 = Σ coeff_i * σ_i；验签时聚合公钥 = Σ coeff_i * pk_i。</p>
     *
     * @param signatures 待聚合的签名列表
     * @param publicKeys 对应公钥列表，长度必须与 signatures 一致
     * @return 加权聚合后的签名
     * @throws IllegalArgumentException 若入参为空或长度不一致
     */
    public static Secp256k1BlsSignature aggregateWithCoefficients(
            List<Secp256k1BlsSignature> signatures,
            List<Secp256k1BlsPublicKey> publicKeys) {
        if (signatures == null || signatures.isEmpty()
                || publicKeys == null || publicKeys.size() != signatures.size()) {
            throw new IllegalArgumentException(
                    "Signatures and public keys must be non-empty and same size");
        }
        if (signatures.size() > MAX_AGGREGATE_SIZE) {
            throw new IllegalArgumentException("Signature list too large (max " + MAX_AGGREGATE_SIZE + ")");
        }
        // 排序所有公钥的字节表示，确保系数计算确定性
        List<byte[]> sortedPkBytes = publicKeys.stream()
                .map(Secp256k1BlsPublicKey::toBytesCompressed)
                .sorted(Comparator.comparingInt(Arrays::hashCode))
                .collect(Collectors.toList());

        ECPoint aggregated = null;
        for (int i = 0; i < signatures.size(); i++) {
            BigInteger coeff = computeCoefficient(
                    publicKeys.get(i).toBytesCompressed(), sortedPkBytes);
            ECPoint weightedSig = signatures.get(i).signaturePoint.multiply(coeff);
            aggregated = (aggregated == null) ? weightedSig : aggregated.add(weightedSig);
        }
        return new Secp256k1BlsSignature(aggregated.normalize());
    }

    /**
     * 计算 rogue-key 防护系数：{@code hash(pk_i || all_pks_sorted)}。
     */
    private static BigInteger computeCoefficient(byte[] pkBytes, List<byte[]> sortedPkBytes) {
        MessageDigest digest = SHA256_DIGEST.get();
        digest.reset();
        digest.update(pkBytes);
        for (byte[] pk : sortedPkBytes) {
            digest.update(pk);
        }
        return new BigInteger(1, digest.digest()).mod(Secp256k1BlsSigner.getN());
    }

    private static BigInteger hashToScalar(byte[] message) {
        MessageDigest digest = SHA256_DIGEST.get();
        digest.reset();
        // 域分离因子前缀，防止不同用途的哈希碰撞
        digest.update(DST.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0); // DST 与 message 之间的分隔符
        byte[] hash = digest.digest(message);
        return new BigInteger(1, hash).mod(Secp256k1BlsSigner.getN());
    }
}