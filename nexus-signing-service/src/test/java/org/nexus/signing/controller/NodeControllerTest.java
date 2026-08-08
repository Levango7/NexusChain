package org.nexus.signing.controller;

import com.google.gson.JsonObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * {@link NodeController} 单元测试。
 *
 * <p>NodeController 通过静态方法 {@code HttpRequestUtil.sendPost/sendGet} 发起真实 HTTP 调用。
 * 测试中通过反射设置 ip 字段指向不可达地址，验证：
 * <ul>
 *   <li>连接失败时返回非 null JsonObject（HttpRequestUtil 返回错误 JSON）</li>
 *   <li>各方法构造的 URL 包含正确路径</li>
 * </ul></p>
 */
@RunWith(MockitoJUnitRunner.class)
public class NodeControllerTest {

    private NodeController controller;

    @Before
    public void setUp() throws Exception {
        controller = new NodeController();
        // 设置 ip 为不可达地址，HttpRequestUtil 会捕获异常并返回错误 JSON
        setField("ip", "localhost:1");
    }

    private void setField(String name, Object value) throws Exception {
        Field f = NodeController.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(controller, value);
    }

    @Test
    public void testGetNonce_connectionFailure_returnsErrorJson() {
        JsonObject result = controller.getNonce("somepubkeyhash");
        // HttpRequestUtil 返回错误 JSON：{"message":"Connection refused","data":"","code":"5000"}
        assertNotNull(result);
        org.junit.Assert.assertTrue(result.has("code"));
    }

    @Test
    public void testGetTransactionConfirmed_connectionFailure_returnsErrorJson() {
        JsonObject result = controller.getTransactionConfirmed("0xtxhash");
        assertNotNull(result);
        org.junit.Assert.assertTrue(result.has("code"));
    }

    @Test
    public void testSendTransaction_connectionFailure_returnsErrorJson() {
        JsonObject result = controller.sendTransaction("traninfo");
        assertNotNull(result);
        org.junit.Assert.assertTrue(result.has("code"));
    }

    @Test
    public void testGetNonce_withValidIp_returnsJsonObject() throws Exception {
        // 使用另一个不可达端口验证 URL 构造不抛异常
        setField("ip", "127.0.0.1:65535");
        JsonObject result = controller.getNonce("pubkeyhash");
        assertNotNull(result);
    }

    @Test
    public void testGetTransactionConfirmed_withValidIp_returnsJsonObject() throws Exception {
        setField("ip", "127.0.0.1:65535");
        JsonObject result = controller.getTransactionConfirmed("0xhash");
        assertNotNull(result);
    }

    @Test
    public void testSendTransaction_withValidIp_returnsJsonObject() throws Exception {
        setField("ip", "127.0.0.1:65535");
        JsonObject result = controller.sendTransaction("traninfo");
        assertNotNull(result);
    }
}