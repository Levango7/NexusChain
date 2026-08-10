package org.nexus.signing.mpc.persistence;

import org.nexus.signing.mpc.MpcWallet;

import java.util.List;
import java.util.Optional;

/**
 * MPC 钱包关系库持久化接口。
 *
 * <p>钱包元数据（joint public key、participants、threshold、status）持久化到
 * 关系库。该接口与 JPA Repository 接口形状一致，便于未来直接替换为
 * Spring Data JPA 实现。当前默认实现 {@link InMemoryMpcWalletRepository}
 * 用于 composite build 占位与测试。</p>
 *
 * <p><b>切换到 JPA 的步骤</b>：</p>
 * <ol>
 *   <li>在 {@code build.gradle} 添加 {@code spring-boot-starter-data-jpa} 与
 *       数据库驱动。</li>
 *   <li>给 {@link MpcWallet} 添加 {@code @Entity/@Table/@Id} 注解。</li>
 *   <li>创建 {@code JpaMpcWalletRepository extends JpaRepository<MpcWallet, String>}。</li>
 *   <li>用 {@code @Primary} 标注 JPA 实现，自动覆盖内存实现。</li>
 * </ol>
 */
public interface MpcWalletRepository {

    /**
     * 保存或更新钱包。
     *
     * @param wallet 钱包实体
     * @return 保存后的实体（含可能的 ID 填充）
     */
    MpcWallet save(MpcWallet wallet);

    /**
     * 按 ID 查找钱包。
     *
     * @param walletId 钱包 ID
     * @return 钱包实体（可选）
     */
    Optional<MpcWallet> findById(String walletId);

    /**
     * 列出所有钱包。
     *
     * @return 钱包列表
     */
    List<MpcWallet> findAll();

    /**
     * 列出指定参与者参与的所有钱包。
     *
     * @param participantId 参与者 ID
     * @return 钱包列表
     */
    List<MpcWallet> findByParticipant(String participantId);

    /**
     * 删除钱包。
     *
     * @param walletId 钱包 ID
     */
    void deleteById(String walletId);

    /**
     * 判断钱包是否存在。
     *
     * @param walletId 钱包 ID
     * @return {@code true} iff 存在
     */
    boolean existsById(String walletId);
}