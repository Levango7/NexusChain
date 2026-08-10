/**
 * NexusChain L2Bridge 合约 Hardhat 单元测试。
 *
 * <p>验证 Solidity 合约基础行为，确保部署到 Hardhat 节点后可被 Java 侧
 * Web3jL1ContractClient 正确调用。Java 侧端到端测试见
 * {@code org.nexus.l2.integration.L2L1EndToEndTest}。</p>
 *
 * @since 2.0
 */
const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("L2Bridge", function () {
  let bridge;
  let deployer;
  let other;

  beforeEach(async function () {
    [deployer, other] = await ethers.getSigners();
    const L2Bridge = await ethers.getContractFactory("L2Bridge");
    bridge = await L2Bridge.deploy(604800);
    await bridge.waitForDeployment();
  });

  describe("submitStateRoot", function () {
    it("应正确提交状态根并触发事件", async function () {
      const batchId = 1n;
      const stateRoot = ethers.randomBytes(32);

      await expect(bridge.submitStateRoot(stateRoot, batchId))
        .to.emit(bridge, "StateRootSubmitted")
        .withArgs(batchId, stateRoot, deployer.address);

      expect(await bridge.batchStateRoot(batchId)).to.equal(ethers.hexlify(stateRoot));
      expect(await bridge.batchSubmitter(batchId)).to.equal(deployer.address);
      expect(await bridge.getBatchStatus(batchId)).to.equal(1); // SUBMITTED
    });

    it("应允许覆盖已提交的状态根", async function () {
      const batchId = 1n;
      const root1 = ethers.randomBytes(32);
      const root2 = ethers.randomBytes(32);

      await bridge.submitStateRoot(root1, batchId);
      await bridge.submitStateRoot(root2, batchId);

      expect(await bridge.batchStateRoot(batchId)).to.equal(ethers.hexlify(root2));
    });
  });

  describe("markBatchVerified", function () {
    it("应正确标记已提交批次为 VERIFIED", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);

      await expect(bridge.markBatchVerified(batchId))
        .to.emit(bridge, "BatchVerified")
        .withArgs(batchId, deployer.address);

      expect(await bridge.getBatchStatus(batchId)).to.equal(2); // VERIFIED
      expect(await bridge.isBatchVerified(batchId)).to.be.true;
    });

    it("未提交批次应 revert", async function () {
      await expect(bridge.markBatchVerified(999n)).to.be.revertedWith(
        "L2Bridge: batch not submitted"
      );
    });
  });

  describe("finalizeWithdraws", function () {
    it("应正确最终化已验证批次", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);
      await bridge.markBatchVerified(batchId);

      await expect(bridge.finalizeWithdraws(batchId))
        .to.emit(bridge, "WithdrawFinalized")
        .withArgs(batchId, deployer.address);

      expect(await bridge.getBatchStatus(batchId)).to.equal(3); // FINALIZED
      expect(await bridge.isWithdrawsFinalized(batchId)).to.be.true;
    });

    it("未验证批次应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);

      await expect(bridge.finalizeWithdraws(batchId)).to.be.revertedWith(
        "L2Bridge: batch not VERIFIED"
      );
    });
  });

  describe("challengeBatch", function () {
    it("应正确挑战已提交批次", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);

      const proof = [ethers.randomBytes(32), ethers.randomBytes(32)];

      await expect(bridge.challengeBatch(batchId, proof))
        .to.emit(bridge, "BatchChallenged");

      expect(await bridge.getBatchStatus(batchId)).to.equal(4); // CHALLENGED
      expect(await bridge.isBatchChallenged(batchId)).to.be.true;
      expect(await bridge.batchChallenger(batchId)).to.equal(deployer.address);
    });

    it("空证明应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);

      await expect(bridge.challengeBatch(batchId, [])).to.be.revertedWith(
        "L2Bridge: empty proof"
      );
    });

    it("已挑战批次再次挑战应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);
      const proof = [ethers.randomBytes(32)];

      await bridge.challengeBatch(batchId, proof);

      await expect(bridge.challengeBatch(batchId, proof)).to.be.revertedWith(
        "L2Bridge: batch already challenged"
      );
    });

    it("已验证批次无法挑战", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.randomBytes(32), batchId);
      await bridge.markBatchVerified(batchId);

      const proof = [ethers.randomBytes(32)];
      await expect(bridge.challengeBatch(batchId, proof)).to.be.revertedWith(
        "L2Bridge: batch already VERIFIED"
      );
    });
  });
});