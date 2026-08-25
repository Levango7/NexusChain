package org.nexus.core.payment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * 跨链桥交易 payload 编解码工具（v2.3.0 生命周期键统一）。
 *
 * <p>为使 BRIDGE_LOCK → BRIDGE_MINT → BRIDGE_BURN 三阶段共享同一条
 * {@link BridgeTransaction} 记录，BRIDGE_MINT 与 BRIDGE_BURN 的 payload
 * 在既有基础格式之后追加可选尾部 {@code [2B idLen][bridgeTxId UTF-8]}，
 * 显式携带生命周期统一 ID（即 {@link BridgeLifecycleReplayGuard#computeLockKey}
 * 派生的语义键）：</p>
 *
 * <ul>
 *   <li><b>BRIDGE_MINT</b>：{@code [8B timelock][1B sigCount][32B messageHash]
 *       [N×(32B pubkey+64B sig)][2B idLen][bridgeTxId]}</li>
 *   <li><b>BRIDGE_BURN</b>：{@code [8B timestamp][1B flags][2B idLen][bridgeTxId]}</li>
 * </ul>
 *
 * <p>不带尾部的旧格式 payload 保持兼容；带尾部但格式残缺的 payload 由
 * 验证层 fail-closed 拒绝。</p>
 */
public final class BridgePayloadCodec {

    /** 尾部长度前缀字节数（2 字节大端）。 */
    public static final int TRAILER_OVERHEAD = 2;

    private BridgePayloadCodec() {
        // 工具类禁止实例化
    }

    /**
     * 在基础 payload 之后追加 bridgeTxId 尾部。
     *
     * @param basePayload 基础 payload（不含尾部）
     * @param bridgeTxId  生命周期统一桥交易 ID（非空）
     * @return 追加尾部后的完整 payload
     */
    public static byte[] appendIdTrailer(byte[] basePayload, String bridgeTxId) {
        if (basePayload == null) {
            throw new IllegalArgumentException("basePayload must not be null");
        }
        if (bridgeTxId == null || bridgeTxId.isEmpty()) {
            throw new IllegalArgumentException("bridgeTxId must not be empty");
        }
        byte[] idBytes = bridgeTxId.getBytes(StandardCharsets.UTF_8);
        if (idBytes.length > 0xFFFF) {
            throw new IllegalArgumentException("bridgeTxId too long: " + idBytes.length);
        }
        ByteBuffer buf = ByteBuffer.allocate(basePayload.length + TRAILER_OVERHEAD + idBytes.length);
        buf.put(basePayload);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        return buf.array();
    }

    /**
     * 构造 BRIDGE_BURN payload（含 bridgeTxId 尾部）。
     *
     * @param timestamp  时间戳（秒）
     * @param flags      标志位（当前固定 0）
     * @param bridgeTxId 生命周期统一桥交易 ID（非空）
     * @return 完整 burn payload
     */
    public static byte[] buildBurnPayload(long timestamp, int flags, String bridgeTxId) {
        byte[] idBytes = bridgeTxId.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + 1 + TRAILER_OVERHEAD + idBytes.length);
        buf.putLong(timestamp);
        buf.put((byte) flags);
        buf.putShort((short) idBytes.length);
        buf.put(idBytes);
        return buf.array();
    }

    /**
     * 从 payload 中提取 bridgeTxId 尾部（若存在且格式完整）。
     *
     * <p>严格解析：若 payload 超出 {@code baseLength} 但剩余字节不足以构成
     * 完整的 {@code [2B idLen][id]}，视为格式损坏并返回 {@code null}
     * （由调用方决定回退或拒绝）。idLen 为 0 同样返回 {@code null}。</p>
     *
     * @param payload    完整 payload
     * @param baseLength 基础格式长度（不含尾部）
     * @return 提取到的 bridgeTxId；无尾部或格式不完整时返回 {@code null}
     */
    public static String extractIdTrailer(byte[] payload, int baseLength) {
        if (payload == null || payload.length <= baseLength) {
            return null;
        }
        int remaining = payload.length - baseLength;
        if (remaining < TRAILER_OVERHEAD) {
            return null;
        }
        int idLen = ((payload[baseLength] & 0xFF) << 8) | (payload[baseLength + 1] & 0xFF);
        if (idLen == 0 || remaining != TRAILER_OVERHEAD + idLen) {
            return null;
        }
        return new String(payload, baseLength + TRAILER_OVERHEAD, idLen,
                StandardCharsets.UTF_8);
    }
}
