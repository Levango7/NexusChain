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
	nonceResult, err := tm.client.RPCCall("conpay_getTransactionCount", params.From, "latest")
	if err != nil {
		return nil, fmt.Errorf("failed to get nonce: %w", err)
	}

	// Query gas price
	gasPriceResult, err := tm.client.RPCCall("conpay_gasPrice")
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
func (tm *TransactionManager) Broadcast(signedTx string) (string, error) {
	result, err := tm.client.RPCCall("conpay_sendRawTransaction", signedTx)
	if err != nil {
		return "", fmt.Errorf("failed to broadcast transaction: %w", err)
	}
	return toString(result), nil
}

// GetTransactionReceipt queries the receipt of a transaction.
func (tm *TransactionManager) GetTransactionReceipt(txHash string) (*TransactionReceipt, error) {
	// TODO: query and parse transaction receipt
	return nil, fmt.Errorf("not yet implemented")
}

// EstimateGas estimates the gas required for a transaction.
func (tm *TransactionManager) EstimateGas(tx *Transaction) (*big.Int, error) {
	// TODO: call conpay_estimateGas
	return nil, fmt.Errorf("not yet implemented")
}

// GetGasPrice returns the current gas price.
func (tm *TransactionManager) GetGasPrice() (*big.Int, error) {
	result, err := tm.client.RPCCall("conpay_gasPrice")
	if err != nil {
		return nil, err
	}
	return new(big.Int).SetInt64(toInt64(result)), nil
}

// toInt64 converts an interface{} to int64 (handling hex strings).
func toInt64(v interface{}) int64 {
	n, err := parseHexInt(v)
	if err != nil {
		return 0
	}
	return n
}
