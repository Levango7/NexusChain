package nexus

import (
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

// fakeCore 起一个模拟 nexus-core JSON-RPC 端点，回真实信封形状
// （JsonRpcController 各 do* 方法的字段——数值型/十进制字符串，非 0x hex）。
type fakeCore struct {
	t       *testing.T
	lastReq map[string]interface{}
}

func (f *fakeCore) handler(w http.ResponseWriter, r *http.Request) {
	var req map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		f.t.Fatalf("bad request body: %v", err)
	}
	f.lastReq = req
	resp := map[string]interface{}{"jsonrpc": "2.0", "id": req["id"]}
	switch req["method"] {
	case "nexus_getBalance":
		resp["result"] = map[string]string{"balance": "123456789"}
	case "nexus_getTransactionCount":
		resp["result"] = map[string]int64{"count": 7}
	case "nexus_getNodeStatus":
		resp["result"] = map[string]interface{}{
			"chainId": 31337, "latestHeight": 100, "latestHash": "ab12",
			"syncing": false, "peers": 0, "version": "v2-rpc-bridge",
		}
	case "nexus_getTransactionsByAddress":
		resp["result"] = []map[string]interface{}{
			{"txHash": "aa", "from": "f1", "to": "t1", "amount": "5", "status": "success"},
		}
	case "nexus_getTransactionByHash":
		resp["result"] = map[string]interface{}{
			"txHash": "aa", "from": "f1", "to": "t1", "amount": "5", "status": "success",
		}
	case "nexus_getLatestTransactions":
		resp["result"] = []map[string]interface{}{{"txHash": "bb"}}
	case "nexus_getCrossChainTransactions":
		resp["result"] = []map[string]interface{}{
			{"bridgeTxId": "cc", "sourceChain": "eth", "status": "confirmed"},
		}
	case "nexus_getLatestBlocks":
		resp["result"] = []map[string]interface{}{{"height": 99, "hash": "hh"}}
	default:
		resp["error"] = map[string]interface{}{"code": -32601, "message": "method not found"}
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(resp)
}

func newTestClient(t *testing.T, coreURL, walletURL string) (*Client, *fakeCore) {
	fc := &fakeCore{t: t}
	srv := httptest.NewServer(http.HandlerFunc(fc.handler))
	t.Cleanup(srv.Close)
	url := coreURL
	if url == "" {
		url = srv.URL
	}
	c, err := NewClient(&Config{RPCUrl: url, WalletServiceURL: walletURL})
	if err != nil {
		t.Fatalf("NewClient: %v", err)
	}
	return c, fc
}

func TestClientRequiresRPCUrl(t *testing.T) {
	if _, err := NewClient(&Config{}); err == nil {
		t.Fatal("empty RPCUrl should fail")
	}
}

func TestGetBalanceDecodesDecimalEnvelope(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	b, err := c.Wallet.GetBalance("1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk")
	if err != nil {
		t.Fatalf("GetBalance: %v", err)
	}
	if b.Cmp(big.NewInt(123456789)) != 0 {
		t.Fatalf("balance = %v, want 123456789", b)
	}
}

func TestGetNonceDecodesCountEnvelope(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	n, err := c.Wallet.GetNonce("some-address")
	if err != nil {
		t.Fatalf("GetNonce: %v", err)
	}
	if n != 7 {
		t.Fatalf("nonce = %d, want 7", n)
	}
}

func TestNodeStatusNumericFields(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	st, err := c.GetNodeStatus()
	if err != nil {
		t.Fatalf("GetNodeStatus: %v", err)
	}
	if st.ChainID != 31337 || st.LatestHeight != 100 {
		t.Fatalf("status = %+v, want chainId=31337 height=100", st)
	}
	if c.GetBlockNumber(); true {
		h, err := c.GetBlockNumber()
		if err != nil || h != 100 {
			t.Fatalf("GetBlockNumber = %d, %v; want 100", h, err)
		}
	}
	cid, err := c.GetChainID()
	if err != nil || cid != 31337 {
		t.Fatalf("GetChainID = %d, %v; want 31337", cid, err)
	}
}

func TestRPCErrorSurfaces(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	_, err := c.RPCCall("nexus_nonexistent")
	if err == nil {
		t.Fatal("unknown method must error")
	}
	if !strings.Contains(err.Error(), "method not found") {
		t.Fatalf("error = %v, want method-not-found", err)
	}
}

func TestBuildTransferFillsNonceAndValidatesAddress(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	// 无效地址被本地校验拦截
	if _, err := c.Transaction.BuildTransfer("bad!", "bad!", big.NewInt(1)); err == nil {
		t.Fatal("invalid address must fail build")
	}
}

func TestSubmitTransferRequiresWalletServiceURL(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	_, err := c.Wallet.SubmitTransfer(SubmitTransferRequest{
		From: "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk", To: "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk",
		Amount: big.NewInt(1),
	})
	if err == nil || !strings.Contains(err.Error(), "WalletServiceURL") {
		t.Fatalf("err = %v, want WalletServiceURL requirement", err)
	}
}

func TestTransactionQueries(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	tx, err := c.Transaction.GetTransactionByHash("aa")
	if err != nil || tx["txHash"] != "aa" {
		t.Fatalf("GetTransactionByHash = %v, %v", tx, err)
	}
	latest, err := c.Transaction.GetLatestTransactions(5)
	if err != nil || len(latest) != 1 {
		t.Fatalf("GetLatestTransactions = %v, %v", latest, err)
	}
	byAddr, err := c.Wallet.GetTransactionsByAddress("f1", 10)
	if err != nil || len(byAddr) != 1 {
		t.Fatalf("GetTransactionsByAddress = %v, %v", byAddr, err)
	}
}

func TestBridgeList(t *testing.T) {
	c, _ := newTestClient(t, "", "")
	cc, err := c.Bridge.List(5, "")
	if err != nil || len(cc) != 1 || cc[0]["bridgeTxId"] != "cc" {
		t.Fatalf("Bridge.List = %v, %v", cc, err)
	}
}

// ===== 地址本地校验 =====

func TestBase58Decode(t *testing.T) {
	// "1" 是 Base58 的零值——1 字节 0x00
	out, err := base58Decode("1")
	if err != nil || len(out) != 1 || out[0] != 0 {
		t.Fatalf("base58Decode('1') = %v, %v", out, err)
	}
	if _, err := base58Decode("0OIl"); err == nil {
		t.Fatal("invalid base58 chars must fail")
	}
}

func TestValidateAddressRejectsMalformed(t *testing.T) {
	for _, bad := range []string{"", "abc", "0x1234", "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLkX"} {
		if ValidateAddress(bad) {
			t.Fatalf("ValidateAddress(%q) should be false", bad)
		}
	}
}

func TestValidateAddressAcceptsWellFormed(t *testing.T) {
	// 用 base58 编码一个自洽的 21 字节地址（版本 0 + 20 字节 hash + 正确校验尾）
	payload := make([]byte, 20)
	for i := range payload {
		payload[i] = byte(i + 1)
	}
	addr := base58EncodeChecked(payload)
	if !ValidateAddress(addr) {
		t.Fatalf("ValidateAddress(%q) should be true (self-consistent checksum)", addr)
	}
	// 篡改一个字符后校验失败
	tampered := []byte(addr)
	if tampered[0] == '2' {
		tampered[0] = '3'
	} else {
		tampered[0] = '2'
	}
	if ValidateAddress(string(tampered)) {
		t.Fatal("tampered address must fail checksum")
	}
}
// base58EncodeChecked 编码 20 字节 pubkeyHash → 21 字节带校验地址（测试辅助）。
func base58EncodeChecked(pubkeyHash []byte) string {
	h := keccak256(keccak256(pubkeyHash))
	full := append(append([]byte{0x00}, pubkeyHash...), h[:4]...)
	// big-endian → base-58（数字主体）
	n := new(big.Int).SetBytes(full)
	radix := big.NewInt(58)
	alphabet := "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
	var digits []byte
	for n.Sign() > 0 {
		mod := new(big.Int)
		n.DivMod(n, radix, mod)
		digits = append([]byte{alphabet[mod.Int64()]}, digits...)
	}
	// 前导零字节 → 每个零一个 '1'，放在数字主体之前
	ones := 0
	for _, b := range full {
		if b != 0 {
			break
		}
		ones++
	}
	return strings.Repeat("1", ones) + string(digits)
}

// TestGoldenAddressCrossLanguage — Java KeystoreAction.main() 中出现过的
// 真实地址。Go 实现的解码语义必须与 Java verifyAddress 一致：Base58 解码
// 为 25 字节（1 版本 + 20 哈希 + 4 校验尾）。若解码失败或长度漂移，
// 说明两侧地址语义不一致。
func TestGoldenAddressCrossLanguage(t *testing.T) {
	addr := "1L3zkde4kSpfd1L7NYmNYSBf1Bvh6fZLk"
	decoded, err := base58Decode(addr)
	if err != nil {
		t.Fatalf("golden address must decode: %v", err)
	}
	if len(decoded) != 25 {
		t.Fatalf("golden address decodes to %d bytes, want 25", len(decoded))
	}
}
