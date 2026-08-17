// Package conpay provides the NexusChain SDK for Go.
//
// 统一多语言 SDK 的 Go 实现，为 NexusChain 区块链支付网络提供全栈访问能力。
// 代币符号：NEX。
//
// 包名 conpay 保留作为 deprecated 别名，新代码请使用 nexus 包（待发布）。
//
// Usage:
//
//	client := conpay.NewClient(&conpay.Config{
//	    Network: "mainnet",
//	    RPCUrl:  "https://rpc.nexus.network",
//	})
//	wallet := client.Wallet().Create()
//	balance, _ := client.Wallet().GetBalance(wallet.Address)
package conpay

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Config holds the ConPay client configuration.
type Config struct {
	// Network is the network type: "mainnet" or "testnet".
	Network string
	// RPCUrl is the ConPay node RPC endpoint.
	RPCUrl string
	// Timeout is the request timeout in milliseconds. Default: 30000.
	Timeout int
	// APIKey is the optional API key for authenticated nodes.
	APIKey string
}

// Client is the main ConPay SDK client.
// It aggregates wallet management, transaction building/signing/broadcasting,
// and RPC access capabilities.
type Client struct {
	config      *Config
	httpClient  *http.Client
	requestID   int64
	wallet      *WalletManager
	transaction *TransactionManager
}

// NewClient creates a new ConPayClient instance.
func NewClient(config *Config) *Client {
	if config.Timeout == 0 {
		config.Timeout = 30000
	}

	c := &Client{
		config:     config,
		httpClient: &http.Client{Timeout: time.Duration(config.Timeout) * time.Millisecond},
	}

	c.wallet = &WalletManager{client: c}
	c.transaction = &TransactionManager{client: c}

	return c
}

// Wallet returns the wallet manager.
func (c *Client) Wallet() *WalletManager {
	return c.wallet
}

// Transaction returns the transaction manager.
func (c *Client) Transaction() *TransactionManager {
	return c.transaction
}

// Network returns the current network type.
func (c *Client) Network() string {
	return c.config.Network
}

// RPCUrl returns the RPC endpoint URL.
func (c *Client) RPCUrl() string {
	return c.config.RPCUrl
}

// RPCCall sends a JSON-RPC request to the ConPay node.
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
		return nil, fmt.Errorf("failed to marshal request: %w", err)
	}

	httpReq, err := http.NewRequest("POST", c.config.RPCUrl, bytes.NewReader(body))
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if c.config.APIKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+c.config.APIKey)
	}

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("RPC request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response: %w", err)
	}

	var rpcResp struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      int64           `json:"id"`
		Result  interface{}     `json:"result"`
		Error   *RPCError       `json:"error"`
	}
	if err := json.Unmarshal(respBody, &rpcResp); err != nil {
		return nil, fmt.Errorf("failed to unmarshal response: %w", err)
	}

	if rpcResp.Error != nil {
		return nil, fmt.Errorf("RPC error %d: %s", rpcResp.Error.Code, rpcResp.Error.Message)
	}

	return rpcResp.Result, nil
}

// GetBlockNumber queries the current block height.
//
// 兼容实现：nexus-core 未提供 nexus_blockNumber，改为调用 nexus_getLatestBlocks
// 取最新区块列表中的第一个区块高度。
func (c *Client) GetBlockNumber() (int64, error) {
	result, err := c.RPCCall("nexus_getLatestBlocks", 1)
	if err != nil {
		return 0, err
	}
	// result 通常是数组，取第一个元素的 height 字段
	switch v := result.(type) {
	case []interface{}:
		if len(v) == 0 {
			return 0, nil
		}
		if m, ok := v[0].(map[string]interface{}); ok {
			if h, ok := m["height"]; ok {
				return parseHexInt(h)
			}
			if h, ok := m["number"]; ok {
				return parseHexInt(h)
			}
		}
		return parseHexInt(v[0])
	default:
		return parseHexInt(v)
	}
}

// GetChainID returns the network chain ID.
//
// 兼容实现：nexus-core 未提供 nexus_chainId，改为调用 nexus_getNodeStatus
// 从节点状态中获取 chainId 字段。
func (c *Client) GetChainID() (int64, error) {
	result, err := c.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return 0, err
	}
	if m, ok := result.(map[string]interface{}); ok {
		if cid, ok := m["chainId"]; ok {
			return parseHexInt(cid)
		}
		if cid, ok := m["chain_id"]; ok {
			return parseHexInt(cid)
		}
	}
	return parseHexInt(result)
}

// RPCError represents a JSON-RPC error response.
type RPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

func (e *RPCError) Error() string {
	return fmt.Sprintf("RPC error %d: %s", e.Code, e.Message)
}

// parseHexInt parses a hex string or float64 to int64.
func parseHexInt(v interface{}) (int64, error) {
	switch val := v.(type) {
	case string:
		var n int64
		_, err := fmt.Sscanf(val, "0x%x", &n)
		if err != nil {
			return 0, fmt.Errorf("failed to parse hex value %s: %w", val, err)
		}
		return n, nil
	case float64:
		return int64(val), nil
	default:
		return 0, fmt.Errorf("unexpected type for numeric value: %T", v)
	}
}
