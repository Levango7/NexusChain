package org.nexus.sdk.client;

import java.math.BigDecimal;

/**
 * 签名服务客户端接口。
 *
 * <p>定义调用方（如 nexus-gateway）对「签名服务」的访问边界。
 * 原由 {@code org.nexus.gateway.client.ExchangeWalletClient} 单一客户端承担，
 * 在 P2 方向5「签名服务独立部署 PoC」中拆分为本接口与
 * {@link WalletMgmtClient} 两个边界清晰的接口。</p>
 *
 * <p>签名服务承载：平台密钥库签名 + 广播、MPC 阈值签名等「涉及私钥」的
 * 敏感操作。调用方不接触私钥，由签名服务使用服务端密钥库完成签名。</p>
 *
 * <p>实现策略：
 * <ul>
 *   <li>进程内（当前）：{@code InProcessSigningServiceClient} 委托 exchange-wallet 进程内服务</li>
 *   <li>HTTP（未来）：{@code HttpSigningServiceClient} 通过 REST 调用独立部署的 nexus-signing-service</li>
 * </ul></p>
 */
public interface SigningServiceClient {

    /**
     * 使用平台密钥库签名并广播一笔转账。
     *
     * <p>调用方不传私钥，由签名服务使用服务端 {@code PlatformKeystore} 完成签名。
     * 对应原 {@code ExchangeWalletClient.signTransfer}。</p>
     *
     * @param fromPubkey   平台（热钱包）公钥 hex
     * @param toPubkeyHash 收款方公钥哈希 hex
     * @param amount       转账金额（最小单位）
     * @return 交易哈希，失败返回 {@code null}
     */
    String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount);

    /**
     * 使用调用方提供的私钥构造并广播一笔转账（legacy 兼容端点）。
     *
     * <p>对应原 {@code ExchangeWalletClient.transfer}。新代码应使用
     * {@link #signTransfer} 避免传输私钥。</p>
     *
     * @param fromPubkey   发送方公钥 hex
     * @param toPubkeyHash 收款方公钥哈希 hex
     * @param amount       转账金额（最小单位）
     * @param privateKey   发送方私钥 hex
     * @return 交易哈希，失败返回 {@code null}
     */
    String transfer(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey);

    /**
     * 判断指定金额是否可通过 MPC 流程签名。
     *
     * @param amount 提现金额
     * @return {@code true} 表示当前在线参与者满足阈值
     */
    boolean canSignViaMpc(BigDecimal amount);
}