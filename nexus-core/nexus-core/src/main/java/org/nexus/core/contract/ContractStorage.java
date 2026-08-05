package org.nexus.core.contract;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约存储：每个合约地址一份 KV 存储（slot → value）。
 *
 * <p>当前为进程内内存实现，供 {@code EvmExecutor} 的 SLOAD / SSTORE 使用。
 * 重启后丢失——生产实现需落盘到状态树 / LevelDB（TODO: state tree integration）。</p>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 双层结构。</p>
 *
 * @since 1.2
 */
@Component
public class ContractStorage {

    /** address → (slot → 32 字节值) */
    private final Map<String, Map<BigInteger, byte[]>> storageByAddress = new ConcurrentHashMap<>();

    /**
     * 读取存储槽。
     *
     * @param address 合约地址
     * @param slot    存储槽位
     * @return 32 字节值；未写入返回 32 字节零
     */
    public byte[] read(String address, BigInteger slot) {
        Map<BigInteger, byte[]> slots = storageByAddress.get(address);
        if (slots == null) {
            return new byte[32];
        }
        byte[] value = slots.get(slot);
        return value != null ? value : new byte[32];
    }

    /**
     * 写入存储槽。值为全零时删除槽位（与 EVM 语义一致）。
     *
     * @param address 合约地址
     * @param slot    存储槽位
     * @param value   32 字节值
     */
    public void write(String address, BigInteger slot, byte[] value) {
        if (address == null || slot == null || value == null) {
            return;
        }
        Map<BigInteger, byte[]> slots = storageByAddress.computeIfAbsent(
                address, k -> new ConcurrentHashMap<>());
        if (isAllZero(value)) {
            slots.remove(slot);
        } else {
            slots.put(slot, value.clone());
        }
    }

    /**
     * 清空指定合约的全部存储（测试用）。
     *
     * @param address 合约地址
     */
    public void clear(String address) {
        storageByAddress.remove(address);
    }

    /**
     * 获取指定合约当前存储槽的快照副本（供解释器执行使用）。
     *
     * <p>返回可变副本；调用方执行后可通过 {@link #writeBack} 写回变更。</p>
     *
     * @param address 合约地址
     * @return slot → 32 字节值 的可变副本，无存储时返回空 map
     */
    public Map<BigInteger, byte[]> snapshot(String address) {
        Map<BigInteger, byte[]> slots = storageByAddress.get(address);
        Map<BigInteger, byte[]> copy = new java.util.HashMap<>();
        if (slots != null) {
            for (Map.Entry<BigInteger, byte[]> e : slots.entrySet()) {
                copy.put(e.getKey(), e.getValue().clone());
            }
        }
        return copy;
    }

    /**
     * 将解释器执行后的存储快照写回（覆盖该地址的全部槽位）。
     *
     * @param address  合约地址
     * @param snapshot 解释器执行后的存储快照
     */
    public void writeBack(String address, Map<BigInteger, byte[]> snapshot) {
        if (address == null || snapshot == null) {
            return;
        }
        storageByAddress.remove(address);
        for (Map.Entry<BigInteger, byte[]> e : snapshot.entrySet()) {
            write(address, e.getKey(), e.getValue());
        }
    }

    private boolean isAllZero(byte[] value) {
        for (byte b : value) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }
}
