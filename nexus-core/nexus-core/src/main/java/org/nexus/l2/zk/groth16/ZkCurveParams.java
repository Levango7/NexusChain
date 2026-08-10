package org.nexus.l2.zk.groth16;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * ZK 椭圆曲线参数与运算工具。
 *
 * <p>基于 BouncyCastle 的 secp256k1 曲线提供 ZK 证明系统所需的椭圆曲线运算。
 * secp256k1 是 Bitcoin/Ethereum 使用的曲线，BouncyCastle 完整支持其点运算。</p>
 *
 * <h3>设计说明</h3>
 * <ul>
 *   <li>使用 secp256k1 而非 BN128：secp256k1 在 BouncyCastle 中 API 更稳定，
 *       且本实现为 Groth16 简化版（不依赖双线性配对），secp256k1 足够</li>
 *   <li>所有标量运算在 {@code F_n}（曲线阶的素域）上进行</li>
 *   <li>提供确定性随机源（基于 SecureRandom）用于 setup</li>
 * </ul>
 *
 * @since 1.5
 */
public final class ZkCurveParams {

    /** secp256k1 曲线参数 */
    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1");

    /** 曲线阶 n（标量域大小） */
    public static final BigInteger CURVE_ORDER = CURVE_PARAMS.getN();

    /** 素数域 p（坐标域大小） */
    public static final BigInteger FIELD_PRIME = CURVE_PARAMS.getCurve().getField().getCharacteristic();

    /** 生成元 G */
    public static final ECPoint GENERATOR = CURVE_PARAMS.getG().normalize();

    /** 曲线 cofactor（secp256k1 为 1） */
    public static final BigInteger COFACTOR = CURVE_PARAMS.getH();

    private ZkCurveParams() {
    }

    /**
     * 标量乘法：返回 {@code scalar * G}。
     *
     * @param scalar 标量（mod n）
     * @return 椭圆曲线点
     */
    public static ECPoint scalarBaseMultiply(BigInteger scalar) {
        if (scalar == null) {
            return GENERATOR.getCurve().getInfinity();
        }
        return GENERATOR.multiply(scalar.mod(CURVE_ORDER)).normalize();
    }

    /**
     * 标量乘法：返回 {@code scalar * point}。
     *
     * @param point  椭圆曲线点
     * @param scalar 标量
     * @return 椭圆曲线点
     */
    public static ECPoint scalarMultiply(ECPoint point, BigInteger scalar) {
        if (point == null || scalar == null) {
            return GENERATOR.getCurve().getInfinity();
        }
        return point.multiply(scalar.mod(CURVE_ORDER)).normalize();
    }

    /**
     * 多标量乘法：返回 {@code Σ scalar_i * point_i}。
     *
     * @param points  椭圆曲线点数组
     * @param scalars 标量数组（长度需与 points 相同）
     * @return 椭圆曲线点
     */
    public static ECPoint multiScalarMultiply(ECPoint[] points, BigInteger[] scalars) {
        if (points == null || scalars == null || points.length != scalars.length) {
            throw new IllegalArgumentException("points/scalars length mismatch");
        }
        ECPoint result = GENERATOR.getCurve().getInfinity();
        for (int i = 0; i < points.length; i++) {
            if (points[i] != null && scalars[i] != null) {
                result = result.add(points[i].multiply(scalars[i].mod(CURVE_ORDER)));
            }
        }
        return result.normalize();
    }

    /**
     * 点加法。
     *
     * @param p1 点 1
     * @param p2 点 2
     * @return p1 + p2
     */
    public static ECPoint add(ECPoint p1, ECPoint p2) {
        if (p1 == null) return p2 == null ? infinity() : p2;
        if (p2 == null) return p1;
        return p1.add(p2).normalize();
    }

    /**
     * 返回无穷远点（单位元）。
     *
     * @return 无穷远点
     */
    public static ECPoint infinity() {
        return GENERATOR.getCurve().getInfinity();
    }

    /**
     * 判断点是否为无穷远点。
     *
     * @param point 椭圆曲线点
     * @return 是无穷远点返回 true
     */
    public static boolean isInfinity(ECPoint point) {
        return point == null || point.isInfinity();
    }

    /**
     * 在标量域 {@code F_n} 中取模。
     *
     * @param value 输入值
     * @return value mod n
     */
    public static BigInteger mod(BigInteger value) {
        if (value == null) return BigInteger.ZERO;
        return value.mod(CURVE_ORDER);
    }

    /**
     * 在标量域 {@code F_n} 中求逆。
     *
     * @param value 输入值
     * @return value^(-1) mod n
     */
    public static BigInteger modInverse(BigInteger value) {
        if (value == null || value.equals(BigInteger.ZERO)) {
            throw new ArithmeticException("cannot invert zero in F_n");
        }
        return value.mod(CURVE_ORDER).modInverse(CURVE_ORDER);
    }

    /**
     * 生成标量域中的随机非零元素。
     *
     * @param random 随机源
     * @return 随机标量
     */
    public static BigInteger randomScalar(SecureRandom random) {
        BigInteger r;
        int bits = CURVE_ORDER.bitLength();
        do {
            r = new BigInteger(bits, random);
        } while (r.equals(BigInteger.ZERO) || r.compareTo(CURVE_ORDER) >= 0);
        return r;
    }

    /**
     * 将椭圆曲线点编码为字节数组（压缩格式）。
     *
     * @param point 椭圆曲线点
     * @return 编码字节
     */
    public static byte[] encodePoint(ECPoint point) {
        if (point == null || point.isInfinity()) {
            return new byte[]{0};
        }
        return point.getEncoded(true);
    }

    /**
     * 将字节数组解码为椭圆曲线点。
     *
     * @param encoded 编码字节
     * @return 椭圆曲线点
     */
    public static ECPoint decodePoint(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded[0] == 0) {
            return infinity();
        }
        return CURVE_PARAMS.getCurve().decodePoint(encoded).normalize();
    }

    /**
     * 返回曲线名称。
     *
     * @return "secp256k1"
     */
    public static String getCurveName() {
        return "secp256k1";
    }
}