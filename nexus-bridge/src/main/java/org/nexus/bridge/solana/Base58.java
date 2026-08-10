package org.nexus.bridge.solana;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Base58 编码/解码工具。
 *
 * <p>Solana 的账户公钥、交易哈希、签名等均采用 Base58 编码（Bitcoin 风格字母表），
 * 与 EVM 链的 hex 编码不同，因此需要独立的 Base58 实现。</p>
 *
 * <h2>字母表</h2>
 * <pre>
 *   123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz
 * </pre>
 * <p>共 58 个字符，剔除易混淆的 0、O、I、l。</p>
 *
 * <h2>实现说明</h2>
 * <p>采用 {@link BigInteger} 进行大数运算，确保编解码正确性。
 * 性能足以满足桥服务场景（单次编解码 32-64 字节）。</p>
 *
 * <h2>典型用途</h2>
 * <ul>
 *   <li>解码 Solana RPC 返回的 base58 字符串为字节（用于签名校验）</li>
 *   <li>将字节编码为 base58 字符串（用于构造 Solana 交易哈希展示）</li>
 * </ul>
 *
 * @since 2.0.0
 */
public final class Base58 {

    /** Base58 字母表（Bitcoin 风格）。 */
    private static final char[] ALPHABET =
            "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    /** 字符 → 索引映射表，未出现的字符以 -1 占位。 */
    private static final int[] INDEX_MAP = new int[128];

    /** Base58 进制基数。 */
    private static final BigInteger BASE = BigInteger.valueOf(58L);

    static {
        Arrays.fill(INDEX_MAP, -1);
        for (int i = 0; i < ALPHABET.length; i++) {
            INDEX_MAP[ALPHABET[i]] = i;
        }
    }

    private Base58() {
    }

    /**
     * 将字节数组编码为 Base58 字符串。
     *
     * @param input 待编码字节，允许为空数组
     * @return Base58 字符串；输入为 null 时返回 null
     */
    public static String encode(byte[] input) {
        if (input == null) {
            return null;
        }
        if (input.length == 0) {
            return "";
        }
        // 统计前导零字节
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        // 使用 BigInteger 编码（正数）
        BigInteger num = new BigInteger(1, input);
        StringBuilder sb = new StringBuilder();
        while (num.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divmod = num.divideAndRemainder(BASE);
            num = divmod[0];
            sb.insert(0, ALPHABET[divmod[1].intValue()]);
        }
        // 添加前导 '1'（每个前导零字节对应一个）
        for (int i = 0; i < zeros; i++) {
            sb.insert(0, ALPHABET[0]);
        }
        return sb.toString();
    }

    /**
     * 将 Base58 字符串解码为字节数组。
     *
     * @param input Base58 字符串
     * @return 解码后的字节；输入为 null 时返回 null
     * @throws IllegalArgumentException 如果包含非 Base58 字符
     */
    public static byte[] decode(String input) {
        if (input == null) {
            return null;
        }
        if (input.isEmpty()) {
            return new byte[0];
        }
        // 统计前导 '1'（每个对应一个零字节）
        int zeros = 0;
        while (zeros < input.length() && input.charAt(zeros) == ALPHABET[0]) {
            zeros++;
        }
        // 使用 BigInteger 累加
        BigInteger num = BigInteger.ZERO;
        for (int i = zeros; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch >= 128 || INDEX_MAP[ch] == -1) {
                throw new IllegalArgumentException("Invalid Base58 character: " + ch);
            }
            num = num.multiply(BASE).add(BigInteger.valueOf(INDEX_MAP[ch]));
        }
        // 转换为字节数组
        byte[] numBytes;
        if (num.equals(BigInteger.ZERO)) {
            numBytes = new byte[0];
        } else {
            numBytes = num.toByteArray();
            // BigInteger.toByteArray() 对正数可能产生前导零字节（符号位），需要去掉
            if (numBytes.length > 1 && numBytes[0] == 0) {
                byte[] stripped = new byte[numBytes.length - 1];
                System.arraycopy(numBytes, 1, stripped, 0, stripped.length);
                numBytes = stripped;
            }
        }
        // 拼装结果：前导零字节 + 数值字节
        byte[] result = new byte[zeros + numBytes.length];
        System.arraycopy(numBytes, 0, result, zeros, numBytes.length);
        return result;
    }
}
