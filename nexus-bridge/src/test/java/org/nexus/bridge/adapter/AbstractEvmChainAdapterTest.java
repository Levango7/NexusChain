package org.nexus.bridge.adapter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;

import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * {@link AbstractEvmChainAdapter} 及其子类单元测试：
 * 覆盖链 ID 查询、区块高度、交易发送、回执查询、合约调用与错误处理。
 */
@ExtendWith(MockitoExtension.class)
class AbstractEvmChainAdapterTest {

    @Mock
    private Web3j web3j;

    @Mock
    private Request<?, EthBlockNumber> ethBlockNumberRequest;
    @Mock
    private Request<?, EthSendTransaction> ethSendRawTxRequest;
    @Mock
    private Request<?, EthGetTransactionReceipt> ethGetTxReceiptRequest;
    @Mock
    private Request<?, EthCall> ethCallRequest;

    /** 测试用适配器子类，允许注入 mock Web3j。 */
    private TestableAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TestableAdapter("0x1", "http://localhost:8545", web3j);
    }

    // ==================== getChainId 测试 ====================

    @Test
    @DisplayName("getChainId 应返回构造时指定的链 ID")
    void getChainId_returnsConfiguredChainId() {
        assertEquals("0x1", adapter.getChainId());
    }

    // ==================== getBlockHeight 测试 ====================

    @Test
    @DisplayName("getBlockHeight: 成功时返回区块高度")
    void getBlockHeight_success() throws IOException {
        EthBlockNumber response = mock(EthBlockNumber.class);
        when(response.getBlockNumber()).thenReturn(BigInteger.valueOf(12345L));
        doReturn(ethBlockNumberRequest).when(web3j).ethBlockNumber();
        when(ethBlockNumberRequest.send()).thenReturn(response);

        long height = adapter.getBlockHeight();

        assertEquals(12345L, height);
    }

    @Test
    @DisplayName("getBlockHeight: IOException 时返回 -1")
    void getBlockHeight_ioExceptionReturnsMinusOne() throws IOException {
        doReturn(ethBlockNumberRequest).when(web3j).ethBlockNumber();
        when(ethBlockNumberRequest.send()).thenThrow(new IOException("connection refused"));

        assertEquals(-1L, adapter.getBlockHeight());
    }

    @Test
    @DisplayName("getBlockHeight: 其他异常时返回 -1")
    void getBlockHeight_otherExceptionReturnsMinusOne() throws IOException {
        doReturn(ethBlockNumberRequest).when(web3j).ethBlockNumber();
        when(ethBlockNumberRequest.send()).thenThrow(new RuntimeException("unexpected"));

        assertEquals(-1L, adapter.getBlockHeight());
    }

    // ==================== sendTransaction 测试 ====================

    @Test
    @DisplayName("sendTransaction: null 字节应抛 IllegalArgumentException")
    void sendTransaction_nullThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(null));
    }

    @Test
    @DisplayName("sendTransaction: 空字节数组应抛 IllegalArgumentException")
    void sendTransaction_emptyThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.sendTransaction(new byte[0]));
    }

    @Test
    @DisplayName("sendTransaction: 成功时返回交易哈希")
    void sendTransaction_success() throws IOException {
        EthSendTransaction response = mock(EthSendTransaction.class);
        when(response.hasError()).thenReturn(false);
        when(response.getTransactionHash()).thenReturn("0xabc123");
        doReturn(ethSendRawTxRequest).when(web3j).ethSendRawTransaction(anyString());
        when(ethSendRawTxRequest.send()).thenReturn(response);

        String hash = adapter.sendTransaction(new byte[]{1, 2, 3, 4});

        assertEquals("0xabc123", hash);
    }

    @Test
    @DisplayName("sendTransaction: 链返回错误时应抛 RuntimeException")
    void sendTransaction_chainErrorThrows() throws IOException {
        EthSendTransaction response = mock(EthSendTransaction.class);
        when(response.hasError()).thenReturn(true);
        Response.Error error = mock(Response.Error.class);
        when(error.getCode()).thenReturn(-32000);
        when(error.getMessage()).thenReturn("nonce too low");
        when(response.getError()).thenReturn(error);
        doReturn(ethSendRawTxRequest).when(web3j).ethSendRawTransaction(anyString());
        when(ethSendRawTxRequest.send()).thenReturn(response);

        assertThrows(RuntimeException.class, () -> adapter.sendTransaction(new byte[]{1}));
    }

    @Test
    @DisplayName("sendTransaction: IOException 时抛 RuntimeException")
    void sendTransaction_ioExceptionThrows() throws IOException {
        doReturn(ethSendRawTxRequest).when(web3j).ethSendRawTransaction(anyString());
        when(ethSendRawTxRequest.send()).thenThrow(new IOException("network error"));

        assertThrows(RuntimeException.class, () -> adapter.sendTransaction(new byte[]{1}));
    }

    // ==================== getTransactionReceipt 测试 ====================

    @Test
    @DisplayName("getTransactionReceipt: null 哈希返回 null")
    void getTransactionReceipt_nullReturnsNull() {
        assertNull(adapter.getTransactionReceipt(null));
    }

    @Test
    @DisplayName("getTransactionReceipt: 空哈希返回 null")
    void getTransactionReceipt_emptyReturnsNull() {
        assertNull(adapter.getTransactionReceipt(""));
    }

    @Test
    @DisplayName("getTransactionReceipt: 成功时返回回执")
    void getTransactionReceipt_success() throws IOException {
        EthGetTransactionReceipt response = mock(EthGetTransactionReceipt.class);
        when(response.hasError()).thenReturn(false);
        TransactionReceipt receipt = mock(TransactionReceipt.class);
        when(response.getTransactionReceipt()).thenReturn(Optional.of(receipt));
        doReturn(ethGetTxReceiptRequest).when(web3j).ethGetTransactionReceipt(anyString());
        when(ethGetTxReceiptRequest.send()).thenReturn(response);

        Object result = adapter.getTransactionReceipt("0xhash");

        assertNotNull(result);
        assertSame(receipt, result);
    }

    @Test
    @DisplayName("getTransactionReceipt: 交易不存在时返回 null")
    void getTransactionReceipt_notFoundReturnsNull() throws IOException {
        EthGetTransactionReceipt response = mock(EthGetTransactionReceipt.class);
        when(response.hasError()).thenReturn(false);
        when(response.getTransactionReceipt()).thenReturn(Optional.empty());
        doReturn(ethGetTxReceiptRequest).when(web3j).ethGetTransactionReceipt(anyString());
        when(ethGetTxReceiptRequest.send()).thenReturn(response);

        assertNull(adapter.getTransactionReceipt("0xhash"));
    }

    @Test
    @DisplayName("getTransactionReceipt: 链返回错误时返回 null")
    void getTransactionReceipt_chainErrorReturnsNull() throws IOException {
        EthGetTransactionReceipt response = mock(EthGetTransactionReceipt.class);
        when(response.hasError()).thenReturn(true);
        Response.Error error = mock(Response.Error.class);
        when(error.getCode()).thenReturn(-1);
        when(error.getMessage()).thenReturn("not found");
        when(response.getError()).thenReturn(error);
        doReturn(ethGetTxReceiptRequest).when(web3j).ethGetTransactionReceipt(anyString());
        when(ethGetTxReceiptRequest.send()).thenReturn(response);

        assertNull(adapter.getTransactionReceipt("0xhash"));
    }

    @Test
    @DisplayName("getTransactionReceipt: IOException 时返回 null")
    void getTransactionReceipt_ioExceptionReturnsNull() throws IOException {
        doReturn(ethGetTxReceiptRequest).when(web3j).ethGetTransactionReceipt(anyString());
        when(ethGetTxReceiptRequest.send()).thenThrow(new IOException("timeout"));

        assertNull(adapter.getTransactionReceipt("0xhash"));
    }


    // ==================== callContract 测试 ====================

    @Test
    @DisplayName("callContract: null 地址应抛 IllegalArgumentException")
    void callContract_nullAddressThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract(null, "0x"));
    }

    @Test
    @DisplayName("callContract: 空地址应抛 IllegalArgumentException")
    void callContract_emptyAddressThrows() {
        assertThrows(IllegalArgumentException.class, () -> adapter.callContract("", "0x"));
    }

    @Test
    @DisplayName("callContract: 成功时返回调用结果")
    void callContract_success() throws IOException {
        EthCall response = mock(EthCall.class);
        when(response.hasError()).thenReturn(false);
        when(response.getValue()).thenReturn("0xresult");
        // 使用 doReturn 避免通配符类型问题
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        when(ethCallRequest.send()).thenReturn(response);

        String result = adapter.callContract("0xcontract", "0xdata");

        assertEquals("0xresult", result);
    }

    @Test
    @DisplayName("callContract: null data 应规范化为 0x")
    void callContract_nullDataNormalized() throws IOException {
        EthCall response = mock(EthCall.class);
        when(response.hasError()).thenReturn(false);
        when(response.getValue()).thenReturn("0x");
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        when(ethCallRequest.send()).thenReturn(response);

        String result = adapter.callContract("0xcontract", null);

        assertEquals("0x", result);
    }

    @Test
    @DisplayName("callContract: 不带 0x 前缀的 data 应自动补全")
    void callContract_dataWithoutPrefix() throws IOException {
        EthCall response = mock(EthCall.class);
        when(response.hasError()).thenReturn(false);
        when(response.getValue()).thenReturn("0xret");
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        when(ethCallRequest.send()).thenReturn(response);

        String result = adapter.callContract("0xcontract", "abcd");

        assertEquals("0xret", result);
    }

    @Test
    @DisplayName("callContract: 链返回错误时返回 null")
    void callContract_chainErrorReturnsNull() throws IOException {
        EthCall response = mock(EthCall.class);
        when(response.hasError()).thenReturn(true);
        Response.Error error = mock(Response.Error.class);
        when(error.getCode()).thenReturn(-1);
        when(error.getMessage()).thenReturn("revert");
        when(response.getError()).thenReturn(error);
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        when(ethCallRequest.send()).thenReturn(response);

        assertNull(adapter.callContract("0xcontract", "0xdata"));
    }

    @Test
    @DisplayName("callContract: IOException 时返回 null")
    void callContract_ioExceptionReturnsNull() throws IOException {
        doReturn(ethCallRequest).when(web3j).ethCall(any(Transaction.class), any(DefaultBlockParameterName.class));
        when(ethCallRequest.send()).thenThrow(new IOException("timeout"));

        assertNull(adapter.callContract("0xcontract", "0xdata"));
    }

    // ==================== shutdown 测试 ====================

    @Test
    @DisplayName("shutdown: 应调用 web3j.shutdown()")
    void shutdown_callsWeb3jShutdown() {
        adapter.shutdown();
        verify(web3j).shutdown();
    }

    // ==================== 子类构造测试 ====================

    @Test
    @DisplayName("EthereumAdapter 应返回默认链 ID 0x1")
    void ethereumAdapter_defaultChainId() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://localhost:8545", "0x1");
        assertEquals("0x1", ethAdapter.getChainId());
    }

    @Test
    @DisplayName("BscAdapter 应返回默认链 ID 0x38")
    void bscAdapter_defaultChainId() {
        BscAdapter bscAdapter = new BscAdapter(
                "http://localhost:8545", "0x38");
        assertEquals("0x38", bscAdapter.getChainId());
    }

    @Test
    @DisplayName("PolygonAdapter 应返回默认链 ID 0x89")
    void polygonAdapter_defaultChainId() {
        PolygonAdapter polygonAdapter = new PolygonAdapter(
                "http://localhost:8545", "0x89");
        assertEquals("0x89", polygonAdapter.getChainId());
    }

    @Test
    @DisplayName("EthereumAdapter: getBlockHeight 在无连接时返回 -1")
    void ethereumAdapter_getBlockHeight_noConnection() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://invalid-localhost:9999", "0x1");
        assertEquals(-1L, ethAdapter.getBlockHeight());
    }

    @Test
    @DisplayName("BscAdapter: getBlockHeight 在无连接时返回 -1")
    void bscAdapter_getBlockHeight_noConnection() {
        BscAdapter bscAdapter = new BscAdapter(
                "http://invalid-localhost:9999", "0x38");
        assertEquals(-1L, bscAdapter.getBlockHeight());
    }

    @Test
    @DisplayName("PolygonAdapter: getBlockHeight 在无连接时返回 -1")
    void polygonAdapter_getBlockHeight_noConnection() {
        PolygonAdapter polygonAdapter = new PolygonAdapter(
                "http://invalid-localhost:9999", "0x89");
        assertEquals(-1L, polygonAdapter.getBlockHeight());
    }

    @Test
    @DisplayName("EthereumAdapter: callContract 在无连接时返回 null")
    void ethereumAdapter_callContract_noConnection() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://invalid-localhost:9999", "0x1");
        assertNull(ethAdapter.callContract("0xcontract", "0xdata"));
    }

    @Test
    @DisplayName("EthereumAdapter: getTransactionReceipt 在无连接时返回 null")
    void ethereumAdapter_getTransactionReceipt_noConnection() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://invalid-localhost:9999", "0x1");
        assertNull(ethAdapter.getTransactionReceipt("0xhash"));
    }

    @Test
    @DisplayName("EthereumAdapter: sendTransaction 在无连接时抛 RuntimeException")
    void ethereumAdapter_sendTransaction_noConnection() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://invalid-localhost:9999", "0x1");
        assertThrows(RuntimeException.class,
                () -> ethAdapter.sendTransaction(new byte[]{1, 2, 3}));
    }

    @Test
    @DisplayName("EthereumAdapter: shutdown 不抛异常")
    void ethereumAdapter_shutdown_noException() {
        EthereumAdapter ethAdapter = new EthereumAdapter(
                "http://localhost:8545", "0x1");
        assertDoesNotThrow(ethAdapter::shutdown);
    }

    @Test
    @DisplayName("BscAdapter: callContract 在无连接时返回 null")
    void bscAdapter_callContract_noConnection() {
        BscAdapter bscAdapter = new BscAdapter(
                "http://invalid-localhost:9999", "0x38");
        assertNull(bscAdapter.callContract("0xcontract", "0xdata"));
    }

    @Test
    @DisplayName("PolygonAdapter: callContract 在无连接时返回 null")
    void polygonAdapter_callContract_noConnection() {
        PolygonAdapter polygonAdapter = new PolygonAdapter(
                "http://invalid-localhost:9999", "0x89");
        assertNull(polygonAdapter.callContract("0xcontract", "0xdata"));
    }

    @Test
    @DisplayName("BscAdapter: shutdown 不抛异常")
    void bscAdapter_shutdown_noException() {
        BscAdapter bscAdapter = new BscAdapter(
                "http://localhost:8545", "0x38");
        assertDoesNotThrow(bscAdapter::shutdown);
    }

    @Test
    @DisplayName("PolygonAdapter: shutdown 不抛异常")
    void polygonAdapter_shutdown_noException() {
        PolygonAdapter polygonAdapter = new PolygonAdapter(
                "http://localhost:8545", "0x89");
        assertDoesNotThrow(polygonAdapter::shutdown);
    }

    /**
     * 测试用适配器子类：允许通过反射注入 mock Web3j。
     */
    static class TestableAdapter extends AbstractEvmChainAdapter {
        TestableAdapter(String chainId, String rpcEndpoint, Web3j mockWeb3j) {
            super(chainId, rpcEndpoint);
            try {
                java.lang.reflect.Field field = AbstractEvmChainAdapter.class.getDeclaredField("web3j");
                field.setAccessible(true);
                field.set(this, mockWeb3j);
            } catch (Exception e) {
                throw new RuntimeException("Failed to inject mock Web3j", e);
            }
        }
    }
}
