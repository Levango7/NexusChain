package org.nexus.consensus.finality.net;

import java.nio.charset.StandardCharsets;

/**
 * 最终性**补票请求**载荷编解码器（PLAN-025 Phase 6C）。
 *
 * <p>为什么需要它：</p>
 * <p>最终性投票的跨节点传播是<b>广播</b>语义（{@code FinalityVoteBroadcaster.broadcast}
 * → {@code PeerServer.broadcast}），只保证"在线节点"收到。在<b>共享存储</b>拓扑下，
 * 掉线节点重启后仍能通过 {@code FinalityGadget} 的库拉取（PLAN-021 视图收敛）
 * 把对端的票补回本地视图；但在<b>独立存储</b>拓扑下，对端的票从来不在本节点库里
 * ——广播错过的票<b>永久缺失</b>，最终性视图无法收敛（roadmap #27 的已知缺口）。</p>
 *
 * <p>本 codec 承载一个轻量补票请求，由 {@code FinalityVoteBroadcaster} 周期性广播：</p>
 * <ul>
 *   <li>载荷前缀 {@link #MAGIC} = {@code 0x5B}（与投票 {@code 0x5A}、验证人集 {@code 0x56} 区分）</li>
 *   <li>载荷体：{@code {"from":<epoch>,"max":<上限条数>}}</li>
 *   <li>响应复用既有投票通道（{@code 0x5A} 投票载荷），接收端走同一套
 *       {@code submitVote} → 绑定校验 → 幂等落库 ⇒ <b>不需要新的响应类型</b></li>
 * </ul>
 *
 * <p><b>安全边界</b>：补票请求本身不需要签名（它不改变任何状态，只触发对端把
 * <b>已签名</b>的投票重发一遍）；重发的票在接收端仍要过 {@code validateVoteBinding}
 * 与聚合验签，因此伪造请求最多造成带宽消耗，不能伪造最终性。</p>
 */
public final class FinalityVoteSyncCodec {

    /** 补票请求魔数（与投票 0x5A、验证人集 0x56 区分）。 */
    public static final byte MAGIC = 0x5B;

    /** 单次请求/响应的默认上限，防止一次请求把整条历史全量拉回。 */
    public static final int DEFAULT_MAX_VOTES = 200;

    private FinalityVoteSyncCodec() {}

    /**
     * 补票请求（不可变）。
     *
     * <p>PLAN-057：新增 {@code cursor} 语义——请求方做<b>游标分页爬升</b>
     * （每轮从上一轮的终点继续，而不是每轮都从 `maxEpoch - margin` 起）。
     * 向后兼容：老节点不认识 {@code cursor} 键，解码为 {@code false}
     * ⇒ 行为与 PLAN-025 完全一致。</p>
     *
     * @param fromEpoch 请求起点（含）
     * @param maxVotes  单轮上限
     * @param cursor    是否声明分页（true=FALSE 兼容旧节点行为）
     */
    public record SyncRequest(long fromEpoch, int maxVotes, boolean cursor) {

        /** 兼容构造器：无 cursor 声明时视为旧行为。 */
        public SyncRequest(long fromEpoch, int maxVotes) {
            this(fromEpoch, maxVotes, false);
        }
    }

    public static byte[] encode(long fromEpoch, int maxVotes) {
        return encode(fromEpoch, maxVotes, false);
    }

    /**
     * 带游标声明的补票请求编码。
     *
     * @param fromEpoch 起点（含）
     * @param maxVotes  单轮上限
     * @param cursor    true 时在载荷写入 {@code "cursor":true}——老节点忽略未知键
     */
    public static byte[] encode(long fromEpoch, int maxVotes, boolean cursor) {
        long from = Math.max(0L, fromEpoch);
        int max = maxVotes <= 0 ? DEFAULT_MAX_VOTES : maxVotes;
        String body = "{\"from\":" + from + ",\"max\":" + max + "}"
                + (cursor ? ",\"cursor\":true" : "");
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[1 + raw.length];
        out[0] = MAGIC;
        System.arraycopy(raw, 0, out, 1, raw.length);
        return out;
    }

    public static boolean isSyncPayload(byte[] payload) {
        return payload != null && payload.length > 1 && payload[0] == MAGIC;
    }

    /**
     * 解码补票请求；非本类型或格式损坏返回 {@code null}（调用方据此丢弃，不抛异常）。
     */
    public static SyncRequest decode(byte[] payload) {
        if (!isSyncPayload(payload)) {
            return null;
        }
        String json = new String(payload, 1, payload.length - 1, StandardCharsets.UTF_8);
        Long from = parseLong(json, "\"from\":");
        Long max = parseLong(json, "\"max\":");
        boolean cursor = json.contains("\"cursor\":true");
        if (from == null) {
            return null;
        }
        int limit = (max == null || max <= 0 || max > 10_000) ? DEFAULT_MAX_VOTES : max.intValue();
        return new SyncRequest(Math.max(0L, from), limit, cursor);
    }

    private static Long parseLong(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
