/**
 * NexusChain L2Bridge 合约部署脚本。
 *
 * <p>用法：</p>
 * <pre>
 *   npx hardhat run scripts/deploy.js --network localhost
 * </pre>
 *
 * <p>部署完成后，将合约地址输出到 stdout 与 {@code deployed-address.json}，
 * 供 Java 侧 {@code L2L1EndToEndTest} 读取并初始化
 * {@code Web3jL1ContractClient}。</p>
 *
 * <p>默认挑战期 604800 秒（7 天）。</p>
 *
 * @since 2.0
 */
const fs = require("fs");
const path = require("path");
const { ethers } = require("hardhat");

async function main() {
  const challengePeriod = 604800; // 7 天

  console.log("Deploying L2Bridge contract...");
  console.log("  network:", network.name);
  console.log("  challengePeriod:", challengePeriod, "seconds");

  const L2Bridge = await ethers.getContractFactory("L2Bridge");
  const bridge = await L2Bridge.deploy(challengePeriod);
  await bridge.waitForDeployment();

  const address = await bridge.getAddress();
  console.log("L2Bridge deployed to:", address);

  // 部署者地址
  const [deployer] = await ethers.getSigners();
  console.log("Deployer address:", deployer.address);

  // 写入 deployed-address.json
  const deployedInfo = {
    contract: "L2Bridge",
    address: address,
    deployer: deployer.address,
    network: network.name,
    challengePeriod: challengePeriod,
    deployedAt: new Date().toISOString()
  };
  const outputPath = path.join(__dirname, "..", "deployed-address.json");
  fs.writeFileSync(outputPath, JSON.stringify(deployedInfo, null, 2));
  console.log("Deployment info written to:", outputPath);

  return address;
}

main()
  .then((address) => {
    console.log("Deployment succeeded:", address);
    process.exit(0);
  })
  .catch((error) => {
    console.error("Deployment failed:", error);
    process.exit(1);
  });