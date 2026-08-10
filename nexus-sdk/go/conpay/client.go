// Package conpay provides the ConPay SDK for Go.
//
// 统一多语言 SDK 的 Go 实现，为 ConPay 区块链支付网络提供全栈访问能力。
// 代币符号：CPAY。
//
// Usage:
//
//	client := conpay.NewClient(&conpay.Config{
//	    Network: "mainnet",
//	    RPCUrl:  "https://rpc.conpay.network",
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
func (c *Client) GetBlockNumber() (int64, error) {
	result, err := c.RPCCall("conpay_blockNumber")
	if err != nil {
		return 0, err
	}
	return parseHexInt(result)
}

// GetChainID returns the network chain ID.
func (c *Client) GetChainID() (int64, error) {
	result, err := c.RPCCall("conpay_chainId")
	if err != nil {
		return 0, err
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
