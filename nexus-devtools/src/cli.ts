import * as fs from 'fs';
import * as path from 'path';
import * as crypto from 'crypto';

const COMMANDS = new Map<string, { desc: string; run: (args: string[]) => void }>();

COMMANDS.set('init', { desc: 'Initialize a new contract project', run: (args) => initProject(args[0] || 'my-contract') });
COMMANDS.set('compile', { desc: 'Compile contract to WASM', run: (args) => compileContract(args[0] || '.') });
COMMANDS.set('deploy', { desc: 'Deploy contract to network', run: (args) => deployContract(args[0], args[1]) });
COMMANDS.set('network:status', { desc: 'Check network status', run: (args) => networkStatus(args[0]) });
COMMANDS.set('account:create', { desc: 'Generate new account keypair', run: () => createAccount() });
COMMANDS.set('payment:create', { desc: 'Create payment via orchestration API', run: (args) => createPayment(args[0], args[1], args[2]) });

function initProject(name: string) {
  const dir = path.resolve(name);
  fs.mkdirSync(path.join(dir, 'src'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'build'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify({ name, version: '0.1.0' }, null, 2));
  fs.writeFileSync(path.join(dir, 'src', 'contract.ts'), 'export function main(a: string, p: string): string { return JSON.stringify({a,p}); }');
  console.log('Initialized: ' + dir);
}

function compileContract(dir: string) {
  const out = path.resolve(dir, 'build');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(path.join(out, 'contract.wasm'), Buffer.from('NEXWASM_v1'));
  console.log('Compiled: ' + path.join(out, 'contract.wasm'));
}

function deployContract(wasmPath?: string, rpcUrl?: string) {
  console.log('Deploying ' + (wasmPath || 'contract.wasm') + ' to ' + (rpcUrl || 'localhost:19585') + ' (dry-run)');
  console.log('Address: nexus1c_' + Date.now().toString(36));
}

function networkStatus(rpcUrl?: string) {
  console.log('Querying ' + (rpcUrl || 'http://localhost:19585') + ' ...');
  console.log('(use fetch or curl to check /height endpoint)');
}

function createAccount() {
  const kp = crypto.generateKeyPairSync('ed25519');
  const pub = kp.publicKey.export({ type: 'spki', format: 'der' });
  const priv = kp.privateKey.export({ type: 'pkcs8', format: 'der' });
  const pubHex = Buffer.from(pub).subarray(-32).toString('hex');
  const privHex = Buffer.from(priv).subarray(-32).toString('hex');
  console.log('Public:  ' + pubHex);
  console.log('Private: ' + privHex);
  console.log('Address: 1' + pubHex.substring(0, 40));
}

function createPayment(gwUrl?: string, amount?: string, currency?: string) {
  console.log('POST ' + (gwUrl || 'http://localhost:8080') + '/api/v1/payments');
  console.log('Body: ' + JSON.stringify({ amount: parseInt(amount || '1000'), currency: currency || 'NEX' }));
}

const [cmd, ...args] = process.argv.slice(2);
if (!cmd || cmd === 'help') {
  console.log('NexusChain CLI');
  for (const [n, v] of COMMANDS) console.log('  ' + n.padEnd(20) + v.desc);
} else if (COMMANDS.has(cmd)) { COMMANDS.get(cmd)!.run(args); } else { console.error('Unknown: ' + cmd); }