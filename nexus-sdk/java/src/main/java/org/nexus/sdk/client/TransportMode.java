package org.nexus.sdk.client;

/**
 * 跨服务客户端传输模式标识。
 *
 * <p>P2 方向5「签名服务独立部署 PoC」引入的跨服务调用抽象。
 * 当前进程内调用使用 {@link #IN_PROCESS}（composite build 直连），
 * 未来切换为 {@link #HTTP}（REST + Nacos 服务发现）。</p>
 */
public enum TransportMode {

    /** 进程内直连（composite build 依赖替换，当前阶段默认） */
    IN_PROCESS,

    /** HTTP REST 调用（未来独立部署后启用，配合 Nacos 服务发现） */
    HTTP
}