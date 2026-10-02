package org.nexus.oracle.governance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * 测试用可控时钟：时间只在显式 {@link #advance(Duration)} 时推进。
 *
 * <p>用途：治理服务的时间窗口判定（投票期 / 执行延迟）此前依赖真实时钟 + sleep，
 * 在高负载 CI 上存在调度竞态（详见 {@code DefaultGovernanceService} 中 {@code clock}
 * 字段的说明）。注入本类后，测试可精确推进时间，无需 sleep、无竞态。</p>
 */
final class MutableClock extends Clock {

    private volatile Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    /** 推进时钟（只前进，不后退）。 */
    void advance(Duration delta) {
        if (delta.isNegative()) {
            throw new IllegalArgumentException("advance must not be negative: " + delta);
        }
        this.now = this.now.plus(delta);
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
