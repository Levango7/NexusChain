package org.nexus.contract.engine;

import java.util.Collections;
import java.util.List;

/**
 * 合约执行结果。
 *
 * <p>承载一次合约调用的输出，包括返回值、gas 消耗、
 * 事件日志以及错误信息。</p>
 *
 * @since 1.2
 */
public class ExecutionResult {

    /** 是否执行成功 */
    private boolean success;

    /** 返回值（可为 null） */
    private Object returnValue;

    /** 实际消耗的 gas */
    private long gasUsed;

    /** 执行过程中产生的事件日志 */
    private List<String> logs;

    /** 失败时的错误信息 */
    private String errorMessage;

    public ExecutionResult() {
        this.logs = Collections.emptyList();
    }

    public static ExecutionResult success(Object returnValue, long gasUsed, List<String> logs) {
        ExecutionResult r = new ExecutionResult();
        r.success = true;
        r.returnValue = returnValue;
        r.gasUsed = gasUsed;
        r.logs = logs == null ? Collections.emptyList() : logs;
        return r;
    }

    public static ExecutionResult failure(String errorMessage, long gasUsed) {
        ExecutionResult r = new ExecutionResult();
        r.success = false;
        r.errorMessage = errorMessage;
        r.gasUsed = gasUsed;
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Object getReturnValue() {
        return returnValue;
    }

    public void setReturnValue(Object returnValue) {
        this.returnValue = returnValue;
    }

    public long getGasUsed() {
        return gasUsed;
    }

    public void setGasUsed(long gasUsed) {
        this.gasUsed = gasUsed;
    }

    public List<String> getLogs() {
        return logs;
    }

    public void setLogs(List<String> logs) {
        this.logs = logs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}