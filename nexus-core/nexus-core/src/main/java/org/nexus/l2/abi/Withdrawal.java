package org.nexus.l2.abi;

import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.generated.Uint256;

import java.math.BigInteger;

/**
 * L2Bridge.Withdrawal 结构体的 Web3j 类型映射。
 *
 * <p>对应 Solidity 合约 {@code L2Bridge.sol} 中的 Withdrawal 结构体：</p>
 * <pre>
 * struct Withdrawal {
 *     address token;      // ERC20 代币地址
 *     address recipient;  // 收款人地址
 *     uint256 amount;     // 提款金额
 * }
 * </pre>
 *
 * <h2>ABI 编码说明</h2>
 * <p>Withdrawal 结构体的三个字段均为静态类型（address 编码为 32 字节，
 * uint256 为 32 字节），因此整体为静态结构体（{@link StaticStruct}）。
 * 当作为 {@code Withdrawal[]} 动态数组元素时，使用
 * {@code DynamicArray<Withdrawal>} 编码，Web3j 4.11.0 的
 * {@code TypeEncoder.encodeDynamicArray} 支持结构体数组的 head/tail 编码。</p>
 *
 * <h2>使用示例</h2>
 * <pre>
 * List&lt;Withdrawal&gt; withdrawals = Arrays.asList(
 *     new Withdrawal("0xToken...", "0xRecipient...", BigInteger.valueOf(100)),
 *     new Withdrawal("0xToken...", "0xRecipient2...", BigInteger.valueOf(200))
 * );
 * DynamicArray&lt;Withdrawal&gt; array = new DynamicArray&lt;&gt;(Withdrawal.class, withdrawals);
 * </pre>
 *
 * @since 2.3
 */
public class Withdrawal extends StaticStruct {

    /**
     * 通过 Web3j 原生类型构造 Withdrawal 结构体。
     *
     * <p>字段顺序必须与 Solidity 定义一致：token, recipient, amount。</p>
     *
     * @param token     ERC20 代币地址（Web3j {@link Address}）
     * @param recipient 收款人地址（Web3j {@link Address}）
     * @param amount    提款金额（Web3j {@link Uint256}）
     */
    public Withdrawal(Address token, Address recipient, Uint256 amount) {
        super(token, recipient, amount);
    }

    /**
     * 通过原生 Java 类型构造 Withdrawal 结构体（便捷构造器）。
     *
     * @param token     ERC20 代币地址（hex，0x 前缀，20 字节）
     * @param recipient 收款人地址（hex，0x 前缀，20 字节）
     * @param amount    提款金额（wei，非负）
     */
    public Withdrawal(String token, String recipient, BigInteger amount) {
        super(new Address(token), new Address(recipient), new Uint256(amount));
    }
}