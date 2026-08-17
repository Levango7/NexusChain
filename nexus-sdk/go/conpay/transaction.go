package conpay

import (
	"fmt"
	"math/big"
)

// Transaction represents an unsigned ConPay transaction.
type Transaction struct {
	From     string
	To       string
	Value    *big.Int
	Token    string // "CPAY" or contract address
	GasLimit *big.Int
	GasPrice *big.Int
	Nonce    *big.Int
	Data     []byte
}

// TransactionReceipt represents the receipt of a confirmed transaction.
type TransactionReceipt struct {
	TransactionHash string
	BlockHash       string
	BlockNumber     int64
	Status          string // "success" or "failed"
	GasUsed         *big.Int
	Logs            []TransactionLog
}

// TransactionLog represents a log entry in a transaction receipt.
type TransactionLog struct {
	Address  string
	Topics   []string
	Data     string
	LogIndex int
}

// TransferParams holds parameters for a simple transfer transaction.
type TransferParams struct {
	From   string
	To     string
	Amount *big.Int
	Token  string // defaults to "CPAY"
}

// TransactionManager provides transaction building, signing, and broadcasting capabilities.
type TransactionManager struct {
	client *Client
}

// BuildTransfer builds a CPAY native transfer transaction.
func (tm *TransactionManager) BuildTransfer(params TransferParams) (*Transaction, error) {
	// Query nonce
	nonceResult, err := tm.client.RPCCall("nexus_getTransactionCount", params.From, "latest")
	if err != nil {
		return nil, fmt.Errorf("failed to get nonce: %w", err)
	}

	// Query gas price
	// 注意：nexus-core 当前未提供 nexus_gasPrice，此处保留接口兼容性。
	// 上层应通过 nexus_getNodeStatus 或外部 oracle 获取 gas 建议值。
	gasPriceResult, err := tm.client.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return nil, fmt.Errorf("failed to get gas price: %w", err)
	}

	token := params.Token
	if token == "" {
		token = "CPAY"
	}

	return &Transaction{
		From:     params.From,
		To:       params.To,
		Value:    params.Amount,
		Token:    token,
		Nonce:    new(big.Int).SetInt64(toInt64(nonceResult)),
		GasPrice: new(big.Int).SetInt64(toInt64(gasPriceResult)),
	}, nil
}

// BuildContractCall builds a contract call transaction.
func (tm *TransactionManager) BuildContractCall(from, contractAddress string, data []byte, value *big.Int) (*Transaction, error) {
	// TODO: build contract call transaction
	return nil, fmt.Errorf("not yet implemented")
}

// Sign signs a transaction with the given private key.
func (tm *TransactionManager) Sign(tx *Transaction, privateKey string) (string, error) {
	// TODO: sign transaction with private key
	return "", fmt.Errorf("not yet implemented")
}

// Broadcast broadcasts a signed transaction to the network.
//
// 注意：nexus-core 当前 JSON-RPC 入口未直接暴露 nexus_sendRawTransaction，
// 交易广播通过 P2P 协议或 wallet-service 完成。此处保留接口以兼容旧 SDK 用户，
// 实际部署应通过 wallet-service HTTP 接口提交。
func (tm *TransactionManager) Broadcast(signedTx string) (string, error) {
	result, err := tm.client.RPCCall("nexus_sendRawTransaction", signedTx)
	if err != nil {
		return "", fmt.Errorf("failed to broadcast transaction: %w", err)
	}
	return toString(result), nil
}

// GetTransactionReceipt queries the receipt of a transaction.
//
// 兼容实现：nexus-core 未提供 nexus_getTransactionReceipt，
// 改为调用 nexus_getTransactionByHash 返回交易详情。
func (tm *TransactionManager) GetTransactionReceipt(txHash string) (*TransactionReceipt, error) {
	result, err := tm.client.RPCCall("nexus_getTransactionByHash", txHash)
	if err != nil {
		return nil, err
	}
	// 简化处理：将 RPC 返回映射到 TransactionReceipt
	_ = result
	return nil, fmt.Errorf("not yet implemented")
}

// EstimateGas estimates the gas required for a transaction.
//
// 注意：nexus-core 当前未提供 nexus_estimateGas，保留接口以兼容旧 SDK 用户。
func (tm *TransactionManager) EstimateGas(tx *Transaction) (*big.Int, error) {
	return nil, fmt.Errorf("nexus_estimateGas not supported by nexus-core")
}

// GetGasPrice returns the current gas price.
//
// 兼容实现：nexus-core 未提供 nexus_gasPrice，改为调用 nexus_getNodeStatus
// 从节点状态中获取 gasPrice 字段；若不存在则返回默认值 1 gwei。
func (tm *TransactionManager) GetGasPrice() (*big.Int, error) {
	result, err := tm.client.RPCCall("nexus_getNodeStatus")
	if err != nil {
		return nil, err
	}
	if m, ok := result.(map[string]interface{}); ok {
		if gp, ok := m["gasPrice"]; ok {
			return new(big.Int).SetInt64(toInt64(gp)), nil
		}
	}
	// 默认 1 gwei
	return new(big.Int).SetInt64(1_000_000_000), nil
}

// toInt64 converts an interface{} to int64 (handling hex strings).
func toInt64(v interface{}) int64 {
	n, err := parseHexInt(v)
	if err != nil {
		return 0
	}
	return n
}
