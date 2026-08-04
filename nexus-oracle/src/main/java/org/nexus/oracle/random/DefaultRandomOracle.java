package org.nexus.oracle.random;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * {@link RandomOracle} 默认骨架实现（VRF 方案）。
 *
 * <p>当前为占位实现：
 * <ul>
 *   <li>{@link #generateRandom} 返回空证明</li>
 *   <li>{@link #verifyRandom} 一律返回 false</li>
 * </ul>
 *
 * <p>后续接入具体 VRF 库（如 ECVRF / chainlink VRF v2）后填充算法。
 */
@Slf4j
@Service
public class DefaultRandomOracle implements RandomOracle {

    @Override
    public RandomProof generateRandom(String seed) {
        // TODO: 调用 VRF 库基于本节点私钥对 seed 求值，产出 (random, proof, signature)
        log.debug("generateRandom skeleton invoked: seed={}", seed);
        return RandomProof.builder()
                .seed(seed)
                .random(null)
                .proof(null)
                .signature(null)
                .build();
    }

    @Override
    public boolean verifyRandom(String random, String proof) {
        // TODO: 用生成者公钥校验 VRF proof 与 random 的对应关系
        log.debug("verifyRandom skeleton invoked: random={}, proof={}", random, proof);
        return false;
    }
}