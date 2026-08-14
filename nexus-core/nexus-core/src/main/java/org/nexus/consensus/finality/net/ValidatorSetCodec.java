package org.nexus.consensus.finality.net;

import java.nio.charset.StandardCharsets;

/**
 * 验证人集合广播消息编解码器（PLAN-001 步骤 1：跨节点验证人同步）。
 *
 * <p>与 {@link FinalityVoteCodec}（投票，魔数 0x5A）区分——本类用魔数 {@code 0x56}（'V' for Validator），
 * 载荷为 JSON：</p>
 * <pre>
 *   {"type":"validator-set","action":"add|remove","address":"...","publicKey":"hex","stakeAmount":"1000"}
 * </pre>
 *
 * <p>传输复用 TRANSACTIONS 通道（TransactionType.VOTE 之外的旁路交易），
 * 接收端 {@code SyncManager} 以魔数探测分流至验证人注册（幂等）。</p>
 */
public final class ValidatorSetCodec {

    public static final byte MAGIC = 0x56;
    public static final String ACTION_ADD = "add";
    public static final String ACTION_REMOVE = "remove";

    private ValidatorSetCodec() {}

    public static byte[] encodeAdd(String address, String publicKey, String stakeAmount) {
        return encode(ACTION_ADD, address, publicKey, stakeAmount);
    }

    public static byte[] encodeRemove(String address) {
        return encode(ACTION_REMOVE, address, null, null);
    }

    public static byte[] encode(String action, String address, String publicKey, String stakeAmount) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("{\"type\":\"validator-set\",\"action\":\"").append(action).append('"');
        sb.append(",\"address\":\"").append(address).append('"');
        if (publicKey != null) {
            sb.append(",\"publicKey\":\"").append(publicKey).append('"');
        }
        if (stakeAmount != null) {
            sb.append(",\"stakeAmount\":\"").append(stakeAmount).append('"');
        }
        sb.append('}');
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + body.length];
        out[0] = MAGIC;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
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
     * @return 消息内容（type/action/address/publicKey/stakeAmount 字段）；畸形返回 null
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
                parseStr(json, "\"stakeAmount\":\""));
    }

    private static String parseStr(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = idx + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    /** 验证人集合消息载体。 */
    public record ValidatorSetMessage(String action, String address,
                                      String publicKey, String stakeAmount) {
        public boolean isAdd() { return ACTION_ADD.equals(action); }
    }
}
