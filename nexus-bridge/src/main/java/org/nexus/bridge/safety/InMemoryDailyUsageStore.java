package org.nexus.bridge.safety;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内日限额用量存储（默认档；单测/嵌入式与历史行为逐条一致）。
 *
 * <p>行为等价于 PLAN-038 之前 {@code BridgeServiceImpl} 内的
 * {@code AtomicLong dailyUsed + lazy resetDailyIfNeeded}：CAS 预留、
 * 过窗惰性重置、回滚补偿减记。局限同历史：重启清零、多副本各持一份。</p>
 *
 * @since 2.76.0
 */
public class InMemoryDailyUsageStore implements DailyUsageStore {

    private final AtomicLong used = new AtomicLong(0);

    private volatile long windowStart;

    public InMemoryDailyUsageStore() {
        this(System.currentTimeMillis());
    }

    /**
     * 指定初始窗口起点的构造器（包级可见，供确定性时间测试使用）。
     *
     * @param initialWindowStart 窗口起点（epoch millis）
     */
    InMemoryDailyUsageStore(long initialWindowStart) {
        this.windowStart = initialWindowStart;
    }

    @Override
    public long currentUsed(long nowMillis) {
        if (nowMillis - windowStart > WINDOW_MILLIS) {
            return 0L;
        }
        return used.get();
    }

    @Override
    public boolean tryReserve(long amount, long limit, long nowMillis) {
        rollIfExpired(nowMillis);
        while (true) {
            long current = used.get();
            if (current + amount > limit) {
                return false;
            }
            if (used.compareAndSet(current, current + amount)) {
                return true;
            }
        }
    }

    @Override
    public void release(long amount, long nowMillis) {
        if (nowMillis - windowStart > WINDOW_MILLIS) {
            // 旧窗口已过窗：余量随滚动作废，归还为空操作（与 Jdbc 实现对齐）
            return;
        }
        used.updateAndGet(v -> Math.max(0L, v - amount));
    }

    private void rollIfExpired(long nowMillis) {
        if (nowMillis - windowStart > WINDOW_MILLIS) {
            used.set(0);
            windowStart = nowMillis;
        }
    }
}
