package nexus

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"

	"golang.org/x/crypto/sha3"
)

// WalletManager provides wallet queries against nexus-core and transaction
// submission through nexus-wallet-service.
//
// Key generation / signing deliberately live in wallet-service (KMS, rotation,
// audit) — the SDK never holds private keys (see package doc).
type WalletManager struct {
	client *Client
}

// GetBalance returns the NEX balance in minimal units (wei).
//
// Envelope (JsonRpcController.doGetBalance): {"balance": "<decimal string>"}.
func (w *WalletManager) GetBalance(address string) (*big.Int, error) {
	result, err := w.client.RPCCall("nexus_getBalance", address)
	if err != nil {
		return nil, fmt.Errorf("nexus: get balance: %w", err)
	}
	m, ok := result.(map[string]interface{})
	if !ok {
		return nil, fmt.Errorf("nexus: unexpected balance envelope: %T", result)
	}
	b, ok := new(big.Int).SetString(fmt.Sprintf("%v", m["balance"]), 10)
	if !ok {
		return nil, fmt.Errorf("nexus: balance not a decimal string: %v", m["balance"])
	}
	return b, nil
}

// GetNonce returns the next nonce for an address.
//
// Envelope (doGetTransactionCount): {"count": <numeric>}.
func (w *WalletManager) GetNonce(address string) (int64, error) {
	result, err := w.client.RPCCall("nexus_getTransactionCount", address)
	if err != nil {
		return 0, fmt.Errorf("nexus: get nonce: %w", err)
	}
	m, ok := result.(map[string]interface{})
	if !ok {
		return 0, fmt.Errorf("nexus: unexpected count envelope: %T", result)
	}
	return toInt64(m["count"])
}

// GetTransactionsByAddress returns transactions touching an address
// (nexus_getTransactionsByAddress → RpcTransaction[]).
func (w *WalletManager) GetTransactionsByAddress(address string, limit int) ([]map[string]interface{}, error) {
	result, err := w.client.RPCCall("nexus_getTransactionsByAddress", address, limit)
	if err != nil {
		return nil, fmt.Errorf("nexus: get transactions: %w", err)
	}
	arr, ok := result.([]interface{})
	if !ok {
		return nil, fmt.Errorf("nexus: unexpected transaction list envelope: %T", result)
	}
	out := make([]map[string]interface{}, 0, len(arr))
	for _, e := range arr {
		if m, ok := e.(map[string]interface{}); ok {
			out = append(out, m)
		}
	}
	return out, nil
}

// SubmitTransfer signs and submits a transfer through nexus-wallet-service.
//
// core's JSON-RPC has no nexus_sendRawTransaction (deliberate: transaction
// submission is wallet-service's domain). The SDK posts a structured request;
// wallet-service enforces KMS/signing/audit and returns the tx hash.
type SubmitTransferRequest struct {
	From   string   `json:"from"`
	To     string   `json:"to"`
	Amount *big.Int `json:"amount"`
	Token  string   `json:"token,omitempty"`
}

// SubmitTransfer posts the transfer to wallet-service /api/v1/transfers and
// returns the resulting transaction hash.
func (w *WalletManager) SubmitTransfer(req SubmitTransferRequest) (string, error) {
	if w.client.config.WalletServiceURL == "" {
		return "", fmt.Errorf("nexus: Config.WalletServiceURL is required for SubmitTransfer " +
			"(transaction submission goes through nexus-wallet-service, not core JSON-RPC)")
	}
	if !ValidateAddress(req.From) || !ValidateAddress(req.To) {
		return "", fmt.Errorf("nexus: invalid address (from=%q to=%q)", req.From, req.To)
	}
	body, err := json.Marshal(map[string]interface{}{
		"from":   req.From,
		"to":     req.To,
		"amount": req.Amount.String(),
		"token":  "NEX",
	})
	if err != nil {
		return "", err
	}
	httpReq, err := http.NewRequest("POST",
		w.client.config.WalletServiceURL+"/api/v1/transfers", bytes.NewReader(body))
	if err != nil {
		return "", err
	}
	httpReq.Header.Set("Content-Type", "application/json")
	if w.client.config.APIKey != "" {
		httpReq.Header.Set("Authorization", "Bearer "+w.client.config.APIKey)
	}
	resp, err := w.client.httpClient.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("nexus: wallet-service request failed: %w", err)
	}
	defer resp.Body.Close()
	respBody, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusCreated {
		return "", fmt.Errorf("nexus: wallet-service http %d: %s", resp.StatusCode, truncate(respBody, 200))
	}
	var envelope struct {
		TxHash string `json:"txHash"`
		Hash   string `json:"hash"`
		Data   struct {
			TxHash string `json:"txHash"`
		} `json:"data"`
	}
	if err := json.Unmarshal(respBody, &envelope); err != nil {
		return "", fmt.Errorf("nexus: decode wallet-service response: %w", err)
	}
	if h := envelope.TxHash; h != "" {
		return h, nil
	}
	if h := envelope.Hash; h != "" {
		return h, nil
	}
	if h := envelope.Data.TxHash; h != "" {
		return h, nil
	}
	return "", fmt.Errorf("nexus: wallet-service response missing tx hash: %s", truncate(respBody, 200))
}

// ValidateAddress checks NexusChain address format locally (no network):
// Base58 decodable to 25 bytes (1 version byte 0x00 + 20 pubkey-hash + 4
// checksum), checksum = first 4 bytes of keccak256(keccak256(pubkeyHash))
// where pubkeyHash = decoded[1:21] (version byte skipped). Mirrors Java
// KeystoreAction.verifyAddress: r5=decode(addr) is 25 bytes; r2=r5[0:21] for
// pubkeyHash extraction; checksum compares keccak²(pubkeyHash) with r5[-4:].
func ValidateAddress(address string) bool {
	decoded, err := base58Decode(address)
	if err != nil || len(decoded) != 25 {
		return false
	}
	pubkeyHash := decoded[1:21]
	checksum := decoded[len(decoded)-4:]
	h := keccak256(keccak256(pubkeyHash))
	for i := 0; i < 4; i++ {
		if h[i] != checksum[i] {
			return false
		}
	}
	return true
}

// base58Decode implements the Bitcoin-alphabet Base58 decode (no dependency).
func base58Decode(s string) ([]byte, error) {
	alphabet := "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
	index := make(map[byte]int, 58)
	for i := 0; i < len(alphabet); i++ {
		index[alphabet[i]] = i
	}
	zeros := 0
	for zeros < len(s) && s[zeros] == '1' {
		zeros++
	}
	// big-endian base-58 → big integer
	n := new(big.Int)
	radix := big.NewInt(58)
	for i := 0; i < len(s); i++ {
		v, ok := index[s[i]]
		if !ok {
			return nil, fmt.Errorf("invalid base58 character %q", s[i])
		}
		n.Mul(n, radix)
		n.Add(n, big.NewInt(int64(v)))
	}
	out := make([]byte, zeros)
	body := n.Bytes()
	if n.Sign() == 0 {
		body = nil
	}
	out = append(out, body...)
	return out, nil
}

// keccak256 uses SHA3-256 with the original Keccak padding (NOT NIST SHA3) —
// the checksum flavor NexusChain addresses use (mirrors Java SHA3Utility.keccak256).
func keccak256(data []byte) []byte {
	h := sha3.NewLegacyKeccak256()
	h.Write(data)
	return h.Sum(nil)
}
