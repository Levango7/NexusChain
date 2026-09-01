// Package nexus is the NexusChain SDK for Go.
//
// v2.2.0 三语言补真（2026-09-01）：本包按 nexus-core JsonRpcController 的
// 真实 RPC 契约实现（15 个已核实方法，信封为十进制字符串/数值而非 0x hex），
// 替代旧 conpay 骨架（其 Broadcast 调用不存在的 nexus_sendRawTransaction、
// hex 解析假设与真实信封不符——见 conpay/STATUS.md）。
//
// 能力面：
//   - 链查询：区块高度/区块详情/节点状态/链 ID
//   - 钱包查询：余额（nexus_getBalance {"balance":"<decimal>"} 信封）、
//     nonce（nexus_getTransactionCount {"count":N}）、交易历史
//   - 交易构建与提交：BuildTransfer（真实信封）；Submit 走 wallet-service
//     HTTP（core JSON-RPC 无 sendRawTransaction——架构决策：交易签名/密钥
//     管控集中在 wallet-service，SDK 不持私钥）
//   - 跨链桥：nexus_getCrossChainTransactions（近 200 区块 BRIDGE_* 推导）
//   - 地址校验：Base58 + 21 字节 + keccak256 双哈希校验尾（对齐 Java
//     KeystoreAction.verifyAddress 语义）
//
// Usage:
//
//	client, err := nexus.NewClient(nexus.Config{RPCUrl: "http://127.0.0.1:19585/rpc"})
//	balance, err := client.Wallet.GetBalance("1L3zk...")
package nexus

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Config holds the NexusChain client configuration.
type Config struct {
	// Network is the network label ("mainnet"/"testnet"), informational only.
	Network string
	// RPCUrl is the nexus-core JSON-RPC endpoint (e.g. http://127.0.0.1:19585/rpc).
	RPCUrl string
	// WalletServiceURL is the nexus-wallet-service base URL for transaction
	// submission (required for Wallet.Submit / Transaction.Submit).
	WalletServiceURL string
	// Timeout is the HTTP timeout in milliseconds. Default 30000.
	Timeout int
	// APIKey is an optional bearer token.
	APIKey string
}

// Client is the NexusChain SDK entry point.
type Client struct {
	config     *Config
	httpClient *http.Client
	requestID  int64

	Wallet      *WalletManager
	Transaction *TransactionManager
	Bridge      *BridgeManager
}

// NewClient creates a Client. RPCUrl is required; WalletServiceURL is optional
// (only needed for transaction submission).
func NewClient(config *Config) (*Client, error) {
	if config == nil || config.RPCUrl == "" {
		return nil, fmt.Errorf("nexus: Config.RPCUrl is required")
	}
	if config.Timeout == 0 {
		config.Timeout = 30000
	}
	c := &Client{
		config:     config,
		httpClient: &http.Client{Timeout: time.Duration(config.Timeout) * time.Millisecond},
	}
	c.Wallet = &WalletManager{client: c}
	c.Transaction = &TransactionManager{client: c}
	c.Bridge = &BridgeManager{client: c}
	return c, nil
}

// Network returns the configured network label.
func (c *Client) Network() string { return c.config.Network }

// RPCUrl returns the RPC endpoint.
func (c *Client) RPCUrl() string { return c.config.RPCUrl }

// RPCCall sends one JSON-RPC request to nexus-core and returns the "result" field.
// A JSON-RPC level error is returned as *RPCError.
func (c *Client) RPCCall(method string, params ...interface{}) (interface{}, error) {
	c.requestID++
	req := map[string]interface{}{
		"jsonrpc": "2.0",
		"method":  method,
		"params":  params,
		"id":      c.requestID,
	}
	body, err := json.Marshal(req)
	if err != nil {
		return nil, fmt.Errorf("nexus: marshal request: %w", err)
	}
	httpReq, err := http.NewRequest("POST", c.config.RPCUrl, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("nexus: create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if c.config.APIKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+c.config.APIKey)
	}
	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("nexus: rpc request failed: %w", err)
	}
	defer resp.Body.Close()
	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("nexus: read response: %w", err)
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("nexus: http %d: %s", resp.StatusCode, truncate(respBody, 200))
	}
	var rpcResp struct {
		Result interface{} `json:"result"`
		Error  *RPCError   `json:"error"`
	}
	if err := json.Unmarshal(respBody, &rpcResp); err != nil {
		return nil, fmt.Errorf("nexus: unmarshal response: %w", err)
	}
	if rpcResp.Error != nil {
		return nil, rpcResp.Error
	}
	return rpcResp.Result, nil
}

// GetBlockNumber returns the canonical chain height (nexus_getNodeStatus.latestHeight,
// numeric — NOT hex).
func (c *Client) GetBlockNumber() (int64, error) {
	result, err := c.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return 0, err
	}
	m, ok := result.(map[string]interface{})
	if !ok {
		return 0, fmt.Errorf("nexus: unexpected getNodeStatus envelope: %T", result)
	}
	return toInt64(m["latestHeight"])
}

// GetChainID returns the chain ID (nexus_getNodeStatus.chainId, numeric).
func (c *Client) GetChainID() (int64, error) {
	result, err := c.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return 0, err
	}
	m, ok := result.(map[string]interface{})
	if !ok {
		return 0, fmt.Errorf("nexus: unexpected getNodeStatus envelope: %T", result)
	}
	return toInt64(m["chainId"])
}

// NodeStatus mirrors the nexus_getNodeStatus envelope (JsonRpcController.doGetNodeStatus).
type NodeStatus struct {
	ChainID      int64  `json:"chainId"`
	LatestHeight int64  `json:"latestHeight"`
	LatestHash   string `json:"latestHash"`
	Syncing      bool   `json:"syncing"`
	Peers        int    `json:"peers"`
	Version      string `json:"version"`
}

// GetNodeStatus returns the full node status envelope.
func (c *Client) GetNodeStatus() (*NodeStatus, error) {
	result, err := c.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return nil, err
	}
	raw, err := json.Marshal(result)
	if err != nil {
		return nil, err
	}
	var st NodeStatus
	if err := json.Unmarshal(raw, &st); err != nil {
		return nil, fmt.Errorf("nexus: decode node status: %w", err)
	}
	return &st, nil
}

// GetBlockByHeight returns a block by height (nexus_getBlockByHeight).
func (c *Client) GetBlockByHeight(height int64) (map[string]interface{}, error) {
	result, err := c.RPCCall("nexus_getBlockByHeight", height, true)
	if err != nil {
		return nil, err
	}
	if m, ok := result.(map[string]interface{}); ok {
		return m, nil
	}
	return nil, fmt.Errorf("nexus: unexpected block envelope: %T", result)
}

// RPCError is a JSON-RPC error envelope.
type RPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *RPCError) Error() string { return fmt.Sprintf("nexus: rpc error %d: %s", e.Code, e.Message) }

// toInt64 accepts the real envelope value forms: JSON numbers (float64 from
// encoding/json) or decimal strings ("123"). NOT hex — nexus-core returns
// decimal strings/numbers.
func toInt64(v interface{}) (int64, error) {
	switch val := v.(type) {
	case float64:
		return int64(val), nil
	case int64:
		return val, nil
	case int:
		return int64(val), nil
	case string:
		var n int64
		if _, err := fmt.Sscanf(val, "%d", &n); err != nil {
			return 0, fmt.Errorf("nexus: not a decimal integer: %q", val)
		}
		return n, nil
	default:
		return 0, fmt.Errorf("nexus: unexpected numeric type %T", v)
	}
}

func truncate(b []byte, n int) string {
	if len(b) <= n {
		return string(b)
	}
	return string(b[:n]) + "..."
}
