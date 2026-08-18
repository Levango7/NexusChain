/**
 * NexusChain 跨链桥合约部署脚本（BridgeSource + BridgeTarget + ERC20Mock）。
 *
 * <p>用法：</p>
 * <pre>
 *   npx hardhat run scripts/deploy-bridge.js --network localhost
 * </pre>
 *
 * <p>部署完成后，将合约地址输出到 stdout 与 {@code deployed-bridge.json}，
 * 供 Java 侧 {@code EthereumBridgeHandler} 通过 Web3j 调用。</p>
 *
 * <p>部署内容：</p>
 * <ul>
 *   <li>ERC20Mock — 测试用 ERC20 代币（初始供应量 1,000,000 * 10^18）</li>
 *   <li>BridgeSource — 源链桥合约，初始 relayer 为部署者</li>
 *   <li>BridgeTarget — 目标链桥合约，初始 relayer 为部署者</li>
 * </ul>
 *
 * @since 2.1
 */
const fs = require("fs");
const path = require("path");
const { ethers } = require("hardhat");

async function main() {
  const initialSupply = ethers.parseEther("1000000"); // 1,000,000 测试代币

  console.log("Deploying NexusChain bridge contracts...");
  console.log("  network:", network.name);

  const [deployer, relayer] = await ethers.getSigners();
  console.log("  deployer:", deployer.address);
  console.log("  relayer :", relayer.address);

  // ==================== 1. 部署 ERC20Mock ====================
  console.log("\n[1/3] Deploying ERC20Mock...");
  const ERC20Mock = await ethers.getContractFactory("ERC20Mock");
  const token = await ERC20Mock.deploy(
    "Nexus Test Token",   // name
    "NXT",                 // symbol
    18,                    // decimals
    initialSupply          // initialSupply 铸造给 deployer
  );
  await token.waitForDeployment();
  const tokenAddress = await token.getAddress();
  console.log("  ERC20Mock deployed to:", tokenAddress);

  // ==================== 2. 部署 BridgeSource ====================
  console.log("\n[2/3] Deploying BridgeSource...");
  const BridgeSource = await ethers.getContractFactory("BridgeSource");
  const bridgeSource = await BridgeSource.deploy(relayer.address);
  await bridgeSource.waitForDeployment();
  const bridgeSourceAddress = await bridgeSource.getAddress();
  console.log("  BridgeSource deployed to:", bridgeSourceAddress);

  // ==================== 3. 部署 BridgeTarget ====================
  console.log("\n[3/3] Deploying BridgeTarget...");
  const BridgeTarget = await ethers.getContractFactory("BridgeTarget");
  const bridgeTarget = await BridgeTarget.deploy(relayer.address);
  await bridgeTarget.waitForDeployment();
  const bridgeTargetAddress = await bridgeTarget.getAddress();
  console.log("  BridgeTarget deployed to:", bridgeTargetAddress);

  // ==================== 输出部署信息 ====================
  const deployedInfo = {
    contracts: {
      ERC20Mock: tokenAddress,
      BridgeSource: bridgeSourceAddress,
      BridgeTarget: bridgeTargetAddress
    },
    deployer: deployer.address,
    relayer: relayer.address,
    network: network.name,
    token: {
      name: "Nexus Test Token",
      symbol: "NXT",
      decimals: 18,
      initialSupply: initialSupply.toString()
    },
    deployedAt: new Date().toISOString()
  };
  const outputPath = path.join(__dirname, "..", "deployed-bridge.json");
  fs.writeFileSync(outputPath, JSON.stringify(deployedInfo, null, 2));
  console.log("\nDeployment info written to:", outputPath);
  console.log(JSON.stringify(deployedInfo, null, 2));

  return deployedInfo;
}

main()
  .then((info) => {
    console.log("\nDeployment succeeded.");
    process.exit(0);
  })
  .catch((error) => {
    console.error("Deployment failed:", error);
    process.exit(1);
  });