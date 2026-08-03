package org.nexus.sdk;

import java.math.BigInteger;

/**
 * 钱包管理接口。
 *
 * <p>提供钱包创建、导入、余额查询等能力。
 * 所有代币余额以最小单位（wei）表示，NEX 为原生代币。</p>
 */
public class Wallet {

    private final RpcClient rpcClient;
    private final String network;

    public Wallet(RpcClient rpcClient, String network) {
        this.rpcClient = rpcClient;
        this.network = network;
    }

    /**
     * 创建新钱包，生成新的密钥对。
     *
     * @return 新创建的 WalletInfo
     */
    public WalletInfo create() {
        // TODO: 生成 ECDSA 密钥对
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 从私钥导入钱包。
     *
     * @param privateKey 十六进制私钥
     * @return 导入的 WalletInfo
     */
    public WalletInfo fromPrivateKey(String privateKey) {
        // TODO: 从私钥推导公钥和地址
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 从助记词导入钱包。
     *
     * @param mnemonic BIP-39 助记词
     * @param path     派生路径（如 "m/44'/60'/0'/0/0"）
     * @return 导入的 WalletInfo
     */
    public WalletInfo fromMnemonic(String mnemonic, String path) {
        // TODO: 从助记词派生密钥对
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 查询地址的 NEX 余额。
     *
     * @param address 钱包地址
     * @return 余额（最小单位 wei）
     */
    public BigInteger getBalance(String address) {
        // TODO: 调用 RPC 查询余额
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 查询地址的指定代币余额。
     *
     * @param address  钱包地址
     * @param tokenContract 代币合约地址
     * @return 代币余额（最小单位）
     */
    public BigInteger getTokenBalance(String address, String tokenContract) {
        // TODO: 调用合约查询代币余额
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 验证地址格式是否合法。
     *
     * @param address 待验证地址
     * @return 是否合法
     */
    public boolean validateAddress(String address) {
        // TODO: 地址格式校验
        throw new UnsupportedOperationException("Not yet implemented");
    }

    /**
     * 钱包信息封装类。
     */
    public static class WalletInfo {
        private final String address;
        private final String privateKey;
        private final String publicKey;

        public WalletInfo(String address, String privateKey, String publicKey) {
            this.address = address;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        public String getAddress() {
            return address;
        }

        public String getPrivateKey() {
            return privateKey;
        }

        public String getPublicKey() {
            return publicKey;
        }
    }
}
