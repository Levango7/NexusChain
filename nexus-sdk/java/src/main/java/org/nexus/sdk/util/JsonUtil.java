package org.nexus.sdk.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 跨服务共享的 Gson 单例工具。
 *
 * <p>原位于 {@code org.nexus.wallet.util.JsonUtil}（nexus-exchange-wallet），
 * 在 Phase 1 微服务化中迁移至 nexus-sdk 共享层（新包 {@code org.nexus.sdk.util}），
 * 供 nexus-signing-service / nexus-wallet-service 等多个服务共同依赖。</p>
 *
 * <p>gson 版本由各消费方模块的 BOM 管理（exchange-wallet 当前钉 2.10.1）。
 * {@code serializeNulls()} 镜像 fastjson 默认行为（包含 null 字段），
 * 保证 toJson/toJSONString 迁移后字节级等价。</p>
 */
public class JsonUtil {
    public static final Gson GSON = new GsonBuilder().serializeNulls().create();
    public static final Gson GSON_PRETTY = new GsonBuilder().serializeNulls().setPrettyPrinting().create();
}