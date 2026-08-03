package org.nexus.bridge;

import java.util.Set;

/**
 * 桥验证者接口。
 *
 * <p>桥验证者负责对跨链操作进行签名确认，实现 N-of-M 多签安全机制。
 * 验证者集合由 nexus-consortium 共识层治理产生，桥模块通过本接口
 * 与验证者进行交互。</p>
 *
 * <h2>验证者职责</h2>
 * <ul>
 *   <li>验证源链锁定交易的真实性</li>
 *   <li>对合法的跨链操作提交签名</li>
 *   <li>监控桥状态，必要时触发暂停</li>
 *   <li>参与紧急事件响应</li>
 * </ul>
 *
 * <h2>多签机制</h2>
 * <p>跨链操作需要收集到阈值数量的验证者签名后才能执行。
 * 阈值参数由 {@link BridgeConfig} 配置。</p>
 *
 * @since 1.0.0
 */
public interface BridgeValidator {

    /**
     * 获取验证者唯一标识（通常为验证者公钥地址）。
     *
     * @return 验证者 ID
     */
    String getValidatorId();

    /**
     * 获取验证者公钥，用于签名验证。
     *
     * @return 验证者公钥（十六进制格式）
     */
    String getPublicKey();

    /**
     * 对跨链操作数据进行签名。
     *
     * <p>签名内容通常包括：源链 ID、目标链 ID、交易金额、
     * 用户地址、时间戳等关键字段的哈希摘要。</p>
     *
     * @param payload 待签名的跨链操作数据
     * @return 签名结果（十六进制格式）
     */
    String sign(byte[] payload);

    /**
     * 验证签名是否来自该验证者。
     *
     * @param payload  原始数据
     * @param signature 待验证的签名
     * @return 如果签名有效返回 {@code true}，否则返回 {@code false}
     */
    boolean verify(byte[] payload, String signature);

    /**
     * 判断该验证者是否处于活跃状态。
     *
     * <p>非活跃验证者的签名将被拒绝。验证者可能因为
     * 节点离线、被治理移除等原因变为非活跃状态。</p>
     *
     * @return 活跃返回 {@code true}，否则返回 {@code false}
     */
    boolean isActive();

    /**
     * 获取验证者的权重。
     *
     * <p>在加权多签场景下，不同验证者可能拥有不同权重。
     * 默认权重为 1，所有验证者权重相等。</p>
     *
     * @return 验证者权重
     */
    default int getWeight() {
        return 1;
    }

    /**
     * 判断给定的验证者集合是否达到多签阈值。
     *
     * @param validatorIds  参与签名的验证者 ID 集合
     * @param config        桥配置（包含阈值信息）
     * @return 达到阈值返回 {@code true}，否则返回 {@code false}
     */
    static boolean meetsThreshold(Set<String> validatorIds, BridgeConfig config) {
        if (validatorIds == null || validatorIds.isEmpty() || config == null) {
            return false;
        }
        return validatorIds.size() >= config.getSignatureThreshold();
    }
}
