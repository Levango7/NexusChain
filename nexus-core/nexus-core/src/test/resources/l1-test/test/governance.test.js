/**
 * NexusChain 治理合约套件 Hardhat 单元测试。
 *
 * <p>覆盖 TimelockController + NexusGovernor + GovernanceTargetMock 完整链路：
 * 提案 → 投票 → 法定人数判定 → 排队 → timelock 到期 → 执行 → 状态变更。</p>
 *
 * <p>对应 Java 侧 GovernanceService / GovernanceExecutor / TimelockController
 * 的链上行为验证。Java 侧端到端集成测试见
 * {@code org.nexus.governance.OnChainGovernanceIntegrationTest}（待新增）。</p>
 *
 * @since 2.1
 */
const { expect } = require("chai");
const { ethers } = require("hardhat");

describe("NexusChain Governance Suite", function () {
  // ==================== 公共变量 ====================
  let timelock;
  let governor;
  let target;
  let deployer;
  let proposer;
  let voter1;
  let voter2;
  let voter3;
  let nonProposer;
  let nonExecutor;

  // 治理参数
  const MIN_DELAY = 3600; // 1 小时
  const VOTING_PERIOD = 100; // 100 区块
  // quorum 阈值：测试中通过 setVotingWeight 设置模拟权重，故阈值用绝对值
  const QUORUM_THRESHOLD = ethers.parseEther("100");

  // 角色常量
  let PROPOSER_ROLE;
  let EXECUTOR_ROLE;

  // ==================== 部署辅助 ====================
  async function deploySuite() {
    [deployer, proposer, voter1, voter2, voter3, nonProposer, nonExecutor] =
      await ethers.getSigners();

    // 1. 部署 TimelockController（初始 PROPOSER/EXECUTOR = deployer + proposer）
    const TimelockController = await ethers.getContractFactory("TimelockController");
    timelock = await TimelockController.deploy(
      MIN_DELAY,
      [deployer.address, proposer.address],
      [deployer.address, proposer.address]
    );
    await timelock.waitForDeployment();

    PROPOSER_ROLE = await timelock.PROPOSER_ROLE();
    EXECUTOR_ROLE = await timelock.EXECUTOR_ROLE();

    // 2. 部署 NexusGovernor
    const NexusGovernor = await ethers.getContractFactory("NexusGovernor");
    governor = await NexusGovernor.deploy(
      await timelock.getAddress(),
      VOTING_PERIOD,
      QUORUM_THRESHOLD
    );
    await governor.waitForDeployment();

    // 3. 部署 GovernanceTargetMock
    const GovernanceTargetMock = await ethers.getContractFactory("GovernanceTargetMock");
    target = await GovernanceTargetMock.deploy();
    await target.waitForDeployment();

    // 4. 授予 Governor 在 TimelockController 上的 PROPOSER/EXECUTOR 角色
    await (await timelock.grantRole(PROPOSER_ROLE, await governor.getAddress())).wait();
    await (await timelock.grantRole(EXECUTOR_ROLE, await governor.getAddress())).wait();
  }

  /**
   * 推进 n 个区块（Hardhat automine 默认开启，每个交易推进 1 区块，
   * 但有时需要显式推进以结束投票期）。
   */
  async function advanceBlocks(n) {
    for (let i = 0; i < n; i++) {
      await ethers.provider.send("evm_mine", []);
    }
  }

  /**
   * 推进时间（秒）+ 出一个区块。
   */
  async function advanceTime(seconds) {
    await ethers.provider.send("evm_increaseTime", [seconds]);
    await ethers.provider.send("evm_mine", []);
  }

  /**
   * 设置模拟投票权重并启用模拟模式。
   */
  async function setupMockWeights(weights) {
    // weights: { [address]: bigint }
    for (const [addr, w] of Object.entries(weights)) {
      await governor.setVotingWeight(addr, w);
    }
  }

  beforeEach(async function () {
    await deploySuite();
  });

  // ============================================================
  // 1. TimelockController 单元测试
  // ============================================================
  describe("TimelockController", function () {
    it("应正确部署并赋予初始角色", async function () {
      expect(await timelock.minDelay()).to.equal(MIN_DELAY);
      expect(await timelock.hasRole(PROPOSER_ROLE, deployer.address)).to.be.true;
      expect(await timelock.hasRole(PROPOSER_ROLE, proposer.address)).to.be.true;
      expect(await timelock.hasRole(EXECUTOR_ROLE, deployer.address)).to.be.true;
      expect(await timelock.hasRole(PROPOSER_ROLE, nonProposer.address)).to.be.false;
    });

    it("PROPOSER 应能 schedule 操作并触发事件", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      const value = 0n;
      const delay = MIN_DELAY;

      // 先取当前区块时间，schedule 交易会被 automine 打包到下一区块，
      // timestamp 通常 +1，但不同 Hardhat 版本可能不同。这里采用事后验证策略。
      const tx = await timelock.schedule(targetAddr, data, value, delay);
      const receipt = await tx.wait();

      // 找到 OperationScheduled 事件
      const evt = receipt.logs.find((l) => {
        try {
          return timelock.interface.parseLog(l).name === "OperationScheduled";
        } catch {
          return false;
        }
      });
      expect(evt).to.not.be.undefined;
      const parsed = timelock.interface.parseLog(evt);
      expect(parsed.args.target).to.equal(targetAddr);
      expect(parsed.args.value).to.equal(value);
      expect(parsed.args.delay).to.equal(delay);

      // 验证 operationId = hashOperation(target, data, value, scheduleTime)
      // scheduleTime = eta - delay
      const scheduleTime = parsed.args.eta - BigInt(delay);
      const expectedId = await timelock.hashOperation.staticCall(
        targetAddr,
        data,
        value,
        scheduleTime
      );
      expect(parsed.args.operationId).to.equal(expectedId);

      // 链上状态校验
      expect(await timelock.operationScheduleTime(parsed.args.operationId)).to.equal(scheduleTime);
      expect(await timelock.getOperationState(parsed.args.operationId)).to.equal(1); // Pending
    });

    it("非 PROPOSER 不能 schedule", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      await expect(
        timelock.connect(nonProposer).schedule(targetAddr, data, 0n, MIN_DELAY)
      ).to.be.revertedWith("TimelockController: unauthorized");
    });

    it("delay 小于 minDelay 应 revert", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      await expect(
        timelock.schedule(targetAddr, data, 0n, MIN_DELAY - 1)
      ).to.be.revertedWith("TimelockController: delay below minimum");
    });

    it("未到期 execute 应 revert", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      const value = 0n;
      const delay = MIN_DELAY;

      const tx = await timelock.schedule(targetAddr, data, value, delay);
      const receipt = await tx.wait();
      // 从事件中提取 operationId
      const evt = receipt.logs.find((l) => {
        try {
          return timelock.interface.parseLog(l).name === "OperationScheduled";
        } catch {
          return false;
        }
      });
      const parsed = timelock.interface.parseLog(evt);
      const operationId = parsed.args.operationId;

      // 未推进时间，直接 execute 应 revert
      await expect(
        timelock.executeById(operationId, targetAddr, data, value)
      ).to.be.revertedWith("TimelockController: operation not yet mature");
    });

    it("到期后 execute 应成功并改变目标状态", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      const value = 0n;
      const delay = MIN_DELAY;

      const tx = await timelock.schedule(targetAddr, data, value, delay);
      const receipt = await tx.wait();
      const evt = receipt.logs.find((l) => {
        try {
          return timelock.interface.parseLog(l).name === "OperationScheduled";
        } catch {
          return false;
        }
      });
      const parsed = timelock.interface.parseLog(evt);
      const operationId = parsed.args.operationId;

      // 推进时间到期
      await advanceTime(delay + 1);

      // 检查状态为 Ready (2)
      expect(await timelock.getOperationState(operationId)).to.equal(2); // Ready

      // 执行
      await expect(timelock.executeById(operationId, targetAddr, data, value))
        .to.emit(timelock, "OperationExecuted");

      expect(await target.value()).to.equal(42n);
      expect(await timelock.getOperationState(operationId)).to.equal(3); // Done
    });

    it("PROPOSER 应能 cancel 已排度操作", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      const value = 0n;

      const tx = await timelock.schedule(targetAddr, data, value, MIN_DELAY);
      const receipt = await tx.wait();
      const evt = receipt.logs.find((l) => {
        try {
          return timelock.interface.parseLog(l).name === "OperationScheduled";
        } catch {
          return false;
        }
      });
      const operationId = timelock.interface.parseLog(evt).args.operationId;

      await expect(timelock.cancel(operationId))
        .to.emit(timelock, "OperationCancelled")
        .withArgs(operationId);

      expect(await timelock.getOperationState(operationId)).to.equal(4); // Cancelled
    });

    it("非 EXECUTOR 不能 execute", async function () {
      const targetAddr = await target.getAddress();
      const data = target.interface.encodeFunctionData("setValue", [42n]);
      const value = 0n;

      const tx = await timelock.schedule(targetAddr, data, value, MIN_DELAY);
      const receipt = await tx.wait();
      const evt = receipt.logs.find((l) => {
        try {
          return timelock.interface.parseLog(l).name === "OperationScheduled";
        } catch {
          return false;
        }
      });
      const operationId = timelock.interface.parseLog(evt).args.operationId;

      await advanceTime(MIN_DELAY + 1);

      await expect(
        timelock.connect(nonExecutor).executeById(operationId, targetAddr, data, value)
      ).to.be.revertedWith("TimelockController: unauthorized");
    });
  });

  // ============================================================
  // 2. NexusGovernor 提案与投票
  // ============================================================
  describe("NexusGovernor propose & vote", function () {
    it("应能创建提案并触发 ProposalCreated 事件", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      const description = "Set value to 42";

      // propose 交易会被 automine 打包到下一区块，startBlock = 当前区块 + 1
      const currentBlock = await ethers.provider.getBlockNumber();
      const expectedStartBlock = currentBlock + 1;

      await expect(
        governor.propose([targetAddr], [calldata], [0n], description)
      )
        .to.emit(governor, "ProposalCreated")
        .withArgs(
          1n,
          deployer.address,
          [targetAddr],
          [0n],
          expectedStartBlock,
          expectedStartBlock + VOTING_PERIOD,
          description
        );

      expect(await governor.proposalCount()).to.equal(1n);

      // 提案状态应为 Active (0)
      expect(await governor.proposalState(1n)).to.equal(0);
    });

    it("targets/calldatas/values 长度不一致应 revert", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await expect(
        governor.propose([targetAddr], [calldata, calldata], [0n], "bad")
      ).to.be.revertedWith("NexusGovernor: length mismatch");
    });

    it("空提案应 revert", async function () {
      await expect(
        governor.propose([], [], [], "empty")
      ).to.be.revertedWith("NexusGovernor: empty proposal");
    });

    it("应能投票并触发 VoteCast 事件", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      // 设置模拟权重
      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });

      // 投 For (support=1)
      await expect(governor.connect(voter1).castVote(1n, 1))
        .to.emit(governor, "VoteCast")
        .withArgs(1n, voter1.address, 1, ethers.parseEther("100"));

      // 检查票数
      const prop = await governor.getProposal(1n);
      expect(prop.forVotes).to.equal(ethers.parseEther("100"));
      expect(prop.againstVotes).to.equal(0n);
      expect(prop.abstainVotes).to.equal(0n);

      // 检查投票记录
      expect(await governor.getVote(1n, voter1.address)).to.equal(2); // 2=For
    });

    it("重复投票应 revert", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });

      await governor.connect(voter1).castVote(1n, 1);
      await expect(
        governor.connect(voter1).castVote(1n, 1)
      ).to.be.revertedWith("NexusGovernor: already voted");
    });

    it("无效 support 值应 revert", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });

      await expect(
        governor.connect(voter1).castVote(1n, 3)
      ).to.be.revertedWith("NexusGovernor: invalid support");
    });

    it("投票期结束后投票应 revert", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });

      // 推进区块超过投票期
      await advanceBlocks(VOTING_PERIOD + 1);

      await expect(
        governor.connect(voter1).castVote(1n, 1)
      ).to.be.revertedWith("NexusGovernor: voting not active");
    });
  });

  // ============================================================
  // 3. 法定人数与提案状态
  // ============================================================
  describe("quorum & proposal state", function () {
    it("投票不足 quorum 应 Defeated", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      // quorum=100 ETH，仅投 50 ETH
      await setupMockWeights({
        [voter1.address]: ethers.parseEther("50")
      });
      await governor.connect(voter1).castVote(1n, 1); // For

      // 推进区块结束投票
      await advanceBlocks(VOTING_PERIOD + 1);

      // forVotes=50 < quorum=100 → Defeated (1)
      expect(await governor.proposalState(1n)).to.equal(1); // Defeated
    });

    it("forVotes <= againstVotes 应 Defeated", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("150"),
        [voter2.address]: ethers.parseEther("150")
      });
      await governor.connect(voter1).castVote(1n, 1); // For 150
      await governor.connect(voter2).castVote(1n, 0); // Against 150

      await advanceBlocks(VOTING_PERIOD + 1);

      // forVotes=150 == againstVotes=150 → Defeated
      expect(await governor.proposalState(1n)).to.equal(1); // Defeated
    });

    it("投票达到 quorum 且 forVotes > againstVotes 应 Succeeded", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100"),
        [voter2.address]: ethers.parseEther("50")
      });
      await governor.connect(voter1).castVote(1n, 1); // For 100
      await governor.connect(voter2).castVote(1n, 0); // Against 50

      await advanceBlocks(VOTING_PERIOD + 1);

      // forVotes=100 >= quorum=100 且 > againstVotes=50 → Succeeded (2)
      expect(await governor.proposalState(1n)).to.equal(2); // Succeeded
    });
  });

  // ============================================================
  // 4. 提案取消
  // ============================================================
  describe("cancel", function () {
    it("提案者应能取消提案", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      // 用 proposer 账户创建提案
      await governor.connect(proposer).propose([targetAddr], [calldata], [0n], "test");

      await expect(governor.connect(proposer).cancel(1n))
        .to.emit(governor, "ProposalCanceled")
        .withArgs(1n);

      expect(await governor.proposalState(1n)).to.equal(5); // Canceled
    });

    it("非提案者且非 owner 不能取消", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.connect(proposer).propose([targetAddr], [calldata], [0n], "test");

      await expect(
        governor.connect(nonProposer).cancel(1n)
      ).to.be.revertedWith("NexusGovernor: not authorized");
    });

    it("owner 应能取消任意提案", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.connect(proposer).propose([targetAddr], [calldata], [0n], "test");

      await governor.cancel(1n); // deployer = owner
      expect(await governor.proposalState(1n)).to.equal(5); // Canceled
    });
  });

  // ============================================================
  // 5. 完整执行链路：propose → vote → queue → execute
  // ============================================================
  describe("full execution flow", function () {
    it("提案通过 + timelock 到期后执行应改变目标合约状态", async function () {
      const targetAddr = await target.getAddress();
      const newValue = 12345n;
      const calldata = target.interface.encodeFunctionData("setValue", [newValue]);
      await governor.propose([targetAddr], [calldata], [0n], "set value to 12345");

      // 设置投票权重，达到 quorum
      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100"),
        [voter2.address]: ethers.parseEther("50")
      });
      await governor.connect(voter1).castVote(1n, 1); // For 100
      await governor.connect(voter2).castVote(1n, 0); // Against 50

      // 结束投票期
      await advanceBlocks(VOTING_PERIOD + 1);
      expect(await governor.proposalState(1n)).to.equal(2); // Succeeded

      // 排队
      const queueTx = await governor.queue(1n);
      const queueReceipt = await queueTx.wait();
      // 找到 ProposalQueued 事件
      const queuedEvt = queueReceipt.logs.find((l) => {
        try {
          return governor.interface.parseLog(l).name === "ProposalQueued";
        } catch {
          return false;
        }
      });
      expect(queuedEvt).to.not.be.undefined;
      const operationId = governor.interface.parseLog(queuedEvt).args.operationId;

      expect(await governor.proposalState(1n)).to.equal(3); // Queued

      // timelock 未到期，execute 应 revert
      await expect(
        governor.execute(1n)
      ).to.be.revertedWith("TimelockController: operation not yet mature");

      // 推进时间到期
      await advanceTime(MIN_DELAY + 1);

      // 执行
      await expect(governor.execute(1n))
        .to.emit(governor, "ProposalExecuted")
        .withArgs(1n);

      // 验证目标合约状态已变更
      expect(await target.value()).to.equal(newValue);
      expect(await target.updateCount()).to.equal(1n);
      expect(await governor.proposalState(1n)).to.equal(4); // Executed
    });

    it("已执行提案不能再次执行", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });
      await governor.connect(voter1).castVote(1n, 1);

      await advanceBlocks(VOTING_PERIOD + 1);
      await governor.queue(1n);
      await advanceTime(MIN_DELAY + 1);
      await governor.execute(1n);

      await expect(
        governor.execute(1n)
      ).to.be.revertedWith("NexusGovernor: already executed");
    });

    it("Defeated 提案不能 queue", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.propose([targetAddr], [calldata], [0n], "test");

      // 不投票，直接结束 → Defeated
      await advanceBlocks(VOTING_PERIOD + 1);
      expect(await governor.proposalState(1n)).to.equal(1); // Defeated

      await expect(
        governor.queue(1n)
      ).to.be.revertedWith("NexusGovernor: not succeeded");
    });

    it("取消已排队提案应同时取消 timelock 操作", async function () {
      const targetAddr = await target.getAddress();
      const calldata = target.interface.encodeFunctionData("setValue", [42n]);
      await governor.connect(proposer).propose([targetAddr], [calldata], [0n], "test");

      await setupMockWeights({
        [voter1.address]: ethers.parseEther("100")
      });
      await governor.connect(voter1).castVote(1n, 1);

      await advanceBlocks(VOTING_PERIOD + 1);
      const queueTx = await governor.queue(1n);
      const queueReceipt = await queueTx.wait();
      const queuedEvt = queueReceipt.logs.find((l) => {
        try {
          return governor.interface.parseLog(l).name === "ProposalQueued";
        } catch {
          return false;
        }
      });
      const operationId = governor.interface.parseLog(queuedEvt).args.operationId;

      // 取消提案
      await governor.connect(proposer).cancel(1n);
      expect(await governor.proposalState(1n)).to.equal(5); // Canceled

      // timelock 操作应也被取消
      expect(await timelock.getOperationState(operationId)).to.equal(4); // Cancelled
    });
  });

  // ============================================================
  // 6. GovernanceTargetMock 单元测试
  // ============================================================
  describe("GovernanceTargetMock", function () {
    it("setValue 应正确修改 value 并触发事件", async function () {
      await expect(target.setValue(99n))
        .to.emit(target, "ValueChanged")
        .withArgs(0n, 99n, deployer.address);

      expect(await target.value()).to.equal(99n);
      expect(await target.getValue()).to.equal(99n);
      expect(await target.updateCount()).to.equal(1n);
    });

    it("多次 setValue 应累加 updateCount", async function () {
      await target.setValue(1n);
      await target.setValue(2n);
      await target.setValue(3n);
      expect(await target.value()).to.equal(3n);
      expect(await target.updateCount()).to.equal(3n);
    });
  });
});