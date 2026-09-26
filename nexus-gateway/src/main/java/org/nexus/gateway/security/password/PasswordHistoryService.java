package org.nexus.gateway.security.password;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 密码历史服务。
 *
 * <p>记录每次密码变更的历史，并检查新密码是否与最近 N 条历史重复。
 * 密码历史比对使用 bcrypt matches 方法（逐条比对哈希），确保安全性。</p>
 *
 * <p>来源：设计文档 §5.4.3 PasswordHistoryService。</p>
 */
@Service
public class PasswordHistoryService {

    private final PasswordHistoryRepository historyRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public PasswordHistoryService(PasswordHistoryRepository historyRepository) {
        this.historyRepository = historyRepository;
        this.passwordEncoder = new BCryptPasswordEncoder();
    }

    /**
     * 记录密码历史。
     *
     * @param merchantId   商户 ID
     * @param passwordHash 密码哈希（bcrypt 格式）
     */
    @Transactional
    public void recordPassword(Long merchantId, String passwordHash) {
        PasswordHistory history = new PasswordHistory();
        history.setMerchantId(merchantId);
        history.setPasswordHash(passwordHash);
        history.setCreatedAt(Instant.now());
        historyRepository.save(history);
    }

    /**
     * @deprecated 此方法存在逻辑错误：bcrypt 哈希之间无法通过 {@code matches} 正确比对。
     * 请使用 {@link #isPlaintextInHistory(Long, String, int)} 代替，该方法接受明文密码
     * 并与历史哈希正确比对。
     */
    @Deprecated(since = "Wave 12", forRemoval = true)
    @Transactional(readOnly = true)
    public boolean isPasswordInHistory(Long merchantId, String passwordHash, int historyCount) {
        List<PasswordHistory> histories = historyRepository
                .findTopNByMerchantIdOrderByCreatedAtDesc(merchantId, historyCount);
        for (PasswordHistory history : histories) {
            if (passwordEncoder.matches(passwordHash, history.getPasswordHash())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查明文密码是否与历史密码重复（推荐使用此方法）。
     *
     * <p>使用 bcrypt matches 方法将明文密码与历史哈希逐条比对，
     * 这是密码历史重复检查的正确方式。</p>
     *
     * @param merchantId      商户 ID
     * @param plaintextPassword 明文密码
     * @param historyCount    检查的历史条数
     * @return true 表示密码在历史中重复
     */
    @Transactional(readOnly = true)
    public boolean isPlaintextInHistory(Long merchantId, String plaintextPassword, int historyCount) {
        List<PasswordHistory> histories = historyRepository
                .findTopNByMerchantIdOrderByCreatedAtDesc(merchantId, historyCount);
        for (PasswordHistory history : histories) {
            if (passwordEncoder.matches(plaintextPassword, history.getPasswordHash())) {
                return true;
            }
        }
        return false;
    }
}