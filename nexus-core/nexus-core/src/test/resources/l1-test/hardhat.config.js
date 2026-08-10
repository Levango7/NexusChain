/**
 * NexusChain L1 测试环境 Hardhat 配置。
 *
 * <p>用途：在 nexus-core L2 Rollup 端到端测试中提供本地 L1 节点，
 * 部署 L2Bridge 合约，供 Web3jL1ContractClient 通过 JSON-RPC 调用。</p>
 *
 * <p>启动方式：{@code npx hardhat node}（默认监听 127.0.0.1:8545，
 * 链 ID 31337，预置 20 个 10000 ETH 测试账户）。</p>
 *
 * @since 2.0
 */
require("@nomicfoundation/hardhat-toolbox");

/** @type import('hardhat/config').HardhatUserConfig */
module.exports = {
  solidity: {
    version: "0.8.20",
    settings: {
      optimizer: {
        enabled: true,
        runs: 200
      }
    }
  },
  networks: {
    // Hardhat 内置 localhost 网络（连接至 `npx hardhat node` 启动的节点）
    localhost: {
      url: "http://127.0.0.1:8545",
      chainId: 31337
    },
    // 默认 Hardhat in-process 网络（用于 `hardhat test`）
    hardhat: {
      chainId: 31337,
      // 提高默认 gas 上限以容纳挑战批次等复杂调用
      gas: 30000000,
      // 10000 ETH 预置账户
      accounts: {
        accountsBalance: "10000000000000000000000"
      }
    }
  },
  paths: {
    sources: "./contracts",
    tests: "./test",
    cache: "./cache",
    artifacts: "./artifacts"
  },
  mocha: {
    timeout: 60000
  }
};