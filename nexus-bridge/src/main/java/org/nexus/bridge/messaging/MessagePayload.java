package org.nexus.bridge.messaging;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 跨链消息负载。
 *
 * <p>封装消息携带的具体数据内容，按业务类型分为三类：</p>
 * <ul>
 *   <li>{@link Type#TOKEN_TRANSFER} — 代币跨链转移（data 包含金额、接收地址等）</li>
 *   <li>{@link Type#CONTRACT_CALL}  — 远程合约调用（data 包含 calldata）</li>
 *   <li>{@link Type#ARBITRARY}      — 任意自定义数据（data 为业务方自定义字节）</li>
 * </ul>
 *
 * <p>{@code encodedData} 为 {@code data} 的 UTF-8 十六进制编码，便于跨链传输与签名。
 * 若构造时未提供，则由 {@link #encode()} 自动计算并缓存。</p>
 *
 * @since 1.9.2
 */
public class MessagePayload {

    /** 负载类型枚举。 */
    public enum Type {
        /** 代币跨链转移。 */
        TOKEN_TRANSFER,
        /** 远程合约调用。 */
        CONTRACT_CALL,
        /** 任意自定义数据。 */
        ARBITRARY
    }

    /** 负载类型。 */
    private final Type type;

    /** 原始数据（UTF-8 字符串形式，可为 JSON / hex / 业务文本）。 */
    private final String data;

    /** 编码后的数据（UTF-8 → hex），延迟初始化。 */
    private String encodedData;

    /**
     * 构造消息负载。
     *
     * @param type 负载类型
     * @param data 原始数据
     */
    public MessagePayload(Type type, String data) {
        if (type == null) {
            throw new IllegalArgumentException("Payload type must not be null");
        }
        if (data == null) {
            throw new IllegalArgumentException("Payload data must not be null");
        }
        this.type = type;
        this.data = data;
        this.encodedData = null;
    }

    /**
     * 构造消息负载（带预编码数据，用于反序列化场景）。
     *
     * @param type        负载类型
     * @param data        原始数据
     * @param encodedData 已编码数据（hex）
     */
    public MessagePayload(Type type, String data, String encodedData) {
        if (type == null) {
            throw new IllegalArgumentException("Payload type must not be null");
        }
        this.type = type;
        this.data = data == null ? "" : data;
        this.encodedData = encodedData;
    }

    /**
     * 获取负载类型。
     *
     * @return 负载类型
     */
    public Type getType() {
        return type;
    }

    /**
     * 获取原始数据。
     *
     * @return 原始数据字符串
     */
    public String getData() {
        return data;
    }

    /**
     * 获取编码后数据（UTF-8 → hex）。
     *
     * <p>若未预置，则按需计算并缓存。</p>
     *
     * @return hex 编码字符串
     */
    public String getEncodedData() {
        if (encodedData == null) {
            encodedData = HexFormat.of().formatHex(data.getBytes(StandardCharsets.UTF_8));
        }
        return encodedData;
    }

    /**
     * 显式触发编码计算（幂等）。
     *
     * @return 当前实例，便于链式调用
     */
    public MessagePayload encode() {
        getEncodedData();
        return this;
    }

    /**
     * 计算负载字节长度（按原始数据 UTF-8 字节数）。
     *
     * @return 字节长度
     */
    public int byteLength() {
        return data.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessagePayload that)) return false;
        return type == that.type && Objects.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, data);
    }

    @Override
    public String toString() {
        return "MessagePayload{type=" + type + ", dataLen=" + byteLength() + "}";
    }
}