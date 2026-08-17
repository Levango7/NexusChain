package org.nexus.bridge.saga;

/**
 * Saga 步骤定义（P2-F2）。
 *
 * <p>一个 Saga 步骤包含正向动作 {@link #action} 与补偿动作 {@link #compensation}。
 * 当正向动作失败时，协调器按已成功步骤的逆序执行补偿动作。</p>
 *
 * <p>本类为函数式封装：{@code action} 与 {@code compensation} 均为
 * {@link ThrowingRunnable}，可抛出任意受检 / 非受检异常，由协调器统一捕获。</p>
 *
 * @since 2.2.0
 */
public class SagaStep {

    /** 步骤名（用于日志 / 持久化 / 索引）。 */
    private final String name;

    /** 正向动作。 */
    private final ThrowingRunnable action;

    /** 补偿动作（可为 {@code null}，表示本步骤不可补偿 / 无需补偿）。 */
    private final ThrowingRunnable compensation;

    /**
     * 构造 Saga 步骤。
     *
     * @param name         步骤名
     * @param action       正向动作
     * @param compensation 补偿动作（可为 {@code null}）
     */
    public SagaStep(String name, ThrowingRunnable action, ThrowingRunnable compensation) {
        this.name = name;
        this.action = action;
        this.compensation = compensation;
    }

    public String getName() {
        return name;
    }

    public ThrowingRunnable getAction() {
        return action;
    }

    public ThrowingRunnable getCompensation() {
        return compensation;
    }

    /**
     * 是否可补偿。
     *
     * @return 补偿动作非 {@code null} 返回 {@code true}
     */
    public boolean isCompensable() {
        return compensation != null;
    }

    /**
     * 可抛异常的 Runnable。
     */
    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}