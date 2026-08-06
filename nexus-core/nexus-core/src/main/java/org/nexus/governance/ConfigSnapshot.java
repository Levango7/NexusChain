package org.nexus.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 配置快照实体（多版本）。
 *
 * <p>记录某一时刻所有可治理参数的值快照，附带版本号、时间戳与标签，
 * 用于支持多版本快照历史与指定版本回滚。回滚操作本身需经治理提案审批后方可执行，
 * 避免单点误操作直接改写链上配置。</p>
 *
 * @since 1.4
 */
public class ConfigSnapshot {

    /** 快照版本号（自增） */
    private final int version;

    /** 快照时间 */
    private final Instant timestamp;

    /** 快照标签（语义描述，如 "pre-upgrade"、"before-proposal-123"） */
    private final String tag;

    /** 参数名 -> 参数值 的不可变快照 */
    private final Map<String, BigDecimal> values;

    public ConfigSnapshot(int version, Instant timestamp, String tag, Map<String, BigDecimal> values) {
        this.version = version;
        this.timestamp = timestamp;
        this.tag = tag;
        this.values = values == null ? Collections.emptyMap() : Collections.unmodifiableMap(values);
    }

    public int getVersion() {
        return version;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getTag() {
        return tag;
    }

    /**
     * 返回快照参数值的只读视图。
     *
     * @return 参数名 -> 参数值
     */
    public Map<String, BigDecimal> getValues() {
        return values;
    }

    @Override
    public String toString() {
        return "ConfigSnapshot{version=" + version + ", timestamp=" + timestamp
                + ", tag='" + tag + "', size=" + values.size() + '}';
    }
}