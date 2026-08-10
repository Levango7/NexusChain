package org.nexus.core.contract;

import org.nexus.db.Leveldb;
import org.nexus.encoding.JSONEncodeDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ContractRegistry 单元测试。
 *
 * <p>使用真实 Leveldb（临时目录）+ 真实 JSONEncodeDecoder，验证 register/get/list/exists
 * 的内存行为与 LevelDB 持久化/启动加载往返。不依赖 Spring 容器，通过反射注入 private 字段。</p>
 *
 * @author nexus-core
 * @since 1.0
 */
public class ContractRegistryTest {

    private Path tempDir;
    private Leveldb leveldb;
    private JSONEncodeDecoder codec;

    @BeforeEach
    public void setUp() throws Exception {
        tempDir = Files.createTempDirectory("contract-test-");
        // clearCache=true 确保每个测试方法起始为空 DB
        leveldb = new Leveldb(tempDir.toString(), true);
        codec = new JSONEncodeDecoder();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // 先关闭 LevelDB 释放 Windows 文件锁，再删除临时目录
        if (leveldb != null) {
            try {
                leveldb.destroy();
            } catch (Exception ignored) {
            }
        }
        if (tempDir != null && Files.exists(tempDir)) {
            deleteRecursively(tempDir);
        }
    }

    private ContractRegistry newRegistry(int maxListSize) throws Exception {
        // 反射注入不会触发 @PostConstruct，需手动打开 LevelDB
        leveldb.init();
        ContractRegistry r = new ContractRegistry();
        setField(r, "leveldb", leveldb);
        setField(r, "codec", codec);
        setField(r, "enabled", true);
        setField(r, "maxListSize", maxListSize);
        r.init();
        return r;
    }

    private RegisteredContract sample(String address, long createdAt) {
        return new RegisteredContract(
                address, "Sample", "[]", "0xabc", "0x0061736d",
                "0xcreator", 1L, createdAt, 1, ContractStatus.ACTIVE);
    }

    // ==================== register + getByAddress ====================

    @Test
    public void testRegisterAndGetByAddress() throws Exception {
        ContractRegistry r = newRegistry(100);
        RegisteredContract rc = sample("0x1a2b", 1000L);
        assertTrue(r.register(rc), "首次注册应返回 true");
        assertEquals(1, r.size(), "size 应为 1");

        RegisteredContract got = r.getByAddress("0x1a2b");
        assertNotNull(got, "已注册地址应能查到");
        assertEquals("0x1a2b", got.getAddress(), "address 字段往返一致");
        assertEquals("Sample", got.getName(), "name 字段往返一致");
        assertEquals("[]", got.getAbi(), "abi 字段往返一致");
        assertEquals("0xabc", got.getCodeHash(), "codeHash 字段往返一致");
        assertEquals("0x0061736d", got.getWasmCode(), "wasmCode 字段往返一致");
        assertEquals("0xcreator", got.getCreator(), "creator 字段往返一致");
        assertEquals(1L, got.getCreationBlock(), "creationBlock 字段往返一致");
        assertEquals(1000L, got.getCreatedAt(), "createdAt 字段往返一致");
        assertEquals(1, got.getChainId(), "chainId 字段往返一致");
        assertEquals(ContractStatus.ACTIVE, got.getStatus(), "status 字段往返一致");
    }

    @Test
    public void testGetByAddressNotFound() throws Exception {
        ContractRegistry r = newRegistry(100);
        assertNull(r.getByAddress("0xdead"), "未注册地址返回 null");
    }

    @Test
    public void testGetByAddressNull() throws Exception {
        ContractRegistry r = newRegistry(100);
        assertNull(r.getByAddress(null), "null 地址返回 null");
    }

    // ==================== exists ====================

    @Test
    public void testExists() throws Exception {
        ContractRegistry r = newRegistry(100);
        assertFalse(r.exists("0x1a2b"), "未注册地址 exists=false");
        r.register(sample("0x1a2b", 1000L));
        assertTrue(r.exists("0x1a2b"), "已注册地址 exists=true");
        assertFalse(r.exists(null), "null 地址 exists=false");
    }

    // ==================== register 重复/异常 ====================

    @Test
    public void testRegisterDuplicateReturnsFalse() throws Exception {
        ContractRegistry r = newRegistry(100);
        assertTrue(r.register(sample("0x1a2b", 1000L)));
        assertFalse(r.register(sample("0x1a2b", 2000L)), "重复注册同地址返回 false");
        assertEquals(1, r.size(), "重复注册不增加条目");
        // 保留首次注册的记录
        assertEquals(1000L, r.getByAddress("0x1a2b").getCreatedAt(), "保留首次注册的 createdAt");
    }

    @Test
    public void testRegisterNullContractThrows() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            ContractRegistry r = newRegistry(100);
            r.register(null);
        });
    }

    @Test
    public void testRegisterNullAddressThrows() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> {
            ContractRegistry r = newRegistry(100);
            r.register(new RegisteredContract(
                    null, "n", "[]", "0x", "0x", "0x", 1L, 1L, 1, ContractStatus.ACTIVE));
        });
    }

    // ==================== list 排序 + 分页 ====================

    @Test
    public void testListSortedByCreatedAtDesc() throws Exception {
        ContractRegistry r = newRegistry(100);
        r.register(sample("0xa1", 100L));
        r.register(sample("0xa2", 300L));
        r.register(sample("0xa3", 200L));
        List<RegisteredContract> list = r.list(0, 10);
        assertEquals(3, list.size(), "应返回 3 条");
        assertEquals(300L, list.get(0).getCreatedAt(), "倒序：第一项 createdAt=300");
        assertEquals(200L, list.get(1).getCreatedAt(), "倒序：第二项 createdAt=200");
        assertEquals(100L, list.get(2).getCreatedAt(), "倒序：第三项 createdAt=100");
    }

    @Test
    public void testListPagination() throws Exception {
        ContractRegistry r = newRegistry(100);
        for (int i = 0; i < 5; i++) {
            r.register(sample("0x" + Integer.toHexString(i), 100L + i));
        }
        // createdAt: 100,101,102,103,104 → 倒序: 104,103,102,101,100
        List<RegisteredContract> page1 = r.list(0, 2);
        assertEquals(2, page1.size(), "page1 大小 2");
        assertEquals(104L, page1.get(0).getCreatedAt(), "page1[0] createdAt=104");
        assertEquals(103L, page1.get(1).getCreatedAt(), "page1[1] createdAt=103");

        List<RegisteredContract> page2 = r.list(2, 2);
        assertEquals(2, page2.size(), "page2 大小 2");
        assertEquals(102L, page2.get(0).getCreatedAt(), "page2[0] createdAt=102");
        assertEquals(101L, page2.get(1).getCreatedAt(), "page2[1] createdAt=101");

        List<RegisteredContract> page3 = r.list(4, 2);
        assertEquals(1, page3.size(), "page3 大小 1（末页不足）");
        assertEquals(100L, page3.get(0).getCreatedAt(), "page3[0] createdAt=100");
    }

    @Test
    public void testListOffsetBeyondSizeReturnsEmpty() throws Exception {
        ContractRegistry r = newRegistry(100);
        r.register(sample("0xa1", 100L));
        assertTrue(r.list(10, 5).isEmpty(), "offset 超出 size 返回空");
    }

    @Test
    public void testListLimitClampedToMax() throws Exception {
        ContractRegistry r = newRegistry(3);
        for (int i = 0; i < 5; i++) {
            r.register(sample("0x" + Integer.toHexString(i), 100L + i));
        }
        // limit=10 > maxListSize=3 → 夹逼到 3
        List<RegisteredContract> list = r.list(0, 10);
        assertEquals(3, list.size(), "limit 超过 maxListSize 时夹逼到 3");
    }

    @Test
    public void testListNegativeLimitClamped() throws Exception {
        ContractRegistry r = newRegistry(100);
        r.register(sample("0xa1", 100L));
        // limit=0 → 夹逼到 maxListSize=100
        List<RegisteredContract> list = r.list(0, 0);
        assertEquals(1, list.size(), "limit<=0 时夹逼到 maxListSize");
    }

    @Test
    public void testListNegativeOffsetClamped() throws Exception {
        ContractRegistry r = newRegistry(100);
        r.register(sample("0xa1", 100L));
        List<RegisteredContract> list = r.list(-5, 10);
        assertEquals(1, list.size(), "offset<0 视为 0");
    }

    @Test
    public void testListEmptyRegistry() throws Exception {
        ContractRegistry r = newRegistry(100);
        assertTrue(r.list(0, 10).isEmpty(), "空注册表 list 返回空");
    }

    @Test
    public void testListReturnsSnapshotCopy() throws Exception {
        ContractRegistry r = newRegistry(100);
        r.register(sample("0xa1", 100L));
        List<RegisteredContract> list = r.list(0, 10);
        // 修改返回的 list 不应影响 registry
        list.clear();
        assertEquals(1, r.size(), "修改快照不影响 registry");
    }

    // ==================== LevelDB 持久化往返（重启加载）====================

    @Test
    public void testRestartLoadFromLevelDb() throws Exception {
        ContractRegistry r1 = newRegistry(100);
        r1.register(sample("0x1a2b", 1000L));
        r1.register(new RegisteredContract(
                "0x3c4d", "PaymentChannel",
                "[{\"type\":\"function\",\"name\":\"open\"}]",
                "0xdead", "0x0061736d01", "0x9f8e", 42L, 2000L, 1, ContractStatus.ACTIVE));
        assertEquals(2, r1.size(), "r1 注册 2 个");

        // 模拟重启：先关闭旧 Leveldb 实例（释放 Windows 文件锁），再新建复用同一目录
        leveldb.destroy();
        leveldb = new Leveldb(tempDir.toString(), false);
        ContractRegistry r2 = newRegistry(100);

        assertEquals(2, r2.size(), "重启后加载到 2 个");
        RegisteredContract got1 = r2.getByAddress("0x1a2b");
        assertNotNull(got1, "重启后 0x1a2b 命中");
        assertEquals("Sample", got1.getName(), "重启后 0x1a2b name 一致");
        assertEquals(1000L, got1.getCreatedAt(), "重启后 0x1a2b createdAt 一致");

        RegisteredContract got2 = r2.getByAddress("0x3c4d");
        assertNotNull(got2, "重启后 0x3c4d 命中");
        assertEquals("PaymentChannel", got2.getName(), "重启后 0x3c4d name 一致");
        assertEquals("[{\"type\":\"function\",\"name\":\"open\"}]", got2.getAbi(), "重启后 0x3c4d abi 一致");
        assertEquals("0x0061736d01", got2.getWasmCode(), "重启后 0x3c4d wasmCode 一致");
        assertEquals(42L, got2.getCreationBlock(), "重启后 0x3c4d creationBlock 一致");
    }

    @Test
    public void testRestartEmptyWhenNoPersistedData() throws Exception {
        // 空 DB 启动加载
        ContractRegistry r = newRegistry(100);
        assertEquals(0, r.size(), "空 DB 启动后 size=0");
        assertTrue(r.list(0, 10).isEmpty(), "空 DB 启动后 list 为空");
    }

    // ==================== registry-enabled=false ====================

    @Test
    public void testDisabledRegistrySkipsLoading() throws Exception {
        // 先写入一些数据
        ContractRegistry r1 = newRegistry(100);
        r1.register(sample("0x1a2b", 1000L));
        assertEquals(1, r1.size());

        // 重启但 enabled=false，应跳过加载
        leveldb.destroy();
        leveldb = new Leveldb(tempDir.toString(), false);
        leveldb.init();
        ContractRegistry r2 = new ContractRegistry();
        setField(r2, "leveldb", leveldb);
        setField(r2, "codec", codec);
        setField(r2, "enabled", false);
        setField(r2, "maxListSize", 100);
        r2.init();
        assertEquals(0, r2.size(), "enabled=false 时跳过加载，size=0");
    }

    // ==================== 辅助方法 ====================

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static void deleteRecursively(Path path) throws Exception {
        List<Path> paths = new ArrayList<>();
        java.nio.file.Files.walk(path).forEach(paths::add);
        paths.sort(Comparator.reverseOrder());
        for (Path p : paths) {
            java.nio.file.Files.deleteIfExists(p);
        }
    }
}
