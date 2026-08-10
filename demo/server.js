import express from 'express';
import cors from 'cors';
import crypto from 'crypto';

const app = express();
app.use(cors());
app.use(express.json());

// === In-memory chain state ===
const state = {
  height: 128,
  blocks: [],
  transactions: [],
  accounts: {},
  peers: 4,
  version: '1.2.0',
  network: 'sandbox',
  startTime: Date.now(),
};

function hash(data) {
  return crypto.createHash('sha256').update(data + Date.now() + Math.random()).digest('hex');
}

function randomAddr() {
  return '1' + crypto.randomBytes(20).toString('hex').slice(0, 33);
}

// Generate initial blocks
for (let i = 1; i <= 20; i++) {
  const h = state.height - 20 + i;
  state.blocks.push({
    height: h,
    hash: hash('block' + h),
    parentHash: hash('block' + (h - 1)),
    timestamp: Math.floor(Date.now() / 1000) - (20 - i) * 10,
    txCount: Math.floor(Math.random() * 5) + 1,
    proposer: 'validator-' + (i % 4),
    difficulty: 1,
    size: Math.floor(Math.random() * 4096) + 512,
  });
}

// Generate initial transactions
const txTypes = ['TRANSFER', 'CHANNEL_OPEN', 'BRIDGE_LOCK', 'MINT_STABLECOIN', 'SUBSCRIPTION_AUTH'];
for (let i = 0; i < 15; i++) {
  const block = state.blocks[Math.floor(Math.random() * state.blocks.length)];
  state.transactions.push({
    txHash: hash('tx' + i),
    blockHeight: block.height,
    blockHash: block.hash,
    from: randomAddr(),
    to: randomAddr(),
    amount: (Math.floor(Math.random() * 10000) + 100).toString(),
    fee: Math.floor(Math.random() * 100),
    type: i % txTypes.length,
    typeName: txTypes[i % txTypes.length],
    nonce: i,
    status: 'success',
    timestamp: block.timestamp,
    payload: null,
  });
}

// === Block production (every 5 seconds) ===
setInterval(() => {
  state.height++;
  const txCount = Math.floor(Math.random() * 4) + 1;
  const newBlock = {
    height: state.height,
    hash: hash('block' + state.height),
    parentHash: state.blocks[state.blocks.length - 1]?.hash || hash('genesis'),
    timestamp: Math.floor(Date.now() / 1000),
    txCount,
    proposer: 'validator-' + (state.height % 4),
    difficulty: 1,
    size: Math.floor(Math.random() * 4096) + 512,
  };
  state.blocks.push(newBlock);
  if (state.blocks.length > 100) state.blocks.shift();

  // Generate txs for new block
  for (let i = 0; i < txCount; i++) {
    const tx = {
      txHash: hash('tx' + state.height + '-' + i),
      blockHeight: newBlock.height,
      blockHash: newBlock.hash,
      from: randomAddr(),
      to: randomAddr(),
      amount: (Math.floor(Math.random() * 50000) + 100).toString(),
      fee: Math.floor(Math.random() * 200),
      type: Math.floor(Math.random() * txTypes.length),
      typeName: txTypes[Math.floor(Math.random() * txTypes.length)],
      nonce: Math.floor(Math.random() * 1000),
      status: 'success',
      timestamp: newBlock.timestamp,
      payload: null,
    };
    state.transactions.push(tx);
    if (state.transactions.length > 200) state.transactions.shift();
  }
}, 5000);

// === Explorer API ===
app.get('/api/status', (req, res) => {
  const elapsed = (Date.now() - state.startTime) / 1000;
  res.json({
    height: state.height,
    peers: state.peers,
    version: state.version,
    network: state.network,
    tps: Math.round(state.transactions.length / Math.max(elapsed, 1) * 100) / 100,
  });
});

app.get('/api/blocks', (req, res) => {
  const limit = parseInt(req.query.limit) || 20;
  const blocks = [...state.blocks].reverse().slice(0, limit);
  res.json(blocks);
});

app.get('/api/blocks/:height', (req, res) => {
  const h = parseInt(req.params.height);
  const block = state.blocks.find(b => b.height === h);
  if (!block) return res.status(404).json({ error: 'Block not found' });
  res.json(block);
});

app.get('/api/tx', (req, res) => {
  const limit = parseInt(req.query.limit) || 20;
  const txs = [...state.transactions].reverse().slice(0, limit);
  res.json(txs);
});

app.get('/api/tx/:hash', (req, res) => {
  const tx = state.transactions.find(t => t.txHash === req.params.hash);
  if (!tx) return res.status(404).json({ error: 'Transaction not found' });
  res.json(tx);
});

app.get('/api/account/:address', (req, res) => {
  const addr = req.params.address;
  const txs = state.transactions.filter(t => t.from === addr || t.to === addr);
  const balance = txs.reduce((sum, t) => {
    if (t.to === addr) return sum + parseInt(t.amount);
    if (t.from === addr) return sum - parseInt(t.amount);
    return sum;
  }, 1000000);
  res.json({
    address: addr,
    publicKeyHash: crypto.createHash('ripemd160').update(addr).digest('hex'),
    balance: Math.max(balance, 0).toString(),
    nonce: txs.filter(t => t.from === addr).length,
    txCount: txs.length,
  });
});

app.get('/api/address/:address/transactions', (req, res) => {
  const addr = req.params.address;
  const limit = parseInt(req.query.limit) || 50;
  const txs = state.transactions
    .filter(t => t.from === addr || t.to === addr)
    .reverse()
    .slice(0, limit)
    .map(t => ({
      tx_hash: t.txHash,
      amount: parseInt(t.amount),
      height: t.blockHeight,
      from: t.from,
      to: t.to,
      datetime: new Date(t.timestamp * 1000).toISOString().replace('T', ' ').slice(0, 19),
      type: t.to === addr ? '+' : '-',
    }));
  res.json(txs);
});

// === Mock Core RPC (for Gateway integration) ===
app.get('/height', (req, res) => {
  res.json({ message: 'SUCCESS', data: state.height, statusCode: 2000 });
});

app.get('/transactionConfirmed', (req, res) => {
  res.json({ message: 'SUCCESS', data: 2, statusCode: 2100 });
});

app.post('/sendTransaction', (req, res) => {
  const txHash = hash('submitted-tx');
  res.json({ message: 'SUCCESS', data: txHash, statusCode: 2000 });
});

app.post('/sendNonce', (req, res) => {
  res.json({ message: 'SUCCESS', data: Math.floor(Math.random() * 100), statusCode: 2000 });
});

app.get('/peers/status', (req, res) => {
  res.json({
    message: 'SUCCESS',
    data: { peers: state.peers, self: 'sandbox-node-0', p2pMode: 'grpc', enableDiscovery: true },
    code: 2000,
  });
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log(`\n  NexusChain Demo Server running at http://localhost:${PORT}`);
  console.log(`  Explorer API:  http://localhost:${PORT}/api/status`);
  console.log(`  Core RPC:      http://localhost:${PORT}/height`);
  console.log(`  Block interval: 5s\n`);
});