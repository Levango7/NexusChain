package conpay

import (
	"fmt"
	"math/big"
)

// WalletInfo holds wallet key material and address.
type WalletInfo struct {
	Address    string
	PrivateKey string
	PublicKey  string
}

// WalletManager provides wallet creation, import, and balance query capabilities.
type WalletManager struct {
	client *Client
}

// Create generates a new wallet with a fresh key pair.
// Not implemented: wallet key generation must be performed by the wallet-service
// (nexus-wallet-service) which enforces KMS, key rotation, and audit policies.
// Callers should use the wallet-service API instead.
func (w *WalletManager) Create() (*WalletInfo, error) {
	return nil, fmt.Errorf("WalletManager.Create not implemented: use wallet-service API instead")
}

// FromPrivateKey imports a wallet from a hex-encoded private key.
func (w *WalletManager) FromPrivateKey(privateKey string) (*WalletInfo, error) {
	return nil, fmt.Errorf("WalletManager.FromPrivateKey not implemented: use wallet-service API instead")
}

// FromMnemonic imports a wallet from a BIP-39 mnemonic phrase.
func (w *WalletManager) FromMnemonic(mnemonic, path string) (*WalletInfo, error) {
	return nil, fmt.Errorf("WalletManager.FromMnemonic not implemented: use wallet-service API instead")
}

// GetBalance queries the CPAY balance of an address (in wei).
func (w *WalletManager) GetBalance(address string) (*big.Int, error) {
	result, err := w.client.RPCCall("nexus_getBalance", address, "latest")
	if err != nil {
		return nil, fmt.Errorf("failed to get balance: %w", err)
	}

	// nexus_getBalance 返回 {"balance": "<decimal>"} 信封，需解包
	if m, ok := result.(map[string]interface{}); ok {
		if b, ok := m["balance"]; ok {
			balance, ok := new(big.Int).SetString(toString(b), 0)
			if !ok {
				return nil, fmt.Errorf("failed to parse balance value: %v", b)
			}
			return balance, nil
		}
	}
	balance, ok := new(big.Int).SetString(toString(result), 0)
	if !ok {
		return nil, fmt.Errorf("failed to parse balance value: %v", result)
	}
	return balance, nil
}

// GetTokenBalance queries the balance of a specific token for an address.
func (w *WalletManager) GetTokenBalance(address, tokenContract string) (*big.Int, error) {
	return nil, fmt.Errorf("WalletManager.GetTokenBalance not implemented: use wallet-service API or contract call directly")
}

// ValidateAddress checks whether an address is valid.
// Returns (false, err) on invalid format; (true, nil) on valid.
func (w *WalletManager) ValidateAddress(address string) (bool, error) {
	return false, fmt.Errorf("WalletManager.ValidateAddress not implemented: use wallet-service API or validate locally")
}

// toString converts an interface{} to string.
func toString(v interface{}) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}
