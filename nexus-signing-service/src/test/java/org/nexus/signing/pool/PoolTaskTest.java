package org.nexus.signing.pool;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.nexus.signing.controller.NodeController;
import org.nexus.signing.storage.Leveldb;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PoolTask} 单元测试。
 *
 * <p>覆盖定时清理任务逻辑：
 * <ul>
 *   <li>空 Nonce 池 → task 不做任何操作</li>
 *   <li>RPC getNonce 返回 code=2000 且 nownonce >= firstkey → 移除 firstkey</li>
 *   <li>RPC getNonce 返回 code=2000 且 nownonce < firstkey → 检查 txHash 确认状态</li>
 *   <li>RPC getNonce 返回非 2000 → 不移除</li>
 *   <li>NonceState 为 null → 移除 firstkey</li>
 * </ul></p>
 *
 * <p>注：{@code WalletUtils.addressToPubkeyHash} 是静态方法，无法 mock。
 * 测试使用真实地址字符串，RPC mock 会拦截具体调用。</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class PoolTaskTest {

    @Mock
    private NoncePool noncePool;

    @Mock
    private NodeController nodeController;

    @Mock
    private Leveldb leveldb;

    private PoolTask poolTask;

    @Before
    public void setUp() throws Exception {
        when(leveldb.readFromSnapshot()).thenReturn("");
        doNothing().when(leveldb).addPoolDb(anyString());
        // 使用真实 NoncePool 以便 add/remove 操作真实 TreeMap
        NoncePool realNoncePool = new NoncePool(leveldb);
        poolTask = new PoolTask(realNoncePool, nodeController);
    }

    @Test
    public void testTask_emptyPool_noOp() throws IOException {
        poolTask.task();
        verify(nodeController, never()).getNonce(anyString());
    }

    @Test
    public void testTask_rpcNonceGreaterThanOrEqualFirstKey_removesFirstKey() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 5L, 1000L));

        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 2000);
        getNonceResp.addProperty("data", 5L);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);

        poolTask.task();

        // firstkey=5 被移除后池应为空
        org.junit.Assert.assertEquals(0L, realNoncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testTask_rpcNonceLessThanFirstKey_checksTxConfirmation() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 10L, 1000L));

        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 2000);
        getNonceResp.addProperty("data", 3L);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);

        JsonObject getTxResp = new JsonObject();
        getTxResp.addProperty("code", 2000);
        when(nodeController.getTransactionConfirmed(anyString())).thenReturn(getTxResp);

        poolTask.task();

        // tx 已确认 → firstkey=10 被移除
        org.junit.Assert.assertEquals(0L, realNoncePool.getMaxNonce("addr1"));
        verify(nodeController).getTransactionConfirmed("0xabc");
    }

    @Test
    public void testTask_rpcNonceLessThanFirstKey_txNotConfirmed_keepsEntry() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 10L, 1000L));

        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 2000);
        getNonceResp.addProperty("data", 3L);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);

        JsonObject getTxResp = new JsonObject();
        getTxResp.addProperty("code", 5000);
        when(nodeController.getTransactionConfirmed(anyString())).thenReturn(getTxResp);

        poolTask.task();

        // tx 未确认 → 保留
        org.junit.Assert.assertEquals(10L, realNoncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testTask_rpcGetNonceNon2000_keepsEntry() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 5L, 1000L));

        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 5000);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);

        poolTask.task();

        org.junit.Assert.assertEquals(5L, realNoncePool.getMaxNonce("addr1"));
        verify(nodeController, never()).getTransactionConfirmed(anyString());
    }

    @Test
    public void testTask_rpcGetNonceNull_keepsEntry() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 5L, 1000L));

        when(nodeController.getNonce(anyString())).thenReturn(null);

        poolTask.task();

        org.junit.Assert.assertEquals(5L, realNoncePool.getMaxNonce("addr1"));
    }

    @Test
    public void testTask_multipleAddresses_processesAll() throws IOException {
        NoncePool realNoncePool = poolTask.noncePool;
        realNoncePool.add("addr1", new NonceState("0xabc", 5L, 1000L));
        realNoncePool.add("addr2", new NonceState("0xdef", 10L, 2000L));

        JsonObject getNonceResp = new JsonObject();
        getNonceResp.addProperty("code", 2000);
        getNonceResp.addProperty("data", 100L);
        when(nodeController.getNonce(anyString())).thenReturn(getNonceResp);

        poolTask.task();

        org.junit.Assert.assertEquals(0L, realNoncePool.getMaxNonce("addr1"));
        org.junit.Assert.assertEquals(0L, realNoncePool.getMaxNonce("addr2"));
        verify(nodeController, atLeastOnce()).getNonce(anyString());
    }

    @Test
    public void testPoolTaskConstructor_injectsDependencies() {
        assertNotNull(poolTask.noncePool);
        assertNotNull(poolTask.nodeController);
    }
}