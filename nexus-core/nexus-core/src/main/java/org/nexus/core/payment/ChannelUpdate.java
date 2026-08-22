package org.nexus.core.payment;

import org.nexus.keystore.util.JsonUtils;
import org.apache.commons.codec.binary.Hex;
import org.nexus.crypto.HashUtil;
import org.nexus.crypto.ed25519.Ed25519PublicKey;
import org.nexus.encoding.BigEndian;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 链下支付通道状态更新消息。
 *
 * <p>通道双方通过交换签名的方式更新通道余额，无需上链。
 * 只有最终状态或争议时才需要上链。每次更新包含递增的 nonce、
 * 双方的新余额以及双方的 Ed25519 签名。签名覆盖的消息内容为
 * channelId + nonce + balance1 + balance2，经 Keccak-256 哈希后签名。</p>
 *
 * <p>典型的链下更新流程：
 * <ol>
 *   <li>参与方1 计算新余额并用自己的私钥签名，构造 {@link ChannelUpdate}</li>
 *   <li>参与方1 将 update 发送给参与方2</li>
 *   <li>参与方2 验证参与方1 的签名和余额守恒后用自己的私钥签名</li>
 *   <li>双方都签名的 update 即为已确认的链下状态</li>
 * </ol></p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ChannelUpdate {

    /** 签名长度（Ed25519 签名为 64 字节）。 */
    public static final int SIGNATURE_SIZE = 64;

    /** 通道唯一标识符。 */
    private String channelId;

    /** 更新序号（递增），防止重放攻击。 */
    private long nonce;

    /** 参与方一余额（单位：NEX 最小单位）。 */
    private long balance1;

    /** 参与方二余额（单位：NEX 最小单位）。 */
    private long balance2;

    /** 参与方一签名（64 字节 Ed25519 签名）。 */
    private byte[] signature1;

    /** 参与方二签名（64 字节 Ed25519 签名）。 */
    private byte[] signature2;

    /** 创建时间戳（毫秒）。 */
    private long timestamp;

    /**
     * 默认构造函数（用于 JSON 反序列化）。
     */
    public ChannelUpdate() {
    }

    /**
     * 全参数构造函数。
     *
     * @param channelId  通道 ID
     * @param nonce      更新序号
     * @param balance1   参与方一余额
     * @param balance2   参与方二余额
     * @param signature1 参与方一签名（64 字节），可为 null 表示尚未签名
     * @param signature2 参与方二签名（64 字节），可为 null 表示尚未签名
     * @param timestamp  创建时间戳（毫秒）
     */
    public ChannelUpdate(String channelId, long nonce, long balance1, long balance2,
                         byte[] signature1, byte[] signature2, long timestamp) {
        this.channelId = channelId;
        this.nonce = nonce;
        this.balance1 = balance1;
        this.balance2 = balance2;
        this.signature1 = signature1;
        this.signature2 = signature2;
        this.timestamp = timestamp;
    }

    // ==================== Serialization for Signing ====================

    /**
     * 获取需要签名的消息字节。
     *
     * <p>签名内容 = channelId（UTF-8）+ nonce（8 字节大端）+
     * balance1（8 字节大端）+ balance2（8 字节大端）。
     * 该字节序列经 {@link HashUtil#keccak256(byte[])} 哈希后，
     * 使用 Ed25519 私钥签名。</p>
     *
     * @return 用于签名的消息字节
     */
    public byte[] getMessageToSign() {
        byte[] channelIdBytes = channelId.getBytes(StandardCharsets.UTF_8);
        return org.nexus.util.ByteUtil.merge(
                channelIdBytes,
                BigEndian.encodeUint64(nonce),
                BigEndian.encodeUint64(balance1),
                BigEndian.encodeUint64(balance2)
        );
    }

    // ==================== Verification Methods ====================

    /**
     * 验证双方签名是否有效。
     *
     * <p>使用参与方各自的公钥验证对应签名。签名消息为
     * {@link #getMessageToSign()} 的 Keccak-256 哈希。</p>
     *
     * @param pubkey1 参与方一公钥（32 字节）
     * @param pubkey2 参与方二公钥（32 字节）
     * @return true 如果两个签名都有效，false 否则
     */
    public boolean verifySignatures(byte[] pubkey1, byte[] pubkey2) {
        if (signature1 == null || signature2 == null) {
            return false;
        }
        if (signature1.length != SIGNATURE_SIZE || signature2.length != SIGNATURE_SIZE) {
            return false;
        }
        byte[] messageHash = HashUtil.keccak256(getMessageToSign());
        try {
            Ed25519PublicKey pk1 = new Ed25519PublicKey(pubkey1);
            Ed25519PublicKey pk2 = new Ed25519PublicKey(pubkey2);
            boolean valid1 = pk1.verify(messageHash, signature1);
            boolean valid2 = pk2.verify(messageHash, signature2);
            return valid1 && valid2;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 检查余额守恒。
     *
     * <p>验证 balance1 + balance2 是否等于通道总余额。
     * 同时检查两个余额均为非负值。</p>
     *
     * @param totalBalance 通道开启时的总余额
     * @return true 如果余额守恒且非负，false 否则
     */
    public boolean isBalanceConserved(long totalBalance) {
        if (balance1 < 0 || balance2 < 0) {
            return false;
        }
        return (balance1 + balance2) == totalBalance;
    }

    /**
     * 检查参与方一的签名是否存在。
     *
     * @return true 如果 signature1 非空且长度正确
     */
    public boolean hasSignature1() {
        return signature1 != null && signature1.length == SIGNATURE_SIZE;
    }

    /**
     * 检查参与方二的签名是否存在。
     *
     * @return true 如果 signature2 非空且长度正确
     */
    public boolean hasSignature2() {
        return signature2 != null && signature2.length == SIGNATURE_SIZE;
    }

    /**
     * 检查是否双方都已签名。
     *
     * @return true 如果两个签名都存在且长度正确
     */
    public boolean isFullySigned() {
        return hasSignature1() && hasSignature2();
    }

    // ==================== Getters and Setters ====================

    /**
     * 获取通道 ID。
     * @return 通道 ID
     */
    public String getChannelId() {
        return channelId;
    }

    /**
     * 设置通道 ID。
     * @param channelId 通道 ID
     */
    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    /**
     * 获取更新序号。
     * @return nonce 值
     */
    public long getNonce() {
        return nonce;
    }

    /**
     * 设置更新序号。
     * @param nonce nonce 值
     */
    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    /**
     * 获取参与方一余额。
     * @return 参与方一余额
     */
    public long getBalance1() {
        return balance1;
    }

    /**
     * 设置参与方一余额。
     * @param balance1 参与方一余额
     */
    public void setBalance1(long balance1) {
        this.balance1 = balance1;
    }

    /**
     * 获取参与方二余额。
     * @return 参与方二余额
     */
    public long getBalance2() {
        return balance2;
    }

    /**
     * 设置参与方二余额。
     * @param balance2 参与方二余额
     */
    public void setBalance2(long balance2) {
        this.balance2 = balance2;
    }

    /**
     * 获取参与方一签名。
     * @return 签名字节数组，可能为 null
     */
    public byte[] getSignature1() {
        return signature1;
    }

    /**
     * 设置参与方一签名。
     * @param signature1 签名字节数组（64 字节）
     */
    public void setSignature1(byte[] signature1) {
        this.signature1 = signature1;
    }

    /**
     * 获取参与方二签名。
     * @return 签名字节数组，可能为 null
     */
    public byte[] getSignature2() {
        return signature2;
    }

    /**
     * 设置参与方二签名。
     * @param signature2 签名字节数组（64 字节）
     */
    public void setSignature2(byte[] signature2) {
        this.signature2 = signature2;
    }

    /**
     * 获取创建时间戳。
     * @return 时间戳（毫秒）
     */
    public long getTimestamp() {
        return timestamp;
    }

    /**
     * 设置创建时间戳。
     * @param timestamp 时间戳（毫秒）
     */
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    // ==================== Serialization ====================

    /**
     * 将更新消息序列化为 JSON 字符串。
     *
     * <p>签名以十六进制字符串编码。使用 fastjson 进行序列化。</p>
     *
     * @return JSON 字符串
     */
    public String toJson() {
        ChannelUpdateDTO dto = toDTO();
        return JsonUtils.toJson(dto);
    }

    /**
     * 从 JSON 字符串反序列化为 ChannelUpdate 对象。
     *
     * @param json JSON 字符串
     * @return ChannelUpdate 对象
     */
    public static ChannelUpdate fromJson(String json) {
        ChannelUpdateDTO dto = JsonUtils.fromJson(json, ChannelUpdateDTO.class);
        return fromDTO(dto);
    }

    /**
     * 转换为 DTO 对象（签名字节转十六进制字符串）。
     *
     * @return DTO 对象
     */
    private ChannelUpdateDTO toDTO() {
        ChannelUpdateDTO dto = new ChannelUpdateDTO();
        dto.setChannelId(channelId);
        dto.setNonce(nonce);
        dto.setBalance1(balance1);
        dto.setBalance2(balance2);
        dto.setSignature1(signature1 != null ? Hex.encodeHexString(signature1) : null);
        dto.setSignature2(signature2 != null ? Hex.encodeHexString(signature2) : null);
        dto.setTimestamp(timestamp);
        return dto;
    }

    /**
     * 从 DTO 对象转换（十六进制字符串转签名字节）。
     *
     * @param dto DTO 对象
     * @return ChannelUpdate 对象
     */
    private static ChannelUpdate fromDTO(ChannelUpdateDTO dto) {
        ChannelUpdate update = new ChannelUpdate();
        update.setChannelId(dto.getChannelId());
        update.setNonce(dto.getNonce());
        update.setBalance1(dto.getBalance1());
        update.setBalance2(dto.getBalance2());
        try {
            update.setSignature1(dto.getSignature1() != null ? Hex.decodeHex(dto.getSignature1()) : null);
            update.setSignature2(dto.getSignature2() != null ? Hex.decodeHex(dto.getSignature2()) : null);
        } catch (org.apache.commons.codec.DecoderException e) {
            throw new IllegalArgumentException("Invalid hex signature: " + e.getMessage(), e);
        }
        update.setTimestamp(dto.getTimestamp());
        return update;
    }

    @Override
    public String toString() {
        return "ChannelUpdate{" +
                "channelId='" + channelId + '\'' +
                ", nonce=" + nonce +
                ", balance1=" + balance1 +
                ", balance2=" + balance2 +
                ", signature1=" + (signature1 != null ? Hex.encodeHexString(signature1).substring(0, 16) + "..." : "null") +
                ", signature2=" + (signature2 != null ? Hex.encodeHexString(signature2).substring(0, 16) + "..." : "null") +
                ", timestamp=" + timestamp +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChannelUpdate that = (ChannelUpdate) o;
        return nonce == that.nonce &&
                balance1 == that.balance1 &&
                balance2 == that.balance2 &&
                timestamp == that.timestamp &&
                java.util.Objects.equals(channelId, that.channelId) &&
                Arrays.equals(signature1, that.signature1) &&
                Arrays.equals(signature2, that.signature2);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(channelId, nonce, balance1, balance2, timestamp);
        result = 31 * result + Arrays.hashCode(signature1);
        result = 31 * result + Arrays.hashCode(signature2);
        return result;
    }

    // ==================== DTO for JSON Serialization ====================

    /**
     * JSON 传输对象，将签名字节数组编码为十六进制字符串。
     */
    public static class ChannelUpdateDTO {
        private String channelId;
        private long nonce;
        private long balance1;
        private long balance2;
        private String signature1;
        private String signature2;
        private long timestamp;

        public String getChannelId() { return channelId; }
        public void setChannelId(String channelId) { this.channelId = channelId; }
        public long getNonce() { return nonce; }
        public void setNonce(long nonce) { this.nonce = nonce; }
        public long getBalance1() { return balance1; }
        public void setBalance1(long balance1) { this.balance1 = balance1; }
        public long getBalance2() { return balance2; }
        public void setBalance2(long balance2) { this.balance2 = balance2; }
        public String getSignature1() { return signature1; }
        public void setSignature1(String signature1) { this.signature1 = signature1; }
        public String getSignature2() { return signature2; }
        public void setSignature2(String signature2) { this.signature2 = signature2; }
        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    }
}
