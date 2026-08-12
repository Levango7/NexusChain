package org.nexus.consensus.finality.net;

import org.nexus.consensus.finality.Vote;

import java.nio.charset.StandardCharsets;

/**
 * 最终性投票网络载荷编解码器（ADR-030 M_net 阶段冻结消息格式）。
 *
 * <p>投票消息体统一为 JSON，进入 P2P 前的封装规则：</p>
 * <ul>
 *   <li>载荷前缀 Byte 标记 {@link #MAGIC}（0x5A，占位），后续 protoc 生成后可去除</li>
 *   <li>载荷格式： {@code {"epoch":1,"checkpoint":"hex","validator":"addr","sig":"hex"}}</li>
 *   <li>字节序：signed big-endian（与区块链其余模块统一）</li>
 * </ul>
 *
 * <p>未来当 {@code protoc} 工具链可用后，将替换为 proto 文件
 * {@code message FinalityVote} + {@code Code.FINALITY_VOTE = 15} 生成类，
 * 本编解码器保留为回归基线。</p>
 */
public final class FinalityVoteCodec {

    public static final byte MAGIC = 0x5A;

    private FinalityVoteCodec() {}

    public static byte[] encode(Vote vote) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"epoch\":").append(vote.getEpoch());
        sb.append(",\"checkpoint\":\"").append(toHex(vote.getCheckpointHash())).append('"');
        sb.append(",\"validator\":\"").append(escape(vote.getValidatorAddress())).append('"');
        sb.append(",\"sig\":\"").append(toHex(vote.getSignature())).append('"');
        sb.append('}');
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + body.length];
        out[0] = MAGIC;
        System.arraycopy(body, 0, out, 1, body.length);
        return out;
    }

    public static Vote decode(byte[] raw) {
        if (raw == null || raw.length < 2 || raw[0] != MAGIC) {
            return null;
        }
        String json = new String(raw, 1, raw.length - 1, StandardCharsets.UTF_8);
        long epoch = parseLong(json, "\"epoch\":");
        String checkpointHex = parseStr(json, "\"checkpoint\":\"");
        String validator = parseStr(json, "\"validator\":\"");
        String sigHex = parseStr(json, "\"sig\":\"");
        return new Vote(epoch, fromHex(checkpointHex), validator, fromHex(sigHex));
    }

    private static long parseLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return 0;
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return Long.parseLong(json.substring(start, end));
    }

    private static String parseStr(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return "";
        int start = idx + key.length();
        int end = json.indexOf('"', start);
        return end < 0 ? "" : json.substring(start, end);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String toHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(Character.forDigit((b >> 4) & 0xF, 16))
                               .append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) ((Character.digit(hex.charAt(i * 2), 16) << 4)
                    | Character.digit(hex.charAt(i * 2 + 1), 16));
        }
        return out;
    }
}
