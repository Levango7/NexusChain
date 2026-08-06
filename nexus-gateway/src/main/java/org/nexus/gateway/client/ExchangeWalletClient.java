package org.nexus.gateway.client;

import org.nexus.sdk.client.SigningServiceClient;
import org.nexus.sdk.client.WalletMgmtClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * exchange-wallet 服务的兼容委托客户端。
 *
 * <p>历史背景：本类原为单一 HTTP 客户端，承担 exchange-wallet 全部端点调用。
 * 在 P2 方向5「签名服务独立部署 PoC」中，按服务边界拆分为：
 * <ul>
 *   <li>{@link WalletMgmtClient} / {@link HttpWalletMgmtClient}：钱包管理操作
 *       （地址校验、地址转公钥哈希、白名单、托管）</li>
 *   <li>{@link SigningServiceClient} / {@link HttpSigningServiceClient}：签名操作
 *       （平台密钥库签名 + 广播、MPC 阈值签名）</li>
 * </ul></p>
 *
 * <p>本类保留作为<strong>兼容委托层</strong>，将原方法委托给拆分后的两个客户端，
 * 避免破坏现有 5 处调用方（ConsortiumConnector / DefaultOnChainExecutionChannel /
 * PaymentServiceImpl / ChainConnector / SubscriptionServiceImpl）。
 * 新代码应直接注入 {@link WalletMgmtClient} 或 {@link SigningServiceClient}。</p>
 *
 * <p>迁移建议：未来完整阶段将调用方逐步切换为直接注入拆分接口，本类最终删除。</p>
 */
@Component
public class ExchangeWalletClient {

    private static final Logger log = LoggerFactory.getLogger(ExchangeWalletClient.class);

    private final WalletMgmtClient walletMgmtClient;
    private final SigningServiceClient signingServiceClient;

    /**
     * 构造兼容委托客户端。
     *
     * @param walletMgmtClient   钱包管理边界客户端
     * @param signingServiceClient 签名边界客户端
     */
    public ExchangeWalletClient(WalletMgmtClient walletMgmtClient,
                                SigningServiceClient signingServiceClient) {
        this.walletMgmtClient = walletMgmtClient;
        this.signingServiceClient = signingServiceClient;
    }

    /**
     * 执行 NEX 转账（legacy 兼容端点，调用方提供私钥）。
     *
     * <p>委托给 {@link SigningServiceClient#transfer}。
     * 新代码应使用 {@link #signTransfer} 避免传输私钥。</p>
     *
     * @param fromPubkey   发送方公钥 hex
     * @param toPubkeyHash 收款方公钥哈希 hex
     * @param amount       转账金额（最小单位）
     * @param privateKey   发送方私钥 hex
     * @return 交易哈希，失败返回 {@code null}
     */
    public String transfer(String fromPubkey, String toPubkeyHash, BigDecimal amount, String privateKey) {
        return signingServiceClient.transfer(fromPubkey, toPubkeyHash, amount, privateKey);
    }

    /**
     * 委托签名 + 广播（不传私钥，使用服务端平台密钥库）。
     *
     * <p>委托给 {@link SigningServiceClient#signTransfer}。</p>
     *
     * @param fromPubkey   平台（热钱包）公钥 hex
     * @param toPubkeyHash 收款方公钥哈希 hex
     * @param amount       转账金额（最小单位）
     * @return 交易哈希，失败返回 {@code null}
     */
    public String signTransfer(String fromPubkey, String toPubkeyHash, BigDecimal amount) {
        return signingServiceClient.signTransfer(fromPubkey, toPubkeyHash, amount);
    }

    /**
     * 将 NEX 地址转换为公钥哈希。
     *
     * <p>委托给 {@link WalletMgmtClient#addressToPubkeyHash}。</p>
     *
     * @param address NEX 地址
     * @return 公钥哈希 hex 字符串，失败返回 {@code null}
     */
    public String addressToPubkeyHash(String address) {
        return walletMgmtClient.addressToPubkeyHash(address);
    }

    /**
     * 校验 NEX 地址是否合法。
     *
     * <p>委托给 {@link WalletMgmtClient#verifyAddress}。</p>
     *
     * @param address NEX 地址
     * @return {@code true} 表示合法
     */
    public boolean verifyAddress(String address) {
        return walletMgmtClient.verifyAddress(address);
    }

    /**
     * 暴露内部钱包管理客户端，供新代码直接使用拆分接口。
     *
     * @return 钱包管理边界客户端
     */
    public WalletMgmtClient getWalletMgmtClient() {
        return walletMgmtClient;
    }

    /**
     * 暴露内部签名客户端，供新代码直接使用拆分接口。
     *
     * @return 签名边界客户端
     */
    public SigningServiceClient getSigningServiceClient() {
        return signingServiceClient;
    }
}
