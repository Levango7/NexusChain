package org.nexus.sdk.signing;

import java.math.BigDecimal;

/**
 * 提现审批策略接口，定义所需审批人数及地址白名单校验。
 *
 * <p>本接口原位于 {@code org.nexus.wallet.wallet.approval.ApprovalPolicy}，
 * 在 P2 方向5「签名服务独立部署 PoC」中迁移至 nexus-sdk 共享层，
 * 供 nexus-exchange-wallet、nexus-signing-service、nexus-wallet-service
 * 等多个服务共同依赖，避免跨服务复制 DTO。</p>
 *
 * <p>迁移历史：原 exchange-wallet 包内的同名接口已删除，所有引用已更新至本包路径。
 * REST API 与 JSON 结构保持不变，仅 Java 包路径变更。</p>
 */
public interface ApprovalPolicy {

    /**
     * 计算给定金额与币种所需的审批人数。
     *
     * @param amount   提现金额
     * @param currency 币种符号
     * @return 所需审批人数（&gt;= 1）
     */
    int getRequiredApprovers(BigDecimal amount, String currency);

    /**
     * 判断给定地址是否在提现白名单中。
     *
     * @param address 钱包地址
     * @return {@code true} 表示地址已加白
     */
    boolean isAddressWhitelisted(String address);
}