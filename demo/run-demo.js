/**
 * NexusChain Demo - 端到端支付流程演示
 * 模拟：注册商户 → 创建订单 → 发起支付 → 链上确认 → 完成
 */
const GATEWAY = process.env.GATEWAY_URL || 'http://localhost:8080';

async function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function post(path, body, headers = {}) {
  const res = await fetch(`${GATEWAY}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  return { status: res.status, data: await res.json().catch(() => null) };
}

async function get(path, headers = {}) {
  const res = await fetch(`${GATEWAY}${path}`, { headers });
  return { status: res.status, data: await res.json().catch(() => null) };
}

async function main() {
  console.log('\n========================================');
  console.log('  NexusChain Demo - Payment Flow');
  console.log('========================================\n');

  // Step 1: Register merchant
  console.log('[1/6] Registering merchant...');
  const reg = await post('/api/v1/merchants/register', {
    merchantName: 'Demo Coffee Shop',
    email: 'demo@nexuschain.io',
    settlementAddress: '1DemoMerchantAddr000000000000000000',
  });
  console.log(`  Status: ${reg.status}`);
  if (reg.status !== 201) { console.log('  FAILED:', reg.data); return; }
  const merchantId = reg.data.id;
  console.log(`  Merchant ID: ${merchantId}, Code: ${reg.data.merchantCode}`);

  // Step 2: Verify merchant
  console.log('\n[2/6] Verifying merchant (KYC)...');
  const verify = await post(`/api/v1/merchants/${merchantId}/verify`, { status: 'VERIFIED' });
  console.log(`  Status: ${verify.status} - ${verify.data?.verificationStatus || 'OK'}`);

  // Step 3: Generate API key
  console.log('\n[3/6] Generating API key...');
  const keyRes = await post(`/api/v1/merchants/${merchantId}/api-keys`, {});
  const apiKey = keyRes.data?.apiKey;
  console.log(`  API Key: ${apiKey?.slice(0, 16)}...`);
  const authHeaders = { 'X-NexusChain-ApiKey': apiKey };

  // Step 4: Create payment order
  console.log('\n[4/6] Creating payment order (2500 NEX)...');
  const order = await post('/api/v1/orders', {
    merchantId: String(merchantId),
    amount: 250000000000,
    description: 'Demo: Premium Coffee Subscription x1',
    notifyUrl: 'http://localhost:3000/webhook',
  }, authHeaders);
  console.log(`  Status: ${order.status}`);
  if (order.status !== 201) { console.log('  FAILED:', order.data); return; }
  const orderId = order.data.id;
  const checkoutToken = order.data.checkoutToken;
  console.log(`  Order: ${order.data.orderNo}`);
  console.log(`  Checkout: http://localhost:8080/checkout.html?token=${checkoutToken}`);

  // Step 5: Initiate payment
  console.log('\n[5/6] Customer initiates payment...');
  await sleep(1000);
  const pay = await post(`/api/v1/orders/${orderId}/pay`, {
    payerAddress: '1CustomerWalletAddr00000000000000000',
  }, authHeaders);
  console.log(`  Status: ${pay.status} - ${pay.data?.status}`);

  // Step 6: Confirm payment (simulates chain confirmation)
  console.log('\n[6/6] Chain confirms transaction...');
  await sleep(2000);
  const confirm = await post(`/api/v1/orders/${orderId}/confirm`, {
    chainTxHash: 'a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2',
  }, authHeaders);
  console.log(`  Status: ${confirm.status} - ${confirm.data?.status}`);
  if (confirm.data?.status === 'PAID') {
    console.log(`  TxHash: ${confirm.data.chainTxHash}`);
    console.log(`  PaidAt: ${confirm.data.paidAt}`);
  }

  // Final: Check order status
  console.log('\n----------------------------------------');
  const finalStatus = await get(`/api/v1/checkout/status?token=${checkoutToken}`);
  console.log(`  Final Order Status: ${finalStatus.data?.status}`);
  console.log('----------------------------------------');
  console.log('\n  Demo complete! Open the Explorer to see chain activity.');
  console.log('  Explorer: http://localhost:5173');
  console.log('  Gateway:  http://localhost:8080/checkout.html?token=' + checkoutToken);
  console.log('');
}

main().catch(e => console.error('Demo failed:', e.message));