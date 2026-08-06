package org.nexus.wallet.signing.mpc.persistence;

import org.nexus.wallet.signing.mpc.MpcKeyShare;

import java.util.List;
import java.util.Optional;

/**
 * MPC 密钥份额本地存储接口。
 *
 * <p><b>关键安全约束</b>：{@link MpcKeyShare#getPrivateShareHex()} 私有份额
 * <b>绝不出节点</b>，因此不能存入关系库（关系库可能被 DBA、备份、日志访问）。
 * 必须存入 <b>本地加密文件</b>，加密密钥来自环境变量或 KMS。</p>
 *
 * <p>该接口抽象本地份额存储，默认实现 {@link EncryptedFileKeyShareStore}
 * 使用 AES-GCM 加密。生产环境可替换为 KMS-backed 实现。</p>
 */
public interface MpcKeyShareStore {

    /**
     * 保存（加密）一个密钥份额到本地。
     *
     * @param share 密钥份额
     */
    void save(MpcKeyShare share);

    /**
     * 加载并解密指定参与者的密钥份额。
     *
     * @param participantId 参与者 ID
     * @return 解密后的份额（可选）
     */
    Optional<MpcKeyShare> load(String participantId);

    /**
     * 列出本地存储的所有参与者 ID（不解密份额，仅枚举文件名）。
     *
     * @return 参与者 ID 列表
     */
    List<String> listParticipantIds();

    /**
     * 删除指定参与者的份额文件。
     *
     * @param participantId 参与者 ID
     */
    void delete(String participantId);

    /**
     * 判断指定参与者的份额是否存在。
     *
     * @param participantId 参与者 ID
     * @return {@code true} iff 存在
     */
    boolean exists(String participantId);
}