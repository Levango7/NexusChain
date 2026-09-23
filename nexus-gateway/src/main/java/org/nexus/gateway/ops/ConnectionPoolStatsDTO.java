package org.nexus.gateway.ops;

/**
 * 连接池统计信息 DTO。
 *
 * <p>封装 HikariCP 数据库连接池的运行时状态指标，包括活跃/空闲/总连接数、等待线程数等。
 * 用于 GET /api/v1/ops/connection-pools 端点响应。</p>
 */
public class ConnectionPoolStatsDTO {

    /** 连接池名称 */
    private String poolName;

    /** 活跃连接数（正在被使用的连接） */
    private int activeConnections;

    /** 空闲连接数（可用但未被使用的连接） */
    private int idleConnections;

    /** 总连接数（活跃 + 空闲） */
    private int totalConnections;

    /** 等待获取连接的线程数 */
    private int threadsAwaitingConnection;

    /** 连接池最大容量 */
    private int maximumPoolSize;

    /** 连接池最小空闲连接数 */
    private int minimumIdle;

    /** 连接超时时间（毫秒） */
    private long connectionTimeout;

    /** 空闲连接超时时间（毫秒） */
    private long idleTimeout;

    /** 连接最大生命周期（毫秒） */
    private long maxLifetime;

    /** 挂起连接数（正在被关闭或初始化的连接） */
    private int suspendedConnections;

    public ConnectionPoolStatsDTO() {
    }

    public ConnectionPoolStatsDTO(String poolName, int activeConnections, int idleConnections,
                                  int totalConnections, int threadsAwaitingConnection,
                                  int maximumPoolSize, int minimumIdle,
                                  long connectionTimeout, long idleTimeout, long maxLifetime,
                                  int suspendedConnections) {
        this.poolName = poolName;
        this.activeConnections = activeConnections;
        this.idleConnections = idleConnections;
        this.totalConnections = totalConnections;
        this.threadsAwaitingConnection = threadsAwaitingConnection;
        this.maximumPoolSize = maximumPoolSize;
        this.minimumIdle = minimumIdle;
        this.connectionTimeout = connectionTimeout;
        this.idleTimeout = idleTimeout;
        this.maxLifetime = maxLifetime;
        this.suspendedConnections = suspendedConnections;
    }

    // --- Getters and Setters ---

    public String getPoolName() { return poolName; }
    public void setPoolName(String poolName) { this.poolName = poolName; }

    public int getActiveConnections() { return activeConnections; }
    public void setActiveConnections(int activeConnections) { this.activeConnections = activeConnections; }

    public int getIdleConnections() { return idleConnections; }
    public void setIdleConnections(int idleConnections) { this.idleConnections = idleConnections; }

    public int getTotalConnections() { return totalConnections; }
    public void setTotalConnections(int totalConnections) { this.totalConnections = totalConnections; }

    public int getThreadsAwaitingConnection() { return threadsAwaitingConnection; }
    public void setThreadsAwaitingConnection(int threadsAwaitingConnection) { this.threadsAwaitingConnection = threadsAwaitingConnection; }

    public int getMaximumPoolSize() { return maximumPoolSize; }
    public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }

    public int getMinimumIdle() { return minimumIdle; }
    public void setMinimumIdle(int minimumIdle) { this.minimumIdle = minimumIdle; }

    public long getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(long connectionTimeout) { this.connectionTimeout = connectionTimeout; }

    public long getIdleTimeout() { return idleTimeout; }
    public void setIdleTimeout(long idleTimeout) { this.idleTimeout = idleTimeout; }

    public long getMaxLifetime() { return maxLifetime; }
    public void setMaxLifetime(long maxLifetime) { this.maxLifetime = maxLifetime; }

    public int getSuspendedConnections() { return suspendedConnections; }
    public void setSuspendedConnections(int suspendedConnections) { this.suspendedConnections = suspendedConnections; }
}