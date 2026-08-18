package org.nexus.l2.integration;

import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionCount;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.tx.RawTransactionManager;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * MockERC20 合约的 Web3j 手写 wrapper。
 *
 * <p>对应 Solidity 合约 {@code MockERC20.sol}，提供 L2→L1 提款测试
 * 所需的 ERC20 操作：mint / transfer / balanceOf / approve / transferFrom。</p>
 *
 * <h2>合约接口</h2>
 * <pre>{@code
 * contract MockERC20 {
 *     function name() external view returns (string);
 *     function symbol() external view returns (string);
 *     function decimals() external view returns (uint8);
 *     function totalSupply() external view returns (uint256);
 *     function balanceOf(address) external view returns (uint256);
 *     function transfer(address, uint256) external returns (bool);
 *     function approve(address, uint256) external returns (bool);
 *     function transferFrom(address, address, uint256) external returns (bool);
 *     function mint(address, uint256) external;
 * }
 * }</pre>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 * MockERC20 token = new MockERC20(tokenAddress, web3j, txManager, credentials);
 * token.mint(recipient, BigInteger.valueOf(1000));
 * BigInteger balance = token.balanceOf(recipient);
 * }</pre>
 *
 * <p>本 wrapper 不依赖 web3j codegen 生成的合约类，手写实现以避免
 * 引入额外构建依赖。所有交易通过 {@link RawTransactionManager} 直接签名发送。</p>
 *
 * @since 2.1
 */
public class MockERC20 {

    /** 默认 gas 上限 */
    private static final BigInteger DEFAULT_GAS_LIMIT = BigInteger.valueOf(500_000L);

    /** 默认 gas price（Hardhat 默认 1 gwei，此处取 0 让节点自动定价，失败再回退） */
    private static final BigInteger DEFAULT_GAS_PRICE = BigInteger.valueOf(1_000_000_000L);

    private final String contractAddress;
    private final Web3j web3j;
    private final RawTransactionManager txManager;
    private final Credentials credentials;

    /**
     * @param contractAddress 合约地址（0x 前缀）
     * @param web3j           Web3j 客户端
     * @param txManager       交易管理器（已绑定签名者）
     * @param credentials     签名者凭证（用于 eth_call 的 from 地址）
     */
    public MockERC20(String contractAddress, Web3j web3j,
                     RawTransactionManager txManager, Credentials credentials) {
        this.contractAddress = Numeric.prependHexPrefix(
                Numeric.cleanHexPrefix(contractAddress)).toLowerCase();
        this.web3j = web3j;
        this.txManager = txManager;
        this.credentials = credentials;
    }

    /** @return 合约地址 */
    public String getContractAddress() {
        return contractAddress;
    }

    // ==================== view 函数 ====================

    /**
     * 查询 {@code name()}。
     *
     * @return 代币名称
     */
    public String name() throws Exception {
        Function function = new Function(
                "name",
                Collections.<Type>emptyList(),
                Collections.singletonList(new TypeReference<Utf8String>() {}));
        return callViewString(function);
    }

    /**
     * 查询 {@code symbol()}。
     *
     * @return 代币符号
     */
    public String symbol() throws Exception {
        Function function = new Function(
                "symbol",
                Collections.<Type>emptyList(),
                Collections.singletonList(new TypeReference<Utf8String>() {}));
        return callViewString(function);
    }

    /**
     * 查询 {@code decimals()}。
     *
     * @return 小数位数
     */
    public BigInteger decimals() throws Exception {
        Function function = new Function(
                "decimals",
                Collections.<Type>emptyList(),
                Collections.singletonList(new TypeReference<Uint8>() {}));
        return callViewUint(function);
    }

    /**
     * 查询 {@code totalSupply()}。
     *
     * @return 总供应量
     */
    public BigInteger totalSupply() throws Exception {
        Function function = new Function(
                "totalSupply",
                Collections.<Type>emptyList(),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        return callViewUint(function);
    }

    /**
     * 查询 {@code balanceOf(account)}。
     *
     * @param account 账户地址（0x 前缀）
     * @return 余额
     */
    public BigInteger balanceOf(String account) throws Exception {
        Function function = new Function(
                "balanceOf",
                Collections.singletonList(new Address(account)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        return callViewUint(function);
    }

    /**
     * 查询 {@code allowance(owner, spender)}。
     *
     * @param owner   授权方地址
     * @param spender 被授权方地址
     * @return 授权额度
     */
    public BigInteger allowance(String owner, String spender) throws Exception {
        Function function = new Function(
                "allowance",
                Arrays.asList(new Address(owner), new Address(spender)),
                Collections.singletonList(new TypeReference<Uint256>() {}));
        return callViewUint(function);
    }

    // ==================== state-changing 函数 ====================

    /**
     * 调用 {@code mint(account, amount)}（测试用，无权限控制）。
     *
     * @param account 铸造目标地址
     * @param amount  铸造金额
     * @return 交易回执
     */
    public TransactionReceipt mint(String account, BigInteger amount) throws Exception {
        Function function = new Function(
                "mint",
                Arrays.asList(new Address(account), new Uint256(amount)),
                Collections.<TypeReference<?>>emptyList());
        return sendTransaction(function, "mint");
    }

    /**
     * 调用 {@code transfer(recipient, amount)}。
     *
     * @param recipient 接收者地址
     * @param amount    转账金额
     * @return 交易回执
     */
    public TransactionReceipt transfer(String recipient, BigInteger amount) throws Exception {
        Function function = new Function(
                "transfer",
                Arrays.asList(new Address(recipient), new Uint256(amount)),
                Collections.<TypeReference<?>>emptyList());
        return sendTransaction(function, "transfer");
    }

    /**
     * 调用 {@code approve(spender, amount)}。
     *
     * @param spender 被授权地址
     * @param amount  授权额度
     * @return 交易回执
     */
    public TransactionReceipt approve(String spender, BigInteger amount) throws Exception {
        Function function = new Function(
                "approve",
                Arrays.asList(new Address(spender), new Uint256(amount)),
                Collections.<TypeReference<?>>emptyList());
        return sendTransaction(function, "approve");
    }

    /**
     * 调用 {@code transferFrom(from, to, amount)}。
     *
     * @param from   来源地址
     * @param to     目标地址
     * @param amount 转账金额
     * @return 交易回执
     */
    public TransactionReceipt transferFrom(String from, String to, BigInteger amount) throws Exception {
        Function function = new Function(
                "transferFrom",
                Arrays.asList(new Address(from), new Address(to), new Uint256(amount)),
                Collections.<TypeReference<?>>emptyList());
        return sendTransaction(function, "transferFrom");
    }

    // ==================== 内部：交易发送 ====================

    /**
     * 发送 state-changing 交易并等待回执。
     *
     * @param function 函数编码
     * @param funcName 函数名（用于错误消息）
     * @return 交易回执
     * @throws RuntimeException 如果交易失败或回执状态非 OK
     */
    private TransactionReceipt sendTransaction(Function function, String funcName) throws Exception {
        String encoded = FunctionEncoder.encode(function);
        BigInteger nonce = ((EthGetTransactionCount) web3j.ethGetTransactionCount(
                credentials.getAddress(), DefaultBlockParameterName.PENDING).send())
                .getTransactionCount();
        BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
        if (gasPrice == null || gasPrice.signum() <= 0) {
            gasPrice = DEFAULT_GAS_PRICE;
        }
        org.web3j.crypto.RawTransaction rawTx = org.web3j.crypto.RawTransaction.createTransaction(
                nonce, gasPrice, DEFAULT_GAS_LIMIT, contractAddress, encoded);
        EthSendTransaction sendResp = txManager.signAndSend(rawTx);
        if (sendResp.hasError()) {
            throw new RuntimeException(funcName + " tx failed: " + sendResp.getError().getMessage());
        }
        String txHash = sendResp.getTransactionHash();
        TransactionReceipt receipt = waitForReceipt(txHash);
        if (receipt == null || !receipt.isStatusOK()) {
            throw new RuntimeException(funcName + " receipt not OK: "
                    + (receipt == null ? "null" : receipt.getStatus()));
        }
        return receipt;
    }

    /**
     * 等待交易回执（轮询，最多 40 秒）。
     */
    private TransactionReceipt waitForReceipt(String txHash) throws Exception {
        for (int i = 0; i < 40; i++) {
            var resp = web3j.ethGetTransactionReceipt(txHash).send();
            if (resp.getTransactionReceipt().isPresent()) {
                return resp.getTransactionReceipt().get();
            }
            Thread.sleep(1000);
        }
        return null;
    }

    // ==================== 内部：view 调用 ====================

    /**
     * 执行 view 函数调用（eth_call）。
     */
    private String callViewRaw(Function function) throws Exception {
        String encoded = FunctionEncoder.encode(function);
        Transaction call = Transaction.createEthCallTransaction(
                credentials.getAddress(), contractAddress, encoded);
        EthCall response = web3j.ethCall(call, DefaultBlockParameterName.LATEST).send();
        if (response.hasError()) {
            throw new RuntimeException("eth_call failed: " + response.getError().getMessage());
        }
        return response.getValue();
    }

    /**
     * view 调用并解码为 uint256。
     */
    private BigInteger callViewUint(Function function) throws Exception {
        String result = callViewRaw(function);
        List<Type> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        if (decoded.isEmpty()) {
            return BigInteger.ZERO;
        }
        return ((Uint256) decoded.get(0)).getValue();
    }

    /**
     * view 调用并解码为 string。
     */
    private String callViewString(Function function) throws Exception {
        String result = callViewRaw(function);
        List<Type> decoded = FunctionReturnDecoder.decode(result, function.getOutputParameters());
        if (decoded.isEmpty()) {
            return "";
        }
        return ((Utf8String) decoded.get(0)).getValue();
    }
}