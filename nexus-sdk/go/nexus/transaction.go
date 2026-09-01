package nexus

import (
	"fmt"
	"math/big"
)

// TransactionManager builds transactions against real nexus-core envelopes
// and queries confirmed transactions.
type TransactionManager struct {
	client *Client
}

// Transaction is an unsigned NEX transfer built from real chain state.
type Transaction struct {
	From     string
	To       string
	Value    *big.Int
	Token    string
	Nonce    int64
	GasPrice *big.Int
}

// BuildTransfer assembles an unsigned transfer: nonce comes from
// nexus_getTransactionCount ({"count":N} envelope), gasPrice from the
// wallet-service/oracle recommendation (core JSON-RPC has no nexus_gasPrice;
// getNodeStatus carries no gasPrice field — see JsonRpcController.doGetNodeStatus).
func (tm *TransactionManager) BuildTransfer(from, to string, amount *big.Int) (*Transaction, error) {
	if !ValidateAddress(from) {
		return nil, fmt.Errorf("nexus: invalid from address %q", from)
	}
	if !ValidateAddress(to) {
		return nil, fmt.Errorf("nexus: invalid to address %q", to)
	}
	nonce, err := tm.client.Wallet.GetNonce(from)
	if err != nil {
		return nil, err
	}
	return &Transaction{
		From:     from,
		To:       to,
		Value:    amount,
		Token:    "NEX",
		Nonce:    nonce,
		GasPrice: big.NewInt(0), // gas 费表见 core Transaction.GAS_TABLE；SDK 侧无查询接口
	}, nil
}

// GetTransactionByHash returns a confirmed transaction
// (nexus_getTransactionByHash → {txHash, from, to, amount, status, ...}).
// NOT a receipt: pending/confirmation semantics don't apply to core's RPC
// (it returns only on-chain transactions with status "success").
func (tm *TransactionManager) GetTransactionByHash(txHash string) (map[string]interface{}, error) {
	result, err := tm.client.RPCCall("nexus_getTransactionByHash", txHash)
	if err != nil {
		return nil, fmt.Errorf("nexus: get transaction: %w", err)
	}
	if m, ok := result.(map[string]interface{}); ok {
		return m, nil
	}
	return nil, fmt.Errorf("nexus: unexpected transaction envelope: %T", result)
}

// GetLatestTransactions returns recent on-chain transactions
// (nexus_getLatestTransactions, limit clamped to 1..100 server-side).
func (tm *TransactionManager) GetLatestTransactions(limit int) ([]map[string]interface{}, error) {
	result, err := tm.client.RPCCall("nexus_getLatestTransactions", limit)
	if err != nil {
		return nil, fmt.Errorf("nexus: latest transactions: %w", err)
	}
	arr, ok := result.([]interface{})
	if !ok {
		return nil, fmt.Errorf("nexus: unexpected list envelope: %T", result)
	}
	out := make([]map[string]interface{}, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]interface{}); ok {
			out = append(out, m)
		}
	}
	return out, nil
}

// BridgeManager queries the on-chain cross-chain bridge view.
type BridgeManager struct {
	client *Client
}

// CrossChainTransaction mirrors a toRpcCrossChainTx entry (BRIDGE_* payload
// projection over the last 200 blocks).
type CrossChainTransaction struct {
	BridgeTxID   string `json:"bridgeTxId"`
	SourceChain  string `json:"sourceChain"`
	TargetChain  string `json:"targetChain"`
	Recipient    string `json:"recipient"`
	Amount       string `json:"amount"`
	Status       string `json:"status"`
	TimeStamp    int64  `json:"timestamp"`
}

// List returns cross-chain transactions (nexus_getCrossChainTransactions).
// statusFilter is optional ("pending"/"confirmed"/... — server-side filter).
func (b *BridgeManager) List(limit int, statusFilter string) ([]map[string]interface{}, error) {
	var result interface{}
	var err error
	if statusFilter == "" {
		result, err = b.client.RPCCall("nexus_getCrossChainTransactions", limit)
	} else {
		result, err = b.client.RPCCall("nexus_getCrossChainTransactions", limit, statusFilter)
	}
	if err != nil {
		return nil, fmt.Errorf("nexus: cross-chain list: %w", err)
	}
	arr, ok := result.([]interface{})
	if !ok {
		return nil, fmt.Errorf("nexus: unexpected cross-chain envelope: %T", result)
	}
	out := make([]map[string]interface{}, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]interface{}); ok {
			out = append(out, m)
		}
	}
	return out, nil
}
