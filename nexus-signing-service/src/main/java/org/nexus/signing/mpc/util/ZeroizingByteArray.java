package org.nexus.signing.mpc.util;

import java.util.Arrays;
import java.util.Objects;

/**
 * 包装敏感字节数组的零化容器（MPC-P1-02 修复）。
 *
 * <p>审计发现 MPC-P1-02：DKG / Sign 结果（密钥分片、部分签名）以明文存储在
 * 内存中，内存 dump 可获取。本类包装 {@code byte[]}，实现 {@link AutoCloseable}，
 * 在 {@link #close()} 时用 {@link Arrays#fill(byte[], byte)} 将底层字节数组清零，
 * 减少敏感材料在内存中的驻留时间。</p>
 *
 * <h2>使用模式</h2>
 * <p>推荐使用 try-with-resources 确保及时清零：</p>
 * <pre>
 * try (ZeroizingByteArray sensitive = ZeroizingByteArray.wrap(rawBytes)) {
 *     // 使用 sensitive.getBytes() 处理敏感数据
 *     process(sensitive.getBytes());
 * } // close() 自动清零
 * // 此时 rawBytes 已被清零，sensitive.getBytes() 全为 0
 * </pre>
 *
 * <h2>清零语义</h2>
 * <ul>
 *   <li>{@link #close()}：立即将底层字节数组所有字节置为 {@code 0}，
 *       并标记为已关闭。多次调用安全（幂等）。</li>
 *   <li>{@link #finalize()}（兜底）：若调用方未显式 close，GC 回收时再次清零。
 *       <b>注意</b>：finalize 不保证及时执行，仅作为兜底防护，
 *       调用方应优先使用 try-with-resources。</li>
 *   <li>{@link #getBytes()}：返回底层字节数组引用（非拷贝），调用方修改会影响容器；
 *       已关闭后返回的数组全为 0。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>本类线程安全：{@link #close()} 与 {@link #getBytes()} 可被不同线程并发调用。
 * 内部用 {@code volatile} 标志位保证可见性。{@link Arrays#fill} 本身是原子操作
 * （对引用数组的写操作）。</p>
 *
 * <h2>不可变性</h2>
 * <p>本类是可变容器（close 后内容改变），但包装的数组引用不变（始终是同一个
 * {@code byte[]} 实例，只是内容被清零）。</p>
 *
 * <h2>安全注意事项</h2>
 * <ul>
 *   <li>调用方传入的 {@code byte[]} 不会被拷贝，本类直接持有引用。
 *       调用方不应在 close 前修改该数组（除非明确需要）。</li>
 *   <li>close 后，原数组引用的内容被清零，调用方若持有原引用也会观察到清零。</li>
 *   <li>本类不防止通过反射或内存 dump 在 close 前读取数据，
 *       仅减少 close 后的驻留时间。</li>
 * </ul>
 *
 * @author NexusChain MPC Security Team
 * @since 2.1.0
 */
public final class ZeroizingByteArray implements AutoCloseable {

    /** 底层字节数组（清零目标）。 */
    private final byte[] bytes;

    /** 是否已清零（close 后置 true，防止重复清零日志噪声）。 */
    private volatile boolean zeroized = false;

    /**
     * 包装给定字节数组（不拷贝，直接持有引用）。
     *
     * @param bytes 待包装的字节数组，非 null
     * @throws NullPointerException 若 bytes 为 null
     */
    private ZeroizingByteArray(byte[] bytes) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
    }

    /**
     * 包装给定字节数组（不拷贝，直接持有引用）。
     *
     * <p>调用方传入的数组不会被拷贝；本容器 close 时会清零该数组。
     * 若调用方需要保留原数组，应先传入拷贝：</p>
     * <pre>
     * ZeroizingByteArray.wrap(original.clone());
     * </pre>
     *
     * @param bytes 待包装的字节数组，非 null
     * @return 包装后的 {@link ZeroizingByteArray}
     * @throws NullPointerException 若 bytes 为 null
     */
    public static ZeroizingByteArray wrap(byte[] bytes) {
        return new ZeroizingByteArray(bytes);
    }

    /**
     * 将 hex 字符串解码为字节数组并包装。
     *
     * <p>解码后的字节数组由本容器持有，close 时清零。
     * 原 hex 字符串不受影响（字符串不可变，且解码产生新数组）。</p>
     *
     * @param hex hex 编码字符串（长度必须为偶数），非 null
     * @return 包装后的 {@link ZeroizingByteArray}
     * @throws NullPointerException     若 hex 为 null
     * @throws IllegalArgumentException 若 hex 长度为奇数或含非 hex 字符
     */
    public static ZeroizingByteArray fromHex(String hex) {
        Objects.requireNonNull(hex, "hex");
        byte[] decoded = hexToBytes(hex);
        return new ZeroizingByteArray(decoded);
    }

    /**
     * 返回底层字节数组引用（非拷贝）。
     *
     * <p>调用方修改返回的数组会影响本容器（同一引用）。
     * 已 close 后返回的数组全为 0。</p>
     *
     * @return 底层字节数组（已 close 后全为 0）
     */
    public byte[] getBytes() {
        return bytes;
    }

    /**
     * 返回数组长度。
     *
     * @return 数组长度（close 后不变，仅内容清零）
     */
    public int length() {
        return bytes.length;
    }

    /**
     * 是否已清零。
     *
     * @return {@code true} 若 {@link #close} 已被调用
     */
    public boolean isZeroized() {
        return zeroized;
    }

    /**
     * 清零底层字节数组（幂等）。
     *
     * <p>将所有字节置为 {@code 0}，标记为已清零。多次调用安全，
     * 但仅首次调用执行实际清零操作（后续调用因数组已全 0 而无需重复）。</p>
     *
     * <p>实现 {@link AutoCloseable#close()}，支持 try-with-resources。</p>
     */
    @Override
    public void close() {
        if (!zeroized) {
            Arrays.fill(bytes, (byte) 0);
            zeroized = true;
        }
    }

    /**
     * 兜底清零（GC 回收时调用）。
     *
     * <p><b>注意</b>：finalize 不保证及时执行，仅作为调用方未显式 close 的兜底防护。
     * 调用方应优先使用 try-with-resources 确保 {@link #close} 及时调用。</p>
     *
     * @deprecated 仅作为兜底，不要依赖 finalize 清零
     */
    @Override
    @Deprecated(since = "2.1.0", forRemoval = false)
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    /**
     * hex 字符串解码为字节数组。
     *
     * @param hex hex 字符串
     * @return 解码后的字节数组
     * @throws IllegalArgumentException 若长度为奇数或含非 hex 字符
     */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        if (len % 2 != 0) {
            throw new IllegalArgumentException(
                    "hex string length must be even, got " + len + ": " + hex);
        }
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            int high = hexCharToNibble(hex.charAt(i));
            int low = hexCharToNibble(hex.charAt(i + 1));
            out[i / 2] = (byte) ((high << 4) | low);
        }
        return out;
    }

    /**
     * 单个 hex 字符转 4-bit 值。
     *
     * @param c hex 字符
     * @return 0-15
     * @throws IllegalArgumentException 若非 hex 字符
     */
    private static int hexCharToNibble(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        } else if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        throw new IllegalArgumentException("invalid hex character: " + c);
    }
}