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
func (w *WalletManager) Create() *WalletInfo {
	// TODO: generate ECDSA key pair
	panic("Not yet implemented")
}

// FromPrivateKey imports a wallet from a hex-encoded private key.
func (w *WalletManager) FromPrivateKey(privateKey string) (*WalletInfo, error) {
	// TODO: derive public key and address from private key
	return nil, fmt.Errorf("not yet implemented")
}

// FromMnemonic imports a wallet from a BIP-39 mnemonic phrase.
func (w *WalletManager) FromMnemonic(mnemonic, path string) (*WalletInfo, error) {
	// TODO: derive key pair from mnemonic
	return nil, fmt.Errorf("not yet implemented")
}

// GetBalance queries the CPAY balance of an address (in wei).
func (w *WalletManager) GetBalance(address string) (*big.Int, error) {
	result, err := w.client.RPCCall("conpay_getBalance", address, "latest")
	if err != nil {
		return nil, fmt.Errorf("failed to get balance: %w", err)
	}

	balance, ok := new(big.Int).SetString(toString(result), 0)
	if !ok {
		return nil, fmt.Errorf("failed to parse balance value: %v", result)
	}
	return balance, nil
}

// GetTokenBalance queries the balance of a specific token for an address.
func (w *WalletManager) GetTokenBalance(address, tokenContract string) (*big.Int, error) {
	// TODO: call contract balanceOf method
	return nil, fmt.Errorf("not yet implemented")
}

// ValidateAddress checks whether an address is valid.
func (w *WalletManager) ValidateAddress(address string) bool {
	// TODO: validate address format
	panic("Not yet implemented")
}

// toString converts an interface{} to string.
func toString(v interface{}) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprintf("%v", v)
}
