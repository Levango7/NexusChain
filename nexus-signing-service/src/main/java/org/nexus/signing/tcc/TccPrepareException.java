package org.nexus.signing.tcc;

/**
 * TCC Try 阶段预锁定失败异常。
 *
 * <p>Phase 3 任务 #62：在 {@link SigningTccActionImpl#prepareSignTransfer} 中，
 * 当 nonce 获取失败、平台 keystore 未配置、fromPubkey 不匹配、address 校验失败、
 * 或 nonce 锁定冲突时抛出。Try 阶段抛异常会触发 Seata 全局事务回滚
 * （TM 调用所有分支的 Cancel 方法）。</p>
 *
 * <p>Confirm/Cancel 阶段不抛本异常（需保证幂等，返回 false 让 TM 重试）。</p>
 */
public class TccPrepareException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TccPrepareException(String message) {
        super(message);
    }

    public TccPrepareException(String message, Throwable cause) {
        super(message, cause);
    }
}