package org.nexus.governance;

/**
 * 参数冲突异常。
 *
 * <p>当提交的提案试图变更某个已被其他待执行提案（PASSED/QUEUED/READY 状态）占用的参数时抛出，
 * 避免同一参数被多个未执行提案并发修改导致语义冲突。</p>
 *
 * @since 1.3
 */
public class ParameterConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ParameterConflictException(String message) {
        super(message);
    }

    public ParameterConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}