package org.nexus.util;

import java.math.BigInteger;
import java.util.Arrays;

// big-endian encoding utils
public class BigEndian {
    private static final BigInteger shadow;

    static {
        byte[] shadowBits = new byte[32];
        shadowBits[0] = (byte) 0xff;
        shadow = new BigInteger(1, shadowBits);
    }


    public static int decodeInt32(byte[] data) {
        return new BigInteger(1, data).intValue();
    }

    // big-endian encoding
    public static byte[] encodeInt32(int val) {
        byte[] res = new byte[4];
        res[0] = (byte) ((val & 0x00000000FF000000L) >>> 24);
        res[1] = (byte) ((val & 0x0000000000FF0000L) >>> 16);
        res[2] = (byte) ((val & 0x000000000000FF00L) >>> 8);
        res[3] = (byte) (val & 0x00000000000000FFL);
        return res;
    }

    // big-endian encoding
    public static byte[] encodeInt64(long value) {
        byte[] res = new byte[8];
        res[0] = (byte) ((value & 0xFF00000000000000L) >>> 56);
        res[1] = (byte) ((value & 0x00FF000000000000L) >>> 48);
        res[2] = (byte) ((value & 0x0000FF0000000000L) >>> 40);
        res[3] = (byte) ((value & 0x000000FF00000000L) >>> 32);
        res[4] = (byte) ((value & 0x00000000FF000000L) >>> 24);
        res[5] = (byte) ((value & 0x0000000000FF0000L) >>> 16);
        res[6] = (byte) ((value & 0x000000000000FF00L) >>> 8);
        res[7] = (byte) (value & 0x00000000000000FFL);
        return res;
    }

    public static long decodeInt64(byte[] data) {
        return new BigInteger(data).longValue();
    }

    public static int compareUint256(byte[] a, byte[] b) {
        return new BigInteger(1, a).compareTo(
                new BigInteger(1, b)
        );
    }

    public static int decodeUint16(byte[] in) {
        return new BigInteger(1, in).intValue();
    }

    public static byte[] encodeUint16(int value) {
        byte[] res = new byte[2];
        res[0] = (byte) ((value & 0x0000ff00) >>> 8);
        res[1] = (byte) (value & 0x000000ff);
        return res;
    }

    public static byte[] encodeUint256(BigInteger in) {
        if (in.signum() < 0) {
            return null;
        }
        if (in.signum() == 0) {
            return new byte[32];
        }
        byte[] res = new byte[32];
        for (int i = 0; i < res.length; i++) {
            BigInteger tmp = in.and(shadow.shiftRight(i * 8)).shiftRight((res.length - i - 1) * 8);
            res[i] = tmp.byteValue();
        }
        return res;
    }

    public static BigInteger decodeUint256(byte[] in) {
        return new BigInteger(1, Arrays.copyOfRange(in, 0, 32));
    }

}
