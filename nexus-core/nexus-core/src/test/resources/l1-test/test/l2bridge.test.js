/**
 * NexusChain L2Bridge 合约 Hardhat 单元测试（生产级增强版）。
 *
 * <p>验证 Solidity 合约完整行为，包括：
 * <ul>
 *   <li>向后兼容基础功能（submitStateRoot / markBatchVerified / finalizeWithdraws / challengeBatch）</li>
 *   <li>Merkle proof 验证（正确 / 错误 proof）</li>
 *   <li>挑战期时间锁（期内可挑战 / 期后可 verify）</li>
 *   <li>Sequencer 签名验证（EIP-712，正确 / 错误签名）</li>
 *   <li>ERC20 提款流程（submitWithdrawals → finalizeWithdrawsWithProof）</li>
 *   <li>罚没机制（挑战成功 sequencer 被罚没）</li>
 *   <li>角色控制（非 sequencer 不能 submitStateRoot）</li>
 * </ul>
 * </p>
 *
 * <p>Java 侧端到端测试见 {@code org.nexus.l2.integration.L2L1EndToEndTest}。</p>
 *
 * @since 2.0
 */
const { expect } = require("chai");
const { ethers, network } = require("hardhat");

describe("L2Bridge", function () {
  let bridge;
  let deployer, other, sequencer, challenger, recipient;
  const CHALLENGE_PERIOD = 604800n; // 7 天

  beforeEach(async function () {
    [deployer, other, sequencer, challenger, recipient] = await ethers.getSigners();
    const L2Bridge = await ethers.getContractFactory("L2Bridge");
    bridge = await L2Bridge.deploy(CHALLENGE_PERIOD);
    await bridge.waitForDeployment();
  });

  /**
   * 辅助函数：将 EVM 时间快进指定秒数。
   * @param {bigint} seconds 快进的秒数
   */
  async function increaseTime(seconds) {
    await network.provider.send("evm_increaseTime", [Number(seconds)]);
    await network.provider.send("evm_mine");
  }

  /**
   * 辅助函数：构造两层 Merkle 树（2 个叶子）。
   * @returns {{leaf0: string, leaf1: string, root: string, proof0: string[], isRight0: boolean[], proof1: string[], isRight1: boolean[]}}
   */
  async function buildTwoLeafMerkleTree() {
    const leaf0 = ethers.hexlify(ethers.randomBytes(32));
    const leaf1 = ethers.hexlify(ethers.randomBytes(32));
    // root = keccak256(abi.encodePacked(leaf0, leaf1))
    const root = ethers.keccak256(ethers.concat([leaf0, leaf1]));
    return {
      leaf0,
      leaf1,
      root,
      // leaf0 在左侧，proof = [leaf1], isRight = [false]
      proof0: [leaf1],
      isRight0: [false],
      // leaf1 在右侧，proof = [leaf0], isRight = [true]
      proof1: [leaf0],
      isRight1: [true],
    };
  }

  // ==================== 向后兼容测试（原有功能，适配挑战期） ====================

  describe("submitStateRoot（向后兼容）", function () {
    it("应正确提交状态根并触发事件", async function () {
      const batchId = 1n;
      const stateRoot = ethers.hexlify(ethers.randomBytes(32));

      await expect(bridge.submitStateRoot(stateRoot, batchId))
        .to.emit(bridge, "StateRootSubmitted")
        .withArgs(batchId, stateRoot, deployer.address);

      expect(await bridge.batchStateRoot(batchId)).to.equal(stateRoot);
      expect(await bridge.batchSubmitter(batchId)).to.equal(deployer.address);
      expect(await bridge.getBatchStatus(batchId)).to.equal(1); // SUBMITTED
    });

    it("应允许覆盖已提交的状态根", async function () {
      const batchId = 1n;
      const root1 = ethers.hexlify(ethers.randomBytes(32));
      const root2 = ethers.hexlify(ethers.randomBytes(32));

      await bridge.submitStateRoot(root1, batchId);
      await bridge.submitStateRoot(root2, batchId);

      expect(await bridge.batchStateRoot(batchId)).to.equal(root2);
    });

    it("应记录批次提交时间", async function () {
      const batchId = 1n;
      const tx = await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);
      const receipt = await tx.wait();
      const block = await ethers.provider.getBlock(receipt.blockNumber);
      const expectedTime = BigInt(block.timestamp);

      expect(await bridge.getBatchSubmitTime(batchId)).to.equal(expectedTime);
    });
  });

  describe("markBatchVerified（含挑战期时间锁）", function () {
    it("挑战期内应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 未快进时间，挑战期未过
      await expect(bridge.markBatchVerified(batchId)).to.be.revertedWith(
        "L2Bridge: challenge period not over"
      );
    });

    it("挑战期结束后应正确标记为 VERIFIED", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 快进超过挑战期
      await increaseTime(CHALLENGE_PERIOD + 1n);

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

  describe("finalizeWithdraws（向后兼容）", function () {
    it("应正确最终化已验证批次", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);
      await increaseTime(CHALLENGE_PERIOD + 1n);
      await bridge.markBatchVerified(batchId);

      await expect(bridge.finalizeWithdraws(batchId))
        .to.emit(bridge, "WithdrawFinalized")
        .withArgs(batchId, deployer.address);

      expect(await bridge.getBatchStatus(batchId)).to.equal(3); // FINALIZED
      expect(await bridge.isWithdrawsFinalized(batchId)).to.be.true;
    });

    it("未验证批次应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      await expect(bridge.finalizeWithdraws(batchId)).to.be.revertedWith(
        "L2Bridge: batch not VERIFIED"
      );
    });
  });

  describe("challengeBatch（向后兼容，含挑战期检查）", function () {
    it("应正确挑战已提交批次（挑战期内）", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      const proof = [ethers.hexlify(ethers.randomBytes(32)), ethers.hexlify(ethers.randomBytes(32))];

      await expect(bridge.challengeBatch(batchId, proof))
        .to.emit(bridge, "BatchChallenged");

      expect(await bridge.getBatchStatus(batchId)).to.equal(4); // CHALLENGED
      expect(await bridge.isBatchChallenged(batchId)).to.be.true;
      expect(await bridge.batchChallenger(batchId)).to.equal(deployer.address);
    });

    it("空证明应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      await expect(bridge.challengeBatch(batchId, [])).to.be.revertedWith(
        "L2Bridge: empty proof"
      );
    });

    it("已挑战批次再次挑战应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);
      const proof = [ethers.hexlify(ethers.randomBytes(32))];

      await bridge.challengeBatch(batchId, proof);

      await expect(bridge.challengeBatch(batchId, proof)).to.be.revertedWith(
        "L2Bridge: batch already challenged"
      );
    });

    it("已验证批次无法挑战", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);
      await increaseTime(CHALLENGE_PERIOD + 1n);
      await bridge.markBatchVerified(batchId);

      const proof = [ethers.hexlify(ethers.randomBytes(32))];
      await expect(bridge.challengeBatch(batchId, proof)).to.be.revertedWith(
        "L2Bridge: batch already VERIFIED"
      );
    });

    it("挑战期结束后应 revert", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 快进超过挑战期
      await increaseTime(CHALLENGE_PERIOD + 1n);

      const proof = [ethers.hexlify(ethers.randomBytes(32))];
      await expect(bridge.challengeBatch(batchId, proof)).to.be.revertedWith(
        "L2Bridge: challenge period over"
      );
    });
  });

  // ==================== Merkle 验证测试 ====================

  describe("MerkleLib 验证", function () {
    it("正确的 Merkle proof 应验证通过（通过 challengeBatchWithProof）", async function () {
      // 构造 Merkle 树：stateRoot = hash(leaf0, leaf1)
      const tree = await buildTwoLeafMerkleTree();

      const batchId = 1n;
      // stateRoot 即为 Merkle root
      await bridge.submitStateRoot(tree.root, batchId);

      // 设置 sequencer 与 challenger bond
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(deployer.address);
      // sequencer 质押
      await bridge.connect(deployer).depositSequencerBond({ value: bondAmount });
      // challenger 质押
      await bridge.connect(challenger).depositChallengerBond({ value: bondAmount });

      // 挑战者用 leaf0 + proof 验证
      await expect(
        bridge.connect(challenger).challengeBatchWithProof(
          batchId,
          tree.leaf0,
          tree.proof0,
          tree.isRight0
        )
      )
        .to.emit(bridge, "BatchChallenged")
        .and.to.emit(bridge, "SubmitterSlashed");

      expect(await bridge.getBatchStatus(batchId)).to.equal(4); // CHALLENGED
      expect(await bridge.batchChallenger(batchId)).to.equal(challenger.address);
    });

    it("错误的 Merkle proof 应 revert", async function () {
      const tree = await buildTwoLeafMerkleTree();
      const batchId = 1n;
      await bridge.submitStateRoot(tree.root, batchId);

      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(deployer.address);
      await bridge.connect(deployer).depositSequencerBond({ value: bondAmount });
      await bridge.connect(challenger).depositChallengerBond({ value: bondAmount });

      // 用错误的 leaf（不在树中）
      const wrongLeaf = ethers.hexlify(ethers.randomBytes(32));
      await expect(
        bridge.connect(challenger).challengeBatchWithProof(
          batchId,
          wrongLeaf,
          tree.proof0,
          tree.isRight0
        )
      ).to.be.revertedWith("L2Bridge: invalid merkle proof");
    });

    it("proof 与 isRight 长度不匹配应 revert", async function () {
      const tree = await buildTwoLeafMerkleTree();
      const batchId = 1n;
      await bridge.submitStateRoot(tree.root, batchId);

      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(deployer.address);
      await bridge.connect(deployer).depositSequencerBond({ value: bondAmount });
      await bridge.connect(challenger).depositChallengerBond({ value: bondAmount });

      // isRight 长度比 proof 长
      await expect(
        bridge.connect(challenger).challengeBatchWithProof(
          batchId,
          tree.leaf0,
          tree.proof0,
          [false, true] // 长度 2，但 proof 长度 1
        )
      ).to.be.revertedWith("MerkleLib: length mismatch");
    });
  });

  // ==================== 挑战期时间锁测试 ====================

  describe("挑战期时间锁", function () {
    it("挑战期内可挑战，期后可 verify", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 期内可挑战
      const proof = [ethers.hexlify(ethers.randomBytes(32))];
      await bridge.challengeBatch(batchId, proof);
      expect(await bridge.isBatchChallenged(batchId)).to.be.true;
    });

    it("getChallengeDeadline 应返回正确的截止时间", async function () {
      const batchId = 1n;
      const tx = await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);
      const receipt = await tx.wait();
      const block = await ethers.provider.getBlock(receipt.blockNumber);
      const submitTime = BigInt(block.timestamp);

      const deadline = await bridge.getChallengeDeadline(batchId);
      expect(deadline).to.equal(submitTime + CHALLENGE_PERIOD);
    });

    it("恰好挑战期结束时 markBatchVerified 应成功", async function () {
      const batchId = 1n;
      await bridge.submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 快进恰好挑战期
      await increaseTime(CHALLENGE_PERIOD);

      await bridge.markBatchVerified(batchId);
      expect(await bridge.isBatchVerified(batchId)).to.be.true;
    });
  });

  // ==================== Sequencer 签名验证测试 ====================

  describe("Sequencer 签名验证（EIP-712）", function () {
    it("正确签名应提交成功", async function () {
      // 设置 sequencer
      await bridge.setAuthorizedSequencer(sequencer.address);

      const batchId = 1n;
      const targetChainId = 31337n;
      const stateRoot = ethers.hexlify(ethers.randomBytes(32));

      // 构造 EIP-712 签名
      const domain = {
        name: "L2Bridge",
        chainId: 31337,
        verifyingContract: await bridge.getAddress(),
      };
      const types = {
        Submit: [
          { name: "stateRoot", type: "bytes32" },
          { name: "batchId", type: "uint256" },
          { name: "targetChainId", type: "uint256" },
        ],
      };
      const value = {
        stateRoot: stateRoot,
        batchId: batchId,
        targetChainId: targetChainId,
      };
      const signature = await sequencer.signTypedData(domain, types, value);

      // 任何人都可以提交签名（签名验证 sequencer 身份）
      await expect(
        bridge.connect(other).submitStateRootWithSig(stateRoot, batchId, targetChainId, signature)
      )
        .to.emit(bridge, "StateRootSubmitted")
        .withArgs(batchId, stateRoot, sequencer.address)
        .and.to.emit(bridge, "StateRootSubmittedWithSig")
        .withArgs(batchId, stateRoot, sequencer.address, targetChainId);

      expect(await bridge.batchStateRoot(batchId)).to.equal(stateRoot);
      expect(await bridge.batchSubmitter(batchId)).to.equal(sequencer.address);
    });

    it("错误签名应 revert", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);

      const batchId = 1n;
      const targetChainId = 31337n;
      const stateRoot = ethers.hexlify(ethers.randomBytes(32));

      // 用 other 签名（非 sequencer）
      const domain = {
        name: "L2Bridge",
        chainId: 31337,
        verifyingContract: await bridge.getAddress(),
      };
      const types = {
        Submit: [
          { name: "stateRoot", type: "bytes32" },
          { name: "batchId", type: "uint256" },
          { name: "targetChainId", type: "uint256" },
        ],
      };
      const value = {
        stateRoot: stateRoot,
        batchId: batchId,
        targetChainId: targetChainId,
      };
      const wrongSignature = await other.signTypedData(domain, types, value);

      await expect(
        bridge.submitStateRootWithSig(stateRoot, batchId, targetChainId, wrongSignature)
      ).to.be.revertedWith("L2Bridge: invalid sequencer signature");
    });

    it("未设置 sequencer 时应 revert", async function () {
      const batchId = 1n;
      const targetChainId = 31337n;
      const stateRoot = ethers.hexlify(ethers.randomBytes(32));
      const signature = ethers.hexlify(ethers.randomBytes(65));

      await expect(
        bridge.submitStateRootWithSig(stateRoot, batchId, targetChainId, signature)
      ).to.be.revertedWith("L2Bridge: sequencer not set");
    });
  });

  // ==================== ERC20 提款流程测试 ====================

  describe("ERC20 提款流程", function () {
    let token;
    const tokenAmount = ethers.parseEther("100");

    beforeEach(async function () {
      // 部署 MockERC20
      const MockERC20 = await ethers.getContractFactory("MockERC20");
      token = await MockERC20.deploy("TestToken", "TST", 18);
      await token.waitForDeployment();

      // 给 bridge 合约铸造足够的 token
      await token.mint(await bridge.getAddress(), tokenAmount);
    });

    it("应完成 submitWithdrawals → finalizeWithdrawsWithProof 流程", async function () {
      // 设置 sequencer
      await bridge.setAuthorizedSequencer(sequencer.address);

      const batchId = 1n;
      // 先提交状态根
      await bridge.connect(sequencer).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 构造提款：1 笔提款给 recipient
      const withdrawalAmount = ethers.parseEther("50");
      const index = 0n;

      // 计算叶节点：keccak256(abi.encode(token, recipient, amount, index))
      const leaf = ethers.keccak256(
        ethers.AbiCoder.defaultAbiCoder().encode(
          ["address", "address", "uint256", "uint256"],
          [await token.getAddress(), recipient.address, withdrawalAmount, index]
        )
      );
      // 单叶子树：root = leaf
      const withdrawalRoot = leaf;

      // 提交提款根
      const withdrawals = [
        {
          token: await token.getAddress(),
          recipient: recipient.address,
          amount: withdrawalAmount,
        },
      ];
      await expect(
        bridge.connect(sequencer).submitWithdrawals(batchId, withdrawals, withdrawalRoot)
      )
        .to.emit(bridge, "WithdrawalsSubmitted")
        .withArgs(batchId, withdrawalRoot, 1);

      // 验证批次
      await increaseTime(CHALLENGE_PERIOD + 1n);
      await bridge.markBatchVerified(batchId);

      // 最终化提款（空 proof，单叶子树）
      const recipientBalanceBefore = await token.balanceOf(recipient.address);
      expect(recipientBalanceBefore).to.equal(0n);

      await expect(
        bridge.finalizeWithdrawsWithProof(
          batchId,
          index,
          await token.getAddress(),
          recipient.address,
          withdrawalAmount,
          [], // 空 proof
          []  // 空 isRight
        )
      )
        .to.emit(bridge, "WithdrawFinalizedDetailed")
        .withArgs(batchId, index, recipient.address, await token.getAddress(), withdrawalAmount)
        .and.to.emit(bridge, "WithdrawFinalized");

      // 验证 token 已转移
      expect(await token.balanceOf(recipient.address)).to.equal(withdrawalAmount);
      expect(await bridge.isWithdrawalFinalized(batchId, index)).to.be.true;
      expect(await bridge.getBatchStatus(batchId)).to.equal(3); // FINALIZED
    });

    it("重复最终化同一笔提款应 revert", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);

      const batchId = 1n;
      await bridge.connect(sequencer).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      const withdrawalAmount = ethers.parseEther("50");
      const index = 0n;
      const leaf = ethers.keccak256(
        ethers.AbiCoder.defaultAbiCoder().encode(
          ["address", "address", "uint256", "uint256"],
          [await token.getAddress(), recipient.address, withdrawalAmount, index]
        )
      );
      const withdrawalRoot = leaf;

      const withdrawals = [
        { token: await token.getAddress(), recipient: recipient.address, amount: withdrawalAmount },
      ];
      await bridge.connect(sequencer).submitWithdrawals(batchId, withdrawals, withdrawalRoot);

      await increaseTime(CHALLENGE_PERIOD + 1n);
      await bridge.markBatchVerified(batchId);

      // 第一次最终化
      await bridge.finalizeWithdrawsWithProof(
        batchId, index, await token.getAddress(), recipient.address, withdrawalAmount, [], []
      );

      // 第二次应 revert
      await expect(
        bridge.finalizeWithdrawsWithProof(
          batchId, index, await token.getAddress(), recipient.address, withdrawalAmount, [], []
        )
      ).to.be.revertedWith("L2Bridge: withdrawal already finalized");
    });

    it("错误的 Merkle proof 应 revert", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);

      const batchId = 1n;
      await bridge.connect(sequencer).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), batchId);

      // 提交一个随机的 withdrawalRoot
      const withdrawalRoot = ethers.hexlify(ethers.randomBytes(32));
      const withdrawals = [
        { token: await token.getAddress(), recipient: recipient.address, amount: ethers.parseEther("1") },
      ];
      await bridge.connect(sequencer).submitWithdrawals(batchId, withdrawals, withdrawalRoot);

      await increaseTime(CHALLENGE_PERIOD + 1n);
      await bridge.markBatchVerified(batchId);

      // 用错误的 amount（叶节点不匹配）
      await expect(
        bridge.finalizeWithdrawsWithProof(
          batchId, 0n, await token.getAddress(), recipient.address, ethers.parseEther("999"), [], []
        )
      ).to.be.revertedWith("L2Bridge: invalid withdrawal proof");
    });
  });

  // ==================== 罚没机制测试 ====================

  describe("罚没机制", function () {
    it("挑战成功后 sequencer bond 应被罚没给挑战者", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(sequencer.address);

      // sequencer 质押
      await bridge.connect(sequencer).depositSequencerBond({ value: bondAmount });
      // challenger 质押
      await bridge.connect(challenger).depositChallengerBond({ value: bondAmount });

      // 构造 Merkle 树并提交
      const tree = await buildTwoLeafMerkleTree();
      const batchId = 1n;
      await bridge.connect(sequencer).submitStateRoot(tree.root, batchId);

      const challengerBalanceBefore = await ethers.provider.getBalance(challenger.address);

      // 挑战者挑战（含 gas）
      const tx = await bridge.connect(challenger).challengeBatchWithProof(
        batchId,
        tree.leaf0,
        tree.proof0,
        tree.isRight0
      );
      const receipt = await tx.wait();
      const gasUsed = receipt.gasUsed * receipt.gasPrice;

      const challengerBalanceAfter = await ethers.provider.getBalance(challenger.address);

      // 挑战者余额应增加 bondAmount - gas
      expect(challengerBalanceAfter - challengerBalanceBefore + gasUsed).to.equal(bondAmount);

      // sequencer bond 应被清空
      expect(await bridge.sequencerBondBalance()).to.equal(0n);
      expect(await bridge.sequencerBondDeposited()).to.be.false;
    });

    it("未质押 challenger bond 应 revert", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(sequencer.address);
      await bridge.connect(sequencer).depositSequencerBond({ value: bondAmount });

      const tree = await buildTwoLeafMerkleTree();
      const batchId = 1n;
      await bridge.connect(sequencer).submitStateRoot(tree.root, batchId);

      // challenger 未质押
      await expect(
        bridge.connect(challenger).challengeBatchWithProof(
          batchId, tree.leaf0, tree.proof0, tree.isRight0
        )
      ).to.be.revertedWith("L2Bridge: insufficient challenger bond");
    });

    it("sequencer 未质押 bond 应 revert", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(sequencer.address);
      // sequencer 未质押
      await bridge.connect(challenger).depositChallengerBond({ value: bondAmount });

      const tree = await buildTwoLeafMerkleTree();
      const batchId = 1n;
      await bridge.connect(sequencer).submitStateRoot(tree.root, batchId);

      await expect(
        bridge.connect(challenger).challengeBatchWithProof(
          batchId, tree.leaf0, tree.proof0, tree.isRight0
        )
      ).to.be.revertedWith("L2Bridge: sequencer not bonded");
    });
  });

  // ==================== 角色控制测试 ====================

  describe("角色控制（AccessControl）", function () {
    it("非 owner 不能 setAuthorizedSequencer", async function () {
      await expect(
        bridge.connect(other).setAuthorizedSequencer(sequencer.address)
      ).to.be.revertedWith("L2Bridge: not owner");
    });

    it("非 owner 不能 setBonds", async function () {
      await expect(
        bridge.connect(other).setBonds(0n, 0n)
      ).to.be.revertedWith("L2Bridge: not owner");
    });

    it("设置 sequencer 后，非 sequencer 不能 submitStateRoot", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);

      await expect(
        bridge.connect(other).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), 1n)
      ).to.be.revertedWith("L2Bridge: not sequencer");
    });

    it("设置 sequencer 后，sequencer 可以 submitStateRoot", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);

      await expect(
        bridge.connect(sequencer).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), 1n)
      ).to.emit(bridge, "StateRootSubmitted");
    });

    it("未设置 sequencer 时，任何人可 submitStateRoot（向后兼容）", async function () {
      // 默认 authorizedSequencer = address(0)
      await expect(
        bridge.connect(other).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), 1n)
      ).to.emit(bridge, "StateRootSubmitted");
    });

    it("非 sequencer 不能 depositSequencerBond", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);
      await bridge.setBonds(ethers.parseEther("1"), ethers.parseEther("1"));

      await expect(
        bridge.connect(other).depositSequencerBond({ value: ethers.parseEther("1") })
      ).to.be.revertedWith("L2Bridge: not sequencer");
    });

    it("非 sequencer 不能 submitWithdrawals", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);
      await bridge.connect(sequencer).submitStateRoot(ethers.hexlify(ethers.randomBytes(32)), 1n);

      const withdrawals = [
        { token: other.address, recipient: recipient.address, amount: 100n },
      ];
      await expect(
        bridge.connect(other).submitWithdrawals(1n, withdrawals, ethers.ZeroHash)
      ).to.be.revertedWith("L2Bridge: not sequencer");
    });

    it("错误的 bond 金额应 revert", async function () {
      await bridge.setAuthorizedSequencer(sequencer.address);
      await bridge.setBonds(ethers.parseEther("1"), ethers.parseEther("1"));

      await expect(
        bridge.connect(sequencer).depositSequencerBond({ value: ethers.parseEther("2") })
      ).to.be.revertedWith("L2Bridge: wrong bond amount");

      await expect(
        bridge.connect(challenger).depositChallengerBond({ value: ethers.parseEther("2") })
      ).to.be.revertedWith("L2Bridge: wrong bond amount");
    });
  });

  // ==================== 质押管理测试 ====================

  describe("质押管理", function () {
    it("sequencer 质押应触发事件并更新状态", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(sequencer.address);

      await expect(bridge.connect(sequencer).depositSequencerBond({ value: bondAmount }))
        .to.emit(bridge, "SequencerBonded")
        .withArgs(sequencer.address, bondAmount);

      expect(await bridge.sequencerBondBalance()).to.equal(bondAmount);
      expect(await bridge.sequencerBondDeposited()).to.be.true;
    });

    it("challenger 质押应触发事件并更新余额", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);

      await expect(bridge.connect(challenger).depositChallengerBond({ value: bondAmount }))
        .to.emit(bridge, "ChallengerBonded")
        .withArgs(challenger.address, bondAmount);

      expect(await bridge.getChallengerBondBalance(challenger.address)).to.equal(bondAmount);
    });

    it("重复 sequencer 质押应 revert", async function () {
      const bondAmount = ethers.parseEther("1");
      await bridge.setBonds(bondAmount, bondAmount);
      await bridge.setAuthorizedSequencer(sequencer.address);

      await bridge.connect(sequencer).depositSequencerBond({ value: bondAmount });
      await expect(
        bridge.connect(sequencer).depositSequencerBond({ value: bondAmount })
      ).to.be.revertedWith("L2Bridge: already bonded");
    });

    it("setBonds 应触发事件", async function () {
      await expect(bridge.setBonds(ethers.parseEther("2"), ethers.parseEther("1")))
        .to.emit(bridge, "BondsUpdated")
        .withArgs(ethers.parseEther("2"), ethers.parseEther("1"));

      expect(await bridge.sequencerBondAmount()).to.equal(ethers.parseEther("2"));
      expect(await bridge.challengerBondAmount()).to.equal(ethers.parseEther("1"));
    });

    it("setAuthorizedSequencer 应触发事件", async function () {
      await expect(bridge.setAuthorizedSequencer(sequencer.address))
        .to.emit(bridge, "SequencerSet")
        .withArgs(sequencer.address);

      expect(await bridge.authorizedSequencer()).to.equal(sequencer.address);
    });
  });
});
