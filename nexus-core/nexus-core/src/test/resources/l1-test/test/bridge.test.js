/**
 * NexusChain 跨链桥合约 Hardhat 单元测试。
 *
 * <p>验证 BridgeSource / BridgeTarget / ERC20Mock 的完整跨链资产转移流程，
 * 包括 lock/unlock/mint/burn、签名验证、幂等性、角色控制等。</p>
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li>lock：用户 approve 后 lock，检查余额和事件</li>
 *   <li>mint：relayer 签名后 mint，检查 wrapped 余额</li>
 *   <li>burn：用户 burn，检查事件</li>
 *   <li>unlock：relayer 签名后 unlock，检查余额</li>
 *   <li>幂等性：重复 nonce 应 revert</li>
 *   <li>签名验证：错误签名应 revert</li>
 *   <li>角色控制：非 owner 不能管理 relayer</li>
 * </ul>
 *
 * @since 2.1
 */
const { expect } = require("chai");
const { ethers } = require("hardhat");


describe("BridgeSource + BridgeTarget 跨链桥套件", function () {
  // ==================== 公共变量 ====================
  let token;
  let bridgeSource;
  let bridgeTarget;
  let deployer;     // owner & 默认 relayer
  let relayer;      // 授权 relayer
  let user;         // 普通用户
  let other;        // 其他地址

  const INITIAL_SUPPLY = ethers.parseEther("1000000");
  const TARGET_CHAIN_ID = 137n; // Polygon

  beforeEach(async function () {
    // relayer 用独立 ethers.Wallet（不连接 provider），以便用 signingKey 直接签名。
    // 合约使用非标准 EIP-191 前缀（小写 "signed"），hardhat signer 的 signMessage
    // 用标准前缀（大写 "Signed"）无法匹配，故 relayer 必须是 Wallet。
    // relayer 仅签名不发送交易（unlock/mint 由 deployer 调用），无需连接 provider。
    [deployer, , user, other] = await ethers.getSigners();
    relayer = ethers.Wallet.createRandom();

    // 部署 ERC20Mock
    const ERC20Mock = await ethers.getContractFactory("ERC20Mock");
    token = await ERC20Mock.deploy("Nexus Test Token", "NXT", 18, INITIAL_SUPPLY);
    await token.waitForDeployment();

    // 给 user 转一些代币
    await token.transfer(user.address, ethers.parseEther("10000"));

    // 部署 BridgeSource，初始 relayer 为 relayer.address
    const BridgeSource = await ethers.getContractFactory("BridgeSource");
    bridgeSource = await BridgeSource.deploy(relayer.address);
    await bridgeSource.waitForDeployment();

    // 部署 BridgeTarget，初始 relayer 为 relayer.address
    const BridgeTarget = await ethers.getContractFactory("BridgeTarget");
    bridgeTarget = await BridgeTarget.deploy(relayer.address);
    await bridgeTarget.waitForDeployment();
  });

  // ==================== 签名工具函数 ====================

  /**
   * 对 32 字节摘要直接进行 ECDSA 签名（不加 EIP-191 前缀）。
   *
   * <p>背景：合约 _recoverSigner 期望对已加前缀的 ethSignedHash 直接 ecrecover，
   * 即签名时不再加任何前缀。而 ethers 的 signer.signMessage 会自动加标准
   * EIP-191 前缀（"\x19Ethereum Signed Message:\n32"，大写 S），与合约使用的
   * 非标准前缀（"\x19Ethereum signed message:\n32"，小写 s）不一致。
   * 因此这里通过 ethers.Wallet.signingKey 直接对摘要签名，输出 65 字节 r+s+v。</p>
   *
   * <p>对于 hardhat signer（无 signingKey 属性，如 "错误签名应 revert" 测试中的
   * non-relayer），回退到 signMessage——此时签名与合约前缀不匹配，恢复的地址
   * 既不是签名者也不是 relayer，合约会 revert "signer not relayer"，正好符合
   * 错误签名测试的预期。</p>
   *
   * @param {ethers.Wallet|ethers.Signer} signer 签名者（Wallet 或 hardhat signer）
   * @param {string} digestHex 32 字节摘要 hex（已加合约前缀的 ethSignedHash）
   * @returns {Promise<string>} 65 字节签名 hex
   */
  async function signDigest(signer, digestHex) {
    if (signer.signingKey) {
      // ethers.Wallet / HDNodeWallet：直接用 signingKey 对摘要签名（不加前缀）
      const sig = signer.signingKey.sign(digestHex);
      // 拼接 r(32) + s(32) + v(1) = 65 字节
      return ethers.hexlify(
        ethers.concat([sig.r, sig.s, ethers.toBeArray(sig.v)])
      );
    }
    // hardhat signer：回退到 signMessage（标准 EIP-191 前缀，与合约不匹配，
    // 仅用于 "错误签名应 revert" 测试场景，恢复地址不会是 relayer）
    return await signer.signMessage(ethers.getBytes(digestHex));
  }

  /**
   * 生成 mint 操作的签名。
   * @param {ethers.Signer} signer relayer 签名者
   * @param {string} tokenAddress 代币地址
   * @param {string} recipient 接收者地址
   * @param {bigint} amount 金额
   * @param {string} nonceHex nonce（bytes32 hex）
   * @returns {Promise<string>} 65 字节签名 hex
   */
  async function signMint(signer, tokenAddress, recipient, amount, nonceHex) {
    const tokenAddr = await Promise.resolve(tokenAddress);
    const recipientAddr = await Promise.resolve(recipient);
    const messageHash = ethers.keccak256(
      ethers.solidityPacked(
        ["address", "address", "uint256", "bytes32", "string"],
        [tokenAddr, recipientAddr, amount, nonceHex, "MINT"]
      )
    );
    // 合约使用非标准前缀 "\x19Ethereum signed message:\n32"（小写 s），
    // 与 ethers.signMessage 的标准前缀（大写 S）不一致，
    // 因此手动构造 ethSignedHash 并用 SigningKey 直接签名。
    const ethSignedHash = ethers.keccak256(
      ethers.concat([
        ethers.toUtf8Bytes("\x19Ethereum signed message:\n32"),
        ethers.getBytes(messageHash),
      ])
    );
    return await signDigest(signer, ethSignedHash);
  }

  /**
   * 生成 unlock 操作的签名。
   * @param {ethers.Signer} signer relayer 签名者
   * @param {string} tokenAddress 代币地址
   * @param {string} recipient 接收者地址
   * @param {bigint} amount 金额
   * @param {string} nonceHex nonce（bytes32 hex）
   * @returns {Promise<string>} 65 字节签名 hex
   */
  async function signUnlock(signer, tokenAddress, recipient, amount, nonceHex) {
    const tokenAddr = await Promise.resolve(tokenAddress);
    const recipientAddr = await Promise.resolve(recipient);
    const messageHash = ethers.keccak256(
      ethers.solidityPacked(
        ["address", "address", "uint256", "bytes32", "string"],
        [tokenAddr, recipientAddr, amount, nonceHex, "UNLOCK"]
      )
    );
    // 同 signMint：手动构造合约前缀的 ethSignedHash 并直接签名
    const ethSignedHash = ethers.keccak256(
      ethers.concat([
        ethers.toUtf8Bytes("\x19Ethereum signed message:\n32"),
        ethers.getBytes(messageHash),
      ])
    );
    return await signDigest(signer, ethSignedHash);
  }

  // ==================== BridgeSource 测试 ====================
  describe("BridgeSource", function () {
    describe("lock", function () {
      it("应正确锁定资产并触发 Locked 事件", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("lock-1");
        const recipient = other.address;

        // user 先 approve 给桥合约
        await token.connect(user).approve(bridgeSource.getAddress(), amount);

        const userBefore = await token.balanceOf(user.address);
        const bridgeBefore = await token.balanceOf(bridgeSource.getAddress());

        await expect(
          bridgeSource.connect(user).lock(
            token.getAddress(),
            recipient,
            amount,
            TARGET_CHAIN_ID,
            nonce
          )
        )
          .to.emit(bridgeSource, "Locked")
          .withArgs(
            token.getAddress(),
            user.address,
            recipient,
            amount,
            TARGET_CHAIN_ID,
            nonce
          );

        expect(await token.balanceOf(user.address)).to.equal(userBefore - amount);
        expect(await token.balanceOf(bridgeSource.getAddress())).to.equal(bridgeBefore + amount);
        expect(await bridgeSource.processedNonces(nonce)).to.be.true;
      });

      it("未 approve 应 revert", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("lock-no-approve");

        await expect(
          bridgeSource.connect(user).lock(
            token.getAddress(),
            other.address,
            amount,
            TARGET_CHAIN_ID,
            nonce
          )
        ).to.be.revertedWith("ERC20Mock: insufficient allowance");
      });

      it("零金额应 revert", async function () {
        const nonce = ethers.id("lock-zero");
        await expect(
          bridgeSource.connect(user).lock(
            token.getAddress(),
            other.address,
            0n,
            TARGET_CHAIN_ID,
            nonce
          )
        ).to.be.revertedWith("BridgeSource: zero amount");
      });

      it("重复 nonce 应 revert（幂等性）", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("lock-dup");

        await token.connect(user).approve(bridgeSource.getAddress(), amount * 2n);
        await bridgeSource.connect(user).lock(
          token.getAddress(),
          other.address,
          amount,
          TARGET_CHAIN_ID,
          nonce
        );

        await expect(
          bridgeSource.connect(user).lock(
            token.getAddress(),
            other.address,
            amount,
            TARGET_CHAIN_ID,
            nonce
          )
        ).to.be.revertedWith("BridgeSource: nonce already processed");
      });
    });

    describe("unlock", function () {
      it("应正确释放资产并触发 Unlocked 事件", async function () {
        // 先 lock 一些资产到桥合约
        const lockAmount = ethers.parseEther("500");
        const lockNonce = ethers.id("unlock-setup");
        await token.connect(user).approve(bridgeSource.getAddress(), lockAmount);
        await bridgeSource.connect(user).lock(
          token.getAddress(),
          other.address,
          lockAmount,
          TARGET_CHAIN_ID,
          lockNonce
        );

        // relayer 签名 unlock
        const unlockAmount = ethers.parseEther("100");
        const unlockNonce = ethers.id("unlock-1");
        const signature = await signUnlock(
          relayer,
          token.getAddress(),
          other.address,
          unlockAmount,
          unlockNonce
        );

        const recipientBefore = await token.balanceOf(other.address);
        const bridgeBefore = await token.balanceOf(bridgeSource.getAddress());

        await expect(
          bridgeSource.unlock(
            token.getAddress(),
            other.address,
            unlockAmount,
            unlockNonce,
            signature
          )
        )
          .to.emit(bridgeSource, "Unlocked")
          .withArgs(
            token.getAddress(),
            other.address,
            unlockAmount,
            unlockNonce,
            relayer.address
          );

        expect(await token.balanceOf(other.address)).to.equal(recipientBefore + unlockAmount);
        expect(await token.balanceOf(bridgeSource.getAddress())).to.equal(bridgeBefore - unlockAmount);
        expect(await bridgeSource.processedNonces(unlockNonce)).to.be.true;
      });

      it("错误签名应 revert", async function () {
        const unlockAmount = ethers.parseEther("100");
        const unlockNonce = ethers.id("unlock-bad-sig");
        // 用 non-relayer 签名
        const badSignature = await signUnlock(
          other,
          token.getAddress(),
          user.address,
          unlockAmount,
          unlockNonce
        );

        await expect(
          bridgeSource.unlock(
            token.getAddress(),
            user.address,
            unlockAmount,
            unlockNonce,
            badSignature
          )
        ).to.be.revertedWith("BridgeSource: signer not relayer");
      });

      it("重复 nonce 应 revert（幂等性）", async function () {
        // 先 lock
        const lockAmount = ethers.parseEther("500");
        const lockNonce = ethers.id("unlock-dup-setup");
        await token.connect(user).approve(bridgeSource.getAddress(), lockAmount);
        await bridgeSource.connect(user).lock(
          token.getAddress(),
          other.address,
          lockAmount,
          TARGET_CHAIN_ID,
          lockNonce
        );

        const unlockAmount = ethers.parseEther("100");
        const unlockNonce = ethers.id("unlock-dup");
        const signature = await signUnlock(
          relayer,
          token.getAddress(),
          other.address,
          unlockAmount,
          unlockNonce
        );

        await bridgeSource.unlock(
          token.getAddress(),
          other.address,
          unlockAmount,
          unlockNonce,
          signature
        );

        await expect(
          bridgeSource.unlock(
            token.getAddress(),
            other.address,
            unlockAmount,
            unlockNonce,
            signature
          )
        ).to.be.revertedWith("BridgeSource: nonce already processed");
      });

      it("无效签名长度应 revert", async function () {
        const unlockNonce = ethers.id("unlock-bad-len");
        await expect(
          bridgeSource.unlock(
            token.getAddress(),
            other.address,
            ethers.parseEther("100"),
            unlockNonce,
            "0x1234" // 短签名
          )
        ).to.be.revertedWith("BridgeSource: invalid signature length");
      });
    });

    describe("relayer 管理", function () {
      it("owner 应能添加 relayer", async function () {
        await expect(bridgeSource.addRelayer(user.address))
          .to.emit(bridgeSource, "RelayerAdded")
          .withArgs(user.address);
        expect(await bridgeSource.isRelayer(user.address)).to.be.true;
      });

      it("owner 应能移除 relayer", async function () {
        await bridgeSource.addRelayer(user.address);
        await expect(bridgeSource.removeRelayer(user.address))
          .to.emit(bridgeSource, "RelayerRemoved")
          .withArgs(user.address);
        expect(await bridgeSource.isRelayer(user.address)).to.be.false;
      });

      it("非 owner 不能添加 relayer", async function () {
        await expect(
          bridgeSource.connect(user).addRelayer(other.address)
        ).to.be.revertedWith("BridgeSource: not owner");
      });

      it("重复添加 relayer 应 revert", async function () {
        await bridgeSource.addRelayer(user.address);
        await expect(
          bridgeSource.addRelayer(user.address)
        ).to.be.revertedWith("BridgeSource: already relayer");
      });
    });
  });

  // ==================== BridgeTarget 测试 ====================
  describe("BridgeTarget", function () {
    describe("mint", function () {
      it("应正确铸造 wrapped 资产并触发 Minted 事件", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("mint-1");
        const signature = await signMint(
          relayer,
          token.getAddress(),
          user.address,
          amount,
          nonce
        );

        const wrappedBefore = await bridgeTarget.getWrappedBalance(
          token.getAddress(),
          user.address
        );

        await expect(
          bridgeTarget.mint(
            token.getAddress(),
            user.address,
            amount,
            nonce,
            signature
          )
        )
          .to.emit(bridgeTarget, "Minted")
          .withArgs(
            token.getAddress(),
            user.address,
            amount,
            nonce,
            relayer.address
          );

        expect(
          await bridgeTarget.getWrappedBalance(token.getAddress(), user.address)
        ).to.equal(wrappedBefore + amount);
        expect(await bridgeTarget.processedNonces(nonce)).to.be.true;
      });

      it("错误签名应 revert", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("mint-bad-sig");
        const badSignature = await signMint(
          other,
          token.getAddress(),
          user.address,
          amount,
          nonce
        );

        await expect(
          bridgeTarget.mint(
            token.getAddress(),
            user.address,
            amount,
            nonce,
            badSignature
          )
        ).to.be.revertedWith("BridgeTarget: signer not relayer");
      });

      it("重复 nonce 应 revert（幂等性）", async function () {
        const amount = ethers.parseEther("100");
        const nonce = ethers.id("mint-dup");
        const signature = await signMint(
          relayer,
          token.getAddress(),
          user.address,
          amount,
          nonce
        );

        await bridgeTarget.mint(
          token.getAddress(),
          user.address,
          amount,
          nonce,
          signature
        );

        await expect(
          bridgeTarget.mint(
            token.getAddress(),
            user.address,
            amount,
            nonce,
            signature
          )
        ).to.be.revertedWith("BridgeTarget: nonce already processed");
      });

      it("零金额应 revert", async function () {
        const nonce = ethers.id("mint-zero");
        const signature = await signMint(
          relayer,
          token.getAddress(),
          user.address,
          0n,
          nonce
        );

        await expect(
          bridgeTarget.mint(
            token.getAddress(),
            user.address,
            0n,
            nonce,
            signature
          )
        ).to.be.revertedWith("BridgeTarget: zero amount");
      });
    });

    describe("burn", function () {
      it("应正确销毁 wrapped 资产并触发 Burned 事件", async function () {
        // 先 mint 一些 wrapped 资产给 user
        const mintAmount = ethers.parseEther("500");
        const mintNonce = ethers.id("burn-setup");
        const mintSig = await signMint(
          relayer,
          token.getAddress(),
          user.address,
          mintAmount,
          mintNonce
        );
        await bridgeTarget.mint(
          token.getAddress(),
          user.address,
          mintAmount,
          mintNonce,
          mintSig
        );

        // user burn
        const burnAmount = ethers.parseEther("100");
        const burnNonce = ethers.id("burn-1");
        const targetRecipient = other.address;

        const wrappedBefore = await bridgeTarget.getWrappedBalance(
          token.getAddress(),
          user.address
        );

        await expect(
          bridgeTarget.connect(user).burn(
            token.getAddress(),
            burnAmount,
            burnNonce,
            targetRecipient
          )
        )
          .to.emit(bridgeTarget, "Burned")
          .withArgs(
            token.getAddress(),
            user.address,
            targetRecipient,
            burnAmount,
            burnNonce
          );

        expect(
          await bridgeTarget.getWrappedBalance(token.getAddress(), user.address)
        ).to.equal(wrappedBefore - burnAmount);
        expect(await bridgeTarget.processedNonces(burnNonce)).to.be.true;
      });

      it("余额不足应 revert", async function () {
        const burnAmount = ethers.parseEther("100");
        const burnNonce = ethers.id("burn-insufficient");

        await expect(
          bridgeTarget.connect(user).burn(
            token.getAddress(),
            burnAmount,
            burnNonce,
            other.address
          )
        ).to.be.revertedWith("BridgeTarget: insufficient wrapped balance");
      });

      it("重复 nonce 应 revert（幂等性）", async function () {
        // 先 mint
        const mintAmount = ethers.parseEther("500");
        const mintNonce = ethers.id("burn-dup-setup");
        const mintSig = await signMint(
          relayer,
          token.getAddress(),
          user.address,
          mintAmount,
          mintNonce
        );
        await bridgeTarget.mint(
          token.getAddress(),
          user.address,
          mintAmount,
          mintNonce,
          mintSig
        );

        const burnAmount = ethers.parseEther("100");
        const burnNonce = ethers.id("burn-dup");

        await bridgeTarget.connect(user).burn(
          token.getAddress(),
          burnAmount,
          burnNonce,
          other.address
        );

        await expect(
          bridgeTarget.connect(user).burn(
            token.getAddress(),
            burnAmount,
            burnNonce,
            other.address
          )
        ).to.be.revertedWith("BridgeTarget: nonce already processed");
      });
    });

    describe("relayer 管理", function () {
      it("owner 应能添加 relayer", async function () {
        await expect(bridgeTarget.addRelayer(user.address))
          .to.emit(bridgeTarget, "RelayerAdded")
          .withArgs(user.address);
        expect(await bridgeTarget.isRelayer(user.address)).to.be.true;
      });

      it("非 owner 不能移除 relayer", async function () {
        await bridgeTarget.addRelayer(user.address);
        await expect(
          bridgeTarget.connect(other).removeRelayer(user.address)
        ).to.be.revertedWith("BridgeTarget: not owner");
      });
    });
  });

  // ==================== 端到端流程测试 ====================
  describe("端到端跨链流程", function () {
    it("lock → mint → burn → unlock 完整流程", async function () {
      // ---- 1. 源链 lock ----
      const amount = ethers.parseEther("50");
      const lockNonce = ethers.id("e2e-lock");
      await token.connect(user).approve(bridgeSource.getAddress(), amount);

      await bridgeSource.connect(user).lock(
        token.getAddress(),
        other.address,
        amount,
        TARGET_CHAIN_ID,
        lockNonce
      );
      expect(await token.balanceOf(bridgeSource.getAddress())).to.equal(amount);

      // ---- 2. 目标链 mint ----
      const mintNonce = ethers.id("e2e-mint");
      const mintSig = await signMint(
        relayer,
        token.getAddress(),
        other.address,
        amount,
        mintNonce
      );
      await bridgeTarget.mint(
        token.getAddress(),
        other.address,
        amount,
        mintNonce,
        mintSig
      );
      expect(
        await bridgeTarget.getWrappedBalance(token.getAddress(), other.address)
      ).to.equal(amount);

      // ---- 3. 目标链 burn（other 销毁 wrapped 资产，赎回源链） ----
      const burnNonce = ethers.id("e2e-burn");
      await bridgeTarget.connect(other).burn(
        token.getAddress(),
        amount,
        burnNonce,
        user.address  // 赎回到 user
      );
      expect(
        await bridgeTarget.getWrappedBalance(token.getAddress(), other.address)
      ).to.equal(0n);

      // ---- 4. 源链 unlock（relayer 释放资产给 user） ----
      const unlockNonce = ethers.id("e2e-unlock");
      const unlockSig = await signUnlock(
        relayer,
        token.getAddress(),
        user.address,
        amount,
        unlockNonce
      );
      const userBefore = await token.balanceOf(user.address);

      await bridgeSource.unlock(
        token.getAddress(),
        user.address,
        amount,
        unlockNonce,
        unlockSig
      );

      expect(await token.balanceOf(user.address)).to.equal(userBefore + amount);
      expect(await token.balanceOf(bridgeSource.getAddress())).to.equal(0n);
    });
  });
});