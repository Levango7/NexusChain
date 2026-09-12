package org.nexus.sdk.client;

import java.math.BigDecimal;

/**
 * 钱包管理服务客户端接口。
 *
 * <p>定义调用方（如 nexus-gateway）对「钱包管理服务」的访问边界。
 * 原由 {@code org.nexus.gateway.client.ExchangeWalletClient} 单一客户端承担，
 * 在 P2 方向5「签名服务独立部署 PoC」中拆分为本接口与
 * {@link SigningServiceClient} 两个边界清晰的接口。</p>
 *
 * <p>钱包管理服务承载：地址校验、地址转公钥哈希、提现审批、托管策略、
 * 地址白名单等「不涉及私钥」的钱包管理操作。</p>
 *
 * <p>实现策略：
 * <ul>
 *   <li>进程内（当前）：{@code InProcessWalletMgmtClient} 委托 exchange-wallet 进程内服务</li>
 *   <li>HTTP（未来）：{@code HttpWalletMgmtClient} 通过 REST 调用独立部署的 nexus-wallet-service</li>
 * </ul></p>
 */
public interface WalletMgmtClient {

    /**
     * 将 NEX 地址转换为公钥哈希。
     *
     * @param address NEX 地址（如 "1CRXnUJx9Tq4ZpNkkueeKFxCbYg1E4uTCt"）
     * @return 公钥哈希 hex 字符串，失败返回 {@code null}
     */
    String addressToPubkeyHash(String address);

    /**
     * 校验 NEX 地址是否合法。
     *
     * @param address NEX 地址
     * @return {@code true} 表示合法
     */
    boolean verifyAddress(String address);

    /**
     * 查询指定地址是否在提现白名单中。
     *
     * <p><b>未实现声明（2026-09-11 质量审查修正）</b>：当前生产链路无任何
     * 风控消费方调用本方法（全库检索实证——健康探针走 Feign 接口的真实端点）。
     * 各实现的现状：HttpWalletMgmtClient 返回 {@code false}（PoC 占位）、
     * Feign 客户端对接 wallet-service 真端点。若未来把本方法接入风控决策
     * （提现白名单拦截），<b>必须先实现真实查询</b>——静默返回 {@code false}
     * 的实现会让全部地址被误拒（fail-closed 方向但语义错误），恒
     * {@code true} 则形同虚设。接入前删除本段声明并补集成测试。</p>
     *
     * @param address 钱包地址
     * @return {@code true} 表示已加白
     */
    boolean isAddressWhitelisted(String address);

    /**
     * 查询指定钱包的托管层级。
     *
     * <p><b>未实现声明（2026-09-11 质量审查修正）</b>：同
     * {@link #isAddressWhitelisted}——无生产消费方；HttpWalletMgmtClient
     * 占位返回 "HOT"。未来接入托管层级策略（如大额转 COLD 审批）前必须
     * 实现真实查询，占位值不得参与任何业务判定。</p>
     *
     * @param walletId 钱包 ID
     * @return 托管层级名称（如 "HOT" / "COLD"）
     */
    String getCustodyTier(String walletId);
}