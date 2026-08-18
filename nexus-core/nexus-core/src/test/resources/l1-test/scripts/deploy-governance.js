/**
 * NexusChain 治理合约套件部署脚本。
 *
 * <p>用法：</p>
 * <pre>
 *   npx hardhat run scripts/deploy-governance.js --network localhost
 * </pre>
 *
 * <p>部署内容：</p>
 * <ol>
 *   <li>TimelockController（minDelay=3600 秒）</li>
 *   <li>NexusGovernor（votingPeriod=100 区块, quorum=50% 票数阈值）</li>
 *   <li>GovernanceTargetMock（治理执行目标）</li>
 *   <li>设置 Governor 为 TimelockController 的 PROPOSER 和 EXECUTOR 角色</li>
 * </ol>
 *
 * <p>部署完成后，将合约地址输出到 stdout 与 {@code deployed-governance.json}，
 * 供 Java 侧 GovernanceService / GovernanceExecutor 读取并初始化链上治理客户端。</p>
 *
 * @since 2.1
 */
const fs = require("fs");
const path = require("path");
const { ethers } = require("hardhat");

async function main() {
  // 治理参数
  const minDelay = 3600; // 1 小时
  const votingPeriodBlocks = 100; // 100 区块
  const quorumThreshold = ethers.parseEther("5000"); // 5000 ETH 票数（占预置总量 1/40）

  console.log("Deploying NexusChain governance suite...");
  console.log("  network:", network.name);
  console.log("  minDelay:", minDelay, "seconds");
  console.log("  votingPeriodBlocks:", votingPeriodBlocks);
  console.log("  quorumThreshold:", quorumThreshold.toString(), "wei");

  const [deployer] = await ethers.getSigners();
  console.log("Deployer address:", deployer.address);

  // ==================== 1. 部署 TimelockController ====================
  console.log("\n[1/3] Deploying TimelockController...");
  const TimelockController = await ethers.getContractFactory("TimelockController");
  // 初始 PROPOSER/EXECUTOR 暂为 deployer，后续将 Governor 加入
  const proposers = [deployer.address];
  const executors = [deployer.address];
  const timelock = await TimelockController.deploy(minDelay, proposers, executors);
  await timelock.waitForDeployment();
  const timelockAddress = await timelock.getAddress();
  console.log("  TimelockController deployed to:", timelockAddress);

  // ==================== 2. 部署 NexusGovernor ====================
  console.log("\n[2/3] Deploying NexusGovernor...");
  const NexusGovernor = await ethers.getContractFactory("NexusGovernor");
  const governor = await NexusGovernor.deploy(
    timelockAddress,
    votingPeriodBlocks,
    quorumThreshold
  );
  await governor.waitForDeployment();
  const governorAddress = await governor.getAddress();
  console.log("  NexusGovernor deployed to:", governorAddress);

  // ==================== 3. 部署 GovernanceTargetMock ====================
  console.log("\n[3/3] Deploying GovernanceTargetMock...");
  const GovernanceTargetMock = await ethers.getContractFactory("GovernanceTargetMock");
  const target = await GovernanceTargetMock.deploy();
  await target.waitForDeployment();
  const targetAddress = await target.getAddress();
  console.log("  GovernanceTargetMock deployed to:", targetAddress);

  // ==================== 4. 设置 Governor 为 TimelockController 的 PROPOSER/EXECUTOR ====================
  console.log("\n[4/4] Granting Governor PROPOSER_ROLE and EXECUTOR_ROLE on TimelockController...");
  const PROPOSER_ROLE = await timelock.PROPOSER_ROLE();
  const EXECUTOR_ROLE = await timelock.EXECUTOR_ROLE();
  await (await timelock.grantRole(PROPOSER_ROLE, governorAddress)).wait();
  await (await timelock.grantRole(EXECUTOR_ROLE, governorAddress)).wait();
  console.log("  Roles granted.");

  // ==================== 输出部署信息 ====================
  const deployedInfo = {
    contracts: {
      TimelockController: timelockAddress,
      NexusGovernor: governorAddress,
      GovernanceTargetMock: targetAddress
    },
    deployer: deployer.address,
    network: network.name,
    params: {
      minDelay: minDelay,
      votingPeriodBlocks: votingPeriodBlocks,
      quorumThreshold: quorumThreshold.toString()
    },
    deployedAt: new Date().toISOString()
  };
  const outputPath = path.join(__dirname, "..", "deployed-governance.json");
  fs.writeFileSync(outputPath, JSON.stringify(deployedInfo, null, 2));
  console.log("\nDeployment info written to:", outputPath);

  return deployedInfo;
}

main()
  .then((info) => {
    console.log("\nDeployment succeeded:");
    console.log(JSON.stringify(info, null, 2));
    process.exit(0);
  })
  .catch((error) => {
    console.error("Deployment failed:", error);
    process.exit(1);
  });