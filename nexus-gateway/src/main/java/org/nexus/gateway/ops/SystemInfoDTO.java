package org.nexus.gateway.ops;

import java.util.List;
import java.util.Map;

/**
 * 系统信息 DTO。
 *
 * <p>封装 JVM 运行时指标，包括堆内存使用、GC 统计、运行时长、CPU 使用率等。
 * 用于 GET /api/v1/ops/system 端点响应。</p>
 */
public class SystemInfoDTO {

    /** JVM 名称 */
    private String jvmName;

    /** JVM 版本 */
    private String jvmVersion;

    /** JVM 供应商 */
    private String jvmVendor;

    /** JVM 启动时间（时间戳，毫秒） */
    private long startTime;

    /** JVM 运行时长（毫秒） */
    private long uptime;

    /** 堆内存已使用（字节） */
    private long heapUsed;

    /** 堆内存最大值（字节） */
    private long heapMax;

    /** 堆内存已提交（字节） */
    private long heapCommitted;

    /** 堆内存使用率（0.0 ~ 1.0） */
    private double heapUsageRate;

    /** 非堆内存已使用（字节） */
    private long nonHeapUsed;

    /** 非堆内存最大值（字节） */
    private long nonHeapMax;

    /** 非堆内存已提交（字节） */
    private long nonHeapCommitted;

    /** 可用处理器数 */
    private int availableProcessors;

    /** 系统负载平均值（最近 1 分钟） */
    private double systemLoadAverage;

    /** GC 统计列表（每个 GC 算法一条记录） */
    private List<GcStats> gcStats;

    /** 已加载类数量 */
    private int loadedClassCount;

    /** 线程总数 */
    private int threadCount;

    /** 守护线程数 */
    private int daemonThreadCount;

    /** 系统属性摘要（脱敏后） */
    private Map<String, String> systemProperties;

    // --- 嵌套类：GC 统计 ---

    public static class GcStats {
        /** GC 算法名称 */
        private String name;
        /** GC 收集次数 */
        private long collectionCount;
        /** GC 收集总耗时（毫秒） */
        private long collectionTime;
        /** GC 收集平均耗时（毫秒） */
        private double averageCollectionTime;

        public GcStats() {
        }

        public GcStats(String name, long collectionCount, long collectionTime, double averageCollectionTime) {
            this.name = name;
            this.collectionCount = collectionCount;
            this.collectionTime = collectionTime;
            this.averageCollectionTime = averageCollectionTime;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public long getCollectionCount() { return collectionCount; }
        public void setCollectionCount(long collectionCount) { this.collectionCount = collectionCount; }

        public long getCollectionTime() { return collectionTime; }
        public void setCollectionTime(long collectionTime) { this.collectionTime = collectionTime; }

        public double getAverageCollectionTime() { return averageCollectionTime; }
        public void setAverageCollectionTime(double averageCollectionTime) { this.averageCollectionTime = averageCollectionTime; }
    }

    // --- Getters and Setters ---

    public String getJvmName() { return jvmName; }
    public void setJvmName(String jvmName) { this.jvmName = jvmName; }

    public String getJvmVersion() { return jvmVersion; }
    public void setJvmVersion(String jvmVersion) { this.jvmVersion = jvmVersion; }

    public String getJvmVendor() { return jvmVendor; }
    public void setJvmVendor(String jvmVendor) { this.jvmVendor = jvmVendor; }

    public long getStartTime() { return startTime; }
    public void setStartTime(long startTime) { this.startTime = startTime; }

    public long getUptime() { return uptime; }
    public void setUptime(long uptime) { this.uptime = uptime; }

    public long getHeapUsed() { return heapUsed; }
    public void setHeapUsed(long heapUsed) { this.heapUsed = heapUsed; }

    public long getHeapMax() { return heapMax; }
    public void setHeapMax(long heapMax) { this.heapMax = heapMax; }

    public long getHeapCommitted() { return heapCommitted; }
    public void setHeapCommitted(long heapCommitted) { this.heapCommitted = heapCommitted; }

    public double getHeapUsageRate() { return heapUsageRate; }
    public void setHeapUsageRate(double heapUsageRate) { this.heapUsageRate = heapUsageRate; }

    public long getNonHeapUsed() { return nonHeapUsed; }
    public void setNonHeapUsed(long nonHeapUsed) { this.nonHeapUsed = nonHeapUsed; }

    public long getNonHeapMax() { return nonHeapMax; }
    public void setNonHeapMax(long nonHeapMax) { this.nonHeapMax = nonHeapMax; }

    public long getNonHeapCommitted() { return nonHeapCommitted; }
    public void setNonHeapCommitted(long nonHeapCommitted) { this.nonHeapCommitted = nonHeapCommitted; }

    public int getAvailableProcessors() { return availableProcessors; }
    public void setAvailableProcessors(int availableProcessors) { this.availableProcessors = availableProcessors; }

    public double getSystemLoadAverage() { return systemLoadAverage; }
    public void setSystemLoadAverage(double systemLoadAverage) { this.systemLoadAverage = systemLoadAverage; }

    public List<GcStats> getGcStats() { return gcStats; }
    public void setGcStats(List<GcStats> gcStats) { this.gcStats = gcStats; }

    public int getLoadedClassCount() { return loadedClassCount; }
    public void setLoadedClassCount(int loadedClassCount) { this.loadedClassCount = loadedClassCount; }

    public int getThreadCount() { return threadCount; }
    public void setThreadCount(int threadCount) { this.threadCount = threadCount; }

    public int getDaemonThreadCount() { return daemonThreadCount; }
    public void setDaemonThreadCount(int daemonThreadCount) { this.daemonThreadCount = daemonThreadCount; }

    public Map<String, String> getSystemProperties() { return systemProperties; }
    public void setSystemProperties(Map<String, String> systemProperties) { this.systemProperties = systemProperties; }
}