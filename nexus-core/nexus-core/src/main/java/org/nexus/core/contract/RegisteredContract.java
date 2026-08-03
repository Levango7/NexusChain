package org.nexus.core.contract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

/**
 * 已注册合约的元数据值对象（不可变）。
 *
 * <p>承载合约地址、ABI、名称、创建块高、所属链标识、创建时间、状态、创建者、
 * codeHash、wasmCode 等字段。字段全部 final，仅构造时注入，无 setter。
 * Jackson 友好：通过 {@link JsonCreator} + {@link JsonProperty} 支持反序列化，
 * 通过 getter 支持序列化（与 {@code JsonRpcController.MAPPER} 及
 * {@code JSONEncodeDecoder} 均兼容）。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code address}：合约地址（hex，0x 前缀，主键）</li>
 *   <li>{@code name}：合约名称（人类可读，如 "PaymentChannel"）</li>
 *   <li>{@code abi}：ABI 的 JSON 字符串（详情页方法签名展示，由调用方解析为 JSON 节点）</li>
 *   <li>{@code codeHash}：wasmCode 的 hash（hex，列表页展示）</li>
 *   <li>{@code wasmCode}：WASM 字节码 hex（详情页展示，列表页省略）</li>
 *   <li>{@code creator}：部署者地址（hex）</li>
 *   <li>{@code creationBlock}：创建所在区块高度</li>
 *   <li>{@code createdAt}：注册时间戳（Unix 秒）</li>
 *   <li>{@code chainId}：所属链标识（与 {@code nexus.chain-id} 对齐）</li>
 *   <li>{@code status}：{@link ContractStatus}，默认 {@link ContractStatus#ACTIVE}</li>
 * </ul>
 *
 * @author nexus-core
 * @since 1.0
 */
public final class RegisteredContract {

    private final String address;
    private final String name;
    private final String abi;
    private final String codeHash;
    private final String wasmCode;
    private final String creator;
    private final long creationBlock;
    private final long createdAt;
    private final int chainId;
    private final ContractStatus status;

    @JsonCreator
    public RegisteredContract(
            @JsonProperty("address") String address,
            @JsonProperty("name") String name,
            @JsonProperty("abi") String abi,
            @JsonProperty("codeHash") String codeHash,
            @JsonProperty("wasmCode") String wasmCode,
            @JsonProperty("creator") String creator,
            @JsonProperty("creationBlock") long creationBlock,
            @JsonProperty("createdAt") long createdAt,
            @JsonProperty("chainId") int chainId,
            @JsonProperty("status") ContractStatus status) {
        this.address = address;
        this.name = name;
        this.abi = abi;
        this.codeHash = codeHash;
        this.wasmCode = wasmCode;
        this.creator = creator;
        this.creationBlock = creationBlock;
        this.createdAt = createdAt;
        this.chainId = chainId;
        this.status = status == null ? ContractStatus.ACTIVE : status;
    }

    public String getAddress() {
        return address;
    }

    public String getName() {
        return name;
    }

    public String getAbi() {
        return abi;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public String getWasmCode() {
        return wasmCode;
    }

    public String getCreator() {
        return creator;
    }

    public long getCreationBlock() {
        return creationBlock;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getChainId() {
        return chainId;
    }

    public ContractStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RegisteredContract)) return false;
        RegisteredContract that = (RegisteredContract) o;
        return Objects.equals(address, that.address);
    }

    @Override
    public int hashCode() {
        return address == null ? 0 : address.hashCode();
    }

    @Override
    public String toString() {
        return "RegisteredContract{address='" + address + "', name='" + name
                + "', codeHash='" + codeHash + "', creationBlock=" + creationBlock
                + ", chainId=" + chainId + ", status=" + status + '}';
    }
}