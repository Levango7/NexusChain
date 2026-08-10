"""
ConPay SDK 钱包管理模块。

提供钱包创建、导入、余额查询等能力。
所有余额以最小单位（wei）表示，CPAY 为原生代币。
"""

from typing import Optional
from dataclasses import dataclass


@dataclass
class WalletInfo:
    """钱包信息。"""

    address: str
    private_key: str
    public_key: str


class Wallet:
    """钱包管理器。

    Args:
        client: ConPayClient 实例
    """

    def __init__(self, client):
        self._client = client

    def create(self) -> WalletInfo:
        """
        创建新钱包，生成新的密钥对。

        Returns:
            新创建的 WalletInfo
        """
        # TODO: 生成 ECDSA 密钥对
        raise NotImplementedError("Not yet implemented")

    def from_private_key(self, private_key: str) -> WalletInfo:
        """
        从私钥导入钱包。

        Args:
            private_key: 十六进制私钥

        Returns:
            导入的 WalletInfo
        """
        # TODO: 从私钥推导公钥和地址
        raise NotImplementedError("Not yet implemented")

    def from_mnemonic(self, mnemonic: str, path: str = "m/44'/60'/0'/0/0") -> WalletInfo:
        """
        从助记词导入钱包。

        Args:
            mnemonic: BIP-39 助记词
            path: 派生路径

        Returns:
            导入的 WalletInfo
        """
        # TODO: 从助记词派生密钥对
        raise NotImplementedError("Not yet implemented")

    def get_balance(self, address: str) -> int:
        """
        查询地址的 CPAY 余额。

        Args:
            address: 钱包地址

        Returns:
            余额（最小单位 wei）
        """
        result = self._client.rpc_call("conpay_getBalance", [address, "latest"])
        if isinstance(result, str):
            return int(result, 16)
        return int(result)

    def get_token_balance(self, address: str, token_contract: str) -> int:
        """
        查询地址的指定代币余额。

        Args:
            address: 钱包地址
            token_contract: 代币合约地址

        Returns:
            代币余额（最小单位）
        """
        # TODO: 调用合约 balanceOf 方法
        raise NotImplementedError("Not yet implemented")

    def validate_address(self, address: str) -> bool:
        """
        验证地址格式是否合法。

        Args:
            address: 待验证地址

        Returns:
            是否合法
        """
        # TODO: 地址格式校验
        raise NotImplementedError("Not yet implemented")
