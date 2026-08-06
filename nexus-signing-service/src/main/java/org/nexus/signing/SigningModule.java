package org.nexus.signing;

/**
 * 签名服务模块标识与版本信息。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」新建模块。本类作为模块入口标识，
 * 实际启动类在未来完整迁移时由 SigningServiceApplication 提供。</p>
 */
public final class SigningModule {

    /** 模块名称 */
    public static final String MODULE_NAME = "nexus-signing-service";

    /** 模块版本 */
    public static final String MODULE_VERSION = "1.2.0";

    /** 服务在 Nacos 中注册的默认服务名 */
    public static final String DEFAULT_SERVICE_NAME = "nexus-signing-service";

    private SigningModule() {
    }
}