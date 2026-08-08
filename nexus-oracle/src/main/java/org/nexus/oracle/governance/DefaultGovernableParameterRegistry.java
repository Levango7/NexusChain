package org.nexus.oracle.governance;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link GovernableParameterRegistry} 默认实现。
 *
 * <p>进程内参数存储，当前接受任何非空参数名与值的字符串表示。
 * 后续接入链上治理合约时替换为带范围校验（如 min/max、枚举约束）
 * 与持久化的实现。
 *
 * @since 1.9.2
 */
@Slf4j
@Service
public class DefaultGovernableParameterRegistry implements GovernableParameterRegistry {

    /** 参数存储（参数名 → 值） */
    private final Map<String, Object> parameters = new ConcurrentHashMap<>();

    @Override
    public boolean validate(String paramName, String value) {
        return paramName != null && !paramName.isBlank() && value != null;
    }

    @Override
    public boolean setParameter(String paramName, String value) {
        if (!validate(paramName, value)) {
            log.debug("Parameter set rejected: invalid name or value, name={}, value={}", paramName, value);
            return false;
        }
        parameters.put(paramName, value);
        log.info("Governable parameter set: {} = {}", paramName, value);
        return true;
    }

    @Override
    public Object getParameter(String paramName) {
        return paramName == null ? null : parameters.get(paramName);
    }

    @Override
    public Map<String, Object> snapshot() {
        return new HashMap<>(parameters);
    }

    @Override
    public void restore(Map<String, Object> snapshot) {
        parameters.clear();
        if (snapshot != null) {
            parameters.putAll(snapshot);
        }
        log.info("Governable parameters restored from snapshot ({} params)", parameters.size());
    }
}