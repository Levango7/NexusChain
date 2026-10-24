package org.nexus.consensus.finality.net;

import java.nio.charset.StandardCharsets;

/**
 * 验证人集合广播消息编解码器（PLAN-001 步骤 1：跨节点验证人同步）。
 *
 * <p>与 {@link FinalityVoteCodec}（投票，魔数 0x5A）区分——本类用魔数 {@code 0x56}（'V' for Validator），
 * 载荷为 JSON：</p>
 * <pre>
 *   v1（无签名，向后兼容）：
 *   {"type":"validator-set","action":"add|remove","address":"...","publicKey":"hex","stakeAmount":"1000"}
 *
 *   v2（PLAN-001 签名扩展，字段可选追加）：
 *   ...,"nonce":1750000000000,"signature":"base64(Ed25519)"}
 * </pre>
 *
 * <p>传输复用 TRANSACTIONS 通道（TransactionType.VOTE 之外的旁路交易），
 * 接收端 {@code SyncManager} 以魔数探测分流至验证人注册（幂等）。</p>
 *
 * <p><b>签名规范串（PLAN-001 扩展切片 2）</b>：签名对象不是 JSON 字节（字段顺序/空白脆弱），
 * 而是 {@link #canonicalSigningBytes} 生成的稳定规范串——行分隔、null 记空串、nonce 十进制：</p>
 * <pre>
 *   nexus-validator-set-v2\n{action}\n{address}\n{publicKey}\n{stakeAmount}\n{nonce}
 * </pre>
 * <p>发送侧对规范串签名，接收侧用解析后的字段重建规范串验签；v1 老消息无
 * {@code nonce}/{@code signature} 字段，解析结果 {@code nonce=0, signature=null}，
 * 行为与旧实现逐字节一致。</p>
 */
public final class ValidatorSetCodec {

    public static final byte MAGIC = 0x56;
    public static final String ACTION_ADD = "add";
    public static final String ACTION_REMOVE = "remove";

    private ValidatorSetCodec() {}

    /** v1 编码：无 nonce、无签名（与旧实现逐字节一致；老节点完全兼容）。 */
    public static byte[] encodeAdd(String address, String publicKey, String stakeAmount) {
        return encode(ACTION_ADD, address, publicKey, stakeAmount, 0L, null);
    }

    /** v1 编码：无 nonce、无签名。 */
    public static byte[] encodeRemove(String address) {
        return encode(ACTION_REMOVE, address, null, null, 0L, null);
    }

    /** v2 编码：带 nonce 与 Ed25519 签名字段（signature 为 null 时退化为 v1 形态，仅多 nonce）。 */
    public static byte[] encodeAdd(String address, String publicKey, String stakeAmount,
                                   long nonce, String signature) {
        return encode(ACTION_ADD, address, publicKey, stakeAmount, nonce, signature);
    }

    /** v2 编码：带 nonce 与签名。 */
    public static byte[] encodeRemove(String address, long nonce, String signature) {
        return encode(ACTION_REMOVE, address, null, null, nonce, signature);
    }

    public static byte[] encode(String action, String address, String publicKey, String stakeAmount,
                                long nonce, String signature) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"type\":\"validator-set\",\"action\":\"").append(action).append('"');
        sb.append(",\"address\":\"").append(address).append('"');
        if (publicKey != null) {
            sb.append(",\"publicKey\":\"").append(publicKey).append('"');
        }
        if (stakeAmount != null) {
            sb.append(",\"stakeAmount\":\"").append(stakeAmount).append('"');
        }
        if (nonce != 0L) {
            sb.append(",\"nonce\":").append(nonce);
        }
        if (signature != null && !signature.isEmpty()) {
            sb.append(",\"signature\":\"").append(signature).append('"');
        }
        sb.append('}');
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + body.length];
        out[0] = MAGIC;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    /**
     * 签名规范串（发送侧/接收侧共用）：字段用 {@code \n} 分隔、null 记空串，
     * 与 JSON 的表层格式解耦。签名覆盖全部可影响注册结果的字段（action/address/
     * publicKey/stakeAmount）与 nonce。
     */
    public static byte[] canonicalSigningBytes(String action, String address,
                                               String publicKey, String stakeAmount, long nonce) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("nexus-validator-set-v2\n");
        sb.append(action == null ? "" : action).append('\n');
        sb.append(address == null ? "" : address).append('\n');
        sb.append(publicKey == null ? "" : publicKey).append('\n');
        sb.append(stakeAmount == null ? "" : stakeAmount).append('\n');
        sb.append(nonce);
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 规范串重载：直接对解码后的消息重建。 */
    public static byte[] canonicalSigningBytes(ValidatorSetMessage msg) {
        return canonicalSigningBytes(msg.action(), msg.address(),
                msg.publicKey(), msg.stakeAmount(), msg.nonce());
    }

    /**
     * 判断载荷是否为验证人集合消息（魔数探测）。
     */
    public static boolean isValidatorSetPayload(byte[] payload) {
        return payload != null && payload.length > 1 && payload[0] == MAGIC;
    }

    /**
     * 解析验证人集合消息。
     *
     * @return 消息内容（type/action/address/publicKey/stakeAmount/nonce/signature 字段）；
     *         畸形返回 null；v1 老消息 nonce=0、signature=null
     */
    public static ValidatorSetMessage decode(byte[] raw) {
        if (!isValidatorSetPayload(raw)) return null;
        String json = new String(raw, 1, raw.length - 1, StandardCharsets.UTF_8);
        String action = parseStr(json, "\"action\":\"");
        String address = parseStr(json, "\"address\":\"");
        if (action == null || address == null) return null;
        return new ValidatorSetMessage(
                action,
                address,
                parseStr(json, "\"publicKey\":\""),
                parseStr(json, "\"stakeAmount\":\""),
                parseNonce(json),
                parseStr(json, "\"signature\":\""));
    }

    private static String parseStr(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    private static long parseNonce(String json) {
        String key = "\"nonce\":";
        int idx = json.indexOf(key);
        if (idx < 0) return 0L;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) return 0L;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 验证人集合消息载体（v2 起含 nonce 与 signature；v1 老消息为 0/null）。 */
    public record ValidatorSetMessage(String action, String address,
                                      String publicKey, String stakeAmount,
                                      long nonce, String signature) {

        /** v1 兼容构造（nonce=0、无签名）。 */
        public ValidatorSetMessage(String action, String address,
                                   String publicKey, String stakeAmount) {
            this(action, address, publicKey, stakeAmount, 0L, null);
        }

        public boolean isAdd() {
            return ACTION_ADD.equals(action);
        }

        public boolean hasSignature() {
            return signature != null && !signature.isEmpty();
        }
    }
}
