package org.nexus.core.contract;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.nexus.core.persist.StateSnapshotPersister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合约存储：每个合约地址一份 KV 存储（slot → value）。
 *
 * <p>供 {@code EvmExecutor} 的 SLOAD / SSTORE 使用。底层为进程内
 * {@link ConcurrentHashMap} 双层结构，通过 JSON 快照在重启后恢复状态。</p>
 *
 * <h3>持久化</h3>
 * <ul>
 *   <li>{@code @PostConstruct}：从 {@code contract-storage-snapshot.json} 加载。</li>
 *   <li>{@code @PreDestroy}：保存到同文件（原子写入）。</li>
 *   <li>{@code byte[]} 值经 Base64 编码为字符串后序列化；{@code BigInteger} 槽位以十进制字符串为 key。</li>
 *   <li>加载 / 保存失败均不阻塞启动 / 关闭（告警 + 继续空内存）。</li>
 * </ul>
 *
 * <p>线程安全：{@link ConcurrentHashMap} 双层结构。</p>
 *
 * @since 1.2
 */
@Component
public class ContractStorage {

    private static final Logger logger = LoggerFactory.getLogger(ContractStorage.class);

    private static final String SNAPSHOT_FILE = "contract-storage-snapshot.json";

    /** address → (slot → 32 字节值) */
    private final Map<String, Map<BigInteger, byte[]>> storageByAddress = new ConcurrentHashMap<>();

    @Autowired
    private StateSnapshotPersister persister;

    /**
     * 启动时从快照恢复合约存储。
     *
     * <p>快照格式：{@code Map<String, Map<String, String>>}，即
     * address → (slotDecStr → base64Value)。文件不存在或解析失败时保持空内存。</p>
     */
    @PostConstruct
    void loadSnapshot() {
        if (persister == null) {
            return;
        }
        Map<String, Map<String, String>> snapshot = persister.load(
                SNAPSHOT_FILE,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Map<String, String>>>() {
                });
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        int addressCount = 0;
        int slotCount = 0;
        for (Map.Entry<String, Map<String, String>> addrEntry : snapshot.entrySet()) {
            String address = addrEntry.getKey();
            if (address == null || addrEntry.getValue() == null) {
                continue;
            }
            Map<BigInteger, byte[]> slots = new ConcurrentHashMap<>();
            for (Map.Entry<String, String> slotEntry : addrEntry.getValue().entrySet()) {
                try {
                    BigInteger slot = new BigInteger(slotEntry.getKey());
                    byte[] value = Base64.getDecoder().decode(slotEntry.getValue());
                    slots.put(slot, value);
                    slotCount++;
                } catch (Exception e) {
                    logger.warn("Skip invalid slot entry in snapshot: address={}, slot={}, reason={}",
                            address, slotEntry.getKey(), e.getMessage());
                }
            }
            if (!slots.isEmpty()) {
                storageByAddress.put(address, slots);
                addressCount++;
            }
        }
        logger.info("Contract storage snapshot loaded: {} addresses, {} slots", addressCount, slotCount);
    }

    /**
     * 关闭时保存合约存储到快照。
     *
     * <p>保存失败仅告警，不阻塞关闭。</p>
     */
    @PreDestroy
    void saveSnapshot() {
        if (persister == null || !persister.isEnabled() || storageByAddress.isEmpty()) {
            return;
        }
        Map<String, Map<String, String>> snapshot = new HashMap<>();
        Base64.Encoder encoder = Base64.getEncoder();
        for (Map.Entry<String, Map<BigInteger, byte[]>> addrEntry : storageByAddress.entrySet()) {
            Map<String, String> slots = new HashMap<>();
            for (Map.Entry<BigInteger, byte[]> slotEntry : addrEntry.getValue().entrySet()) {
                slots.put(slotEntry.getKey().toString(), encoder.encodeToString(slotEntry.getValue()));
            }
            if (!slots.isEmpty()) {
                snapshot.put(addrEntry.getKey(), slots);
            }
        }
        persister.save(SNAPSHOT_FILE, snapshot);
    }

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
