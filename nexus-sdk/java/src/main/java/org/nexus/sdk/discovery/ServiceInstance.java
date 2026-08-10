package org.nexus.sdk.discovery;

/**
 * 服务实例信息。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」引入。描述服务发现解析出的实例地址、端口、元数据。</p>
 */
public class ServiceInstance {

    /** 实例 ID（如 Nacos 注册的 instanceId） */
    private final String instanceId;

    /** 服务名 */
    private final String serviceName;

    /** 实例 IP / 主机名 */
    private final String host;

    /** 实例端口 */
    private final int port;

    /** 是否启用 HTTPS */
    private final boolean secure;

    /**
     * 构造服务实例。
     *
     * @param instanceId  实例 ID
     * @param serviceName 服务名
     * @param host        主机名
     * @param port        端口
     * @param secure      是否 HTTPS
     */
    public ServiceInstance(String instanceId, String serviceName, String host, int port, boolean secure) {
        this.instanceId = instanceId;
        this.serviceName = serviceName;
        this.host = host;
        this.port = port;
        this.secure = secure;
    }

    public String getInstanceId() { return instanceId; }
    public String getServiceName() { return serviceName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public boolean isSecure() { return secure; }

    /**
     * 拼接基础 URL（如 "http://localhost:8081"）。
     *
     * @return 基础 URL
     */
    public String baseUrl() {
        return (secure ? "https://" : "http://") + host + ":" + port;
    }

    @Override
    public String toString() {
        return "ServiceInstance{" + serviceName + " @ " + baseUrl() + " (id=" + instanceId + ")}";
    }
}