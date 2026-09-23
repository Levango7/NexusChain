package org.nexus.gateway.ops;

/**
 * 线程池统计信息 DTO。
 *
 * <p>封装线程池的运行时状态指标，包括活跃线程数、队列大小、已完成任务数等。
 * 用于 GET /api/v1/ops/threadpools 端点响应。</p>
 */
public class ThreadPoolStatsDTO {

    /** 线程池名称 */
    private String poolName;

    /** 核心线程数 */
    private int corePoolSize;

    /** 最大线程数 */
    private int maximumPoolSize;

    /** 当前活跃线程数 */
    private int activeCount;

    /** 当前队列中等待的任务数 */
    private int queueSize;

    /** 队列容量 */
    private int queueCapacity;

    /** 已完成任务数 */
    private long completedTaskCount;

    /** 总任务数（包括排队和已完成的） */
    private long taskCount;

    /** 线程池是否已关闭 */
    private boolean isShutdown;

    /** 线程池是否已终止 */
    private boolean isTerminated;

    public ThreadPoolStatsDTO() {
    }

    public ThreadPoolStatsDTO(String poolName, int corePoolSize, int maximumPoolSize,
                               int activeCount, int queueSize, int queueCapacity,
                               long completedTaskCount, long taskCount,
                               boolean isShutdown, boolean isTerminated) {
        this.poolName = poolName;
        this.corePoolSize = corePoolSize;
        this.maximumPoolSize = maximumPoolSize;
        this.activeCount = activeCount;
        this.queueSize = queueSize;
        this.queueCapacity = queueCapacity;
        this.completedTaskCount = completedTaskCount;
        this.taskCount = taskCount;
        this.isShutdown = isShutdown;
        this.isTerminated = isTerminated;
    }

    // --- Getters and Setters ---

    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }

    public int getCorePoolSize() { return corePoolSize; }
    public void setCorePoolSize(int corePoolSize) { this.corePoolSize = corePoolSize; }

    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }

    public int getActiveCount() { return activeCount; }
    public void setActiveCount(int activeCount) { this.activeCount = activeCount; }

    public int getQueueSize() { return queueSize; }
    public void setQueueSize(int queueSize) { this.queueSize = queueSize; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }

    public long getCompletedTaskCount() { return completedTaskCount; }
    public void setCompletedTaskCount(long completedTaskCount) { this.completedTaskCount = completedTaskCount; }

    public long getTaskCount() { return taskCount; }
    public void setTaskCount(long taskCount) { this.taskCount = taskCount; }

    public boolean isShutdown() { return isShutdown; }
    public void setShutdown(boolean shutdown) { isShutdown = shutdown; }

    public boolean isTerminated() { return isTerminated; }
    public void setTerminated(boolean terminated) { isTerminated = terminated; }
}