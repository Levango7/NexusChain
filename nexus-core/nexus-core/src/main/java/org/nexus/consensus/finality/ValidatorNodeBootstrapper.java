package org.nexus.consensus.finality;

import org.apache.commons.codec.binary.Hex;
import org.nexus.consensus.pos.PosConsensusEngine;
import org.nexus.consensus.pos.StakingService;
import org.nexus.consensus.pos.Validator;
import org.nexus.consensus.pos.ValidatorRegistry;
import org.nexus.consensus.pos.ValidatorStatus;
import org.nexus.keystore.crypto.KeyPair;
import org.nexus.keystore.wallet.KeystoreAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 验证人节点启动引导器（NexFinality 多节点闭环的最后拼图）。
 *
 * <p><b>解决的问题</b>：{@link PosConsensusEngine} 默认生成<b>随机签名密钥</b>
 * （无 keystore 注入接线，README 自述为待办），且其 `propose()` 要求引擎公钥
 * 与某注册验证人对应，否则恒返回 null（拒绝出块）→ 无法真实出块 → 无法驱动
 * 最终性投票。</p>
 *
 * <p><b>方案</b>：应用启动就绪后，读取引擎的 {@code getSigningKeyPair()}，
 * 用其公钥推导本节点验证人地址，自动注册到 {@link ValidatorRegistry} 并质押
 * （自举为本节点验证人）。据此：</p>
 * <ul>
 *   <li>单节点：出块 → epoch 边界自动投票 → 100% 权重 → 立即 FINALIZED 闭环</li>
 *   <li>多节点：各节点自举注册 + P2P 广播投票加权 → 跨节点汇聚最终化</li>
 * </ul>
 *
 * <p>启用条件：{@code nexus.finality.self-register=true}（默认 true）且 pos 模式。
 * 尚未接入 keystore 文件加载（生产需将配置密钥替换随机密钥，见 ADR-031）。</p>
 */
@Component
@ConditionalOnProperty(name = "nexus.consensus.mode", havingValue = "pos")
public class ValidatorNodeBootstrapper implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(ValidatorNodeBootstrapper.class);

    private final PosConsensusEngine engine;
    private final ValidatorRegistry validatorRegistry;
    private final StakingService stakingService;
    private final FinalityCoordinator finalityCoordinator;
    private final boolean selfRegisterEnabled;
    private final BigDecimal selfStake;

    public ValidatorNodeBootstrapper(PosConsensusEngine engine,
                                     ValidatorRegistry validatorRegistry,
                                     StakingService stakingService,
                                     FinalityCoordinator finalityCoordinator,
                                     @Value("${nexus.finality.self-register:true}") boolean selfRegisterEnabled,
                                     @Value("${nexus.finality.self-stake:1000}") BigDecimal selfStake) {
        this.engine = engine;
        this.validatorRegistry = validatorRegistry;
        this.stakingService = stakingService;
        this.finalityCoordinator = finalityCoordinator;
        this.selfRegisterEnabled = selfRegisterEnabled;
        this.selfStake = selfStake;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!selfRegisterEnabled) {
            log.info("ValidatorNodeBootstrapper disabled (nexus.finality.self-register=false)");
            return;
        }
        KeyPair keyPair = engine.getSigningKeyPair();
        if (keyPair == null) {
            log.error("Self-register aborted: engine has no signing key pair");
            return;
        }
        String pubHex = Hex.encodeHexString(keyPair.getPublicKey().getBytes());
        String address = KeystoreAction.pubkeyToAddress(keyPair.getPublicKey().getBytes(), (byte) 0x01);

        Validator existing = validatorRegistry.getValidator(address);
        if (existing != null) {
            log.info("Self-register skipped: validator already registered address={}", address);
            return;
        }
        boolean ok = validatorRegistry.register(address, pubHex, selfStake, 0.1);
        if (!ok) {
            log.error("Self-register FAILED: address={} stake={}", address, selfStake);
            return;
        }
        stakingService.stake(address, selfStake);
        Validator v = validatorRegistry.getValidator(address);
        if (v != null && v.getStatus() != ValidatorStatus.ACTIVE) {
            v.setStatus(ValidatorStatus.ACTIVE);
        }
        // 点亮最终性投票链路：协调器需知道本节点地址才能对检查点投票
        if (finalityCoordinator != null) {
            finalityCoordinator.setSelfValidatorAddress(address);
        }
        log.info("✅ Self-registered as validator: address={} pub={} stake={} (finality coordinator armed)",
                address, pubHex, selfStake);
    }
}