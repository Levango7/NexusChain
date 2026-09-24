package org.nexus.gateway.alert;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 告警事件服务。
 *
 * <p>P1-1 架构修复：将 {@link AlertEventRepository} 的数据访问从
 * {@link AlertController} 下沉到本服务，Controller 只负责 HTTP 编排，
 * 不再直接依赖 Repository（符合分层架构约束）。</p>
 *
 * <p>注意：{@link AlertEngine} 仍按原样直接注入 Repository 进行事件持久化，
 * 本服务不影响其行为。</p>
 */
@Service
public class AlertEventService {

    private final AlertEventRepository eventRepository;

    public AlertEventService(AlertEventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /**
     * 查询指定时间之后产生的告警事件（按时间倒序）。
     *
     * @param since 起始时间（不含）
     * @return 事件列表
     */
    public List<AlertEvent> findRecentEvents(LocalDateTime since) {
        return eventRepository.findByTimestampAfterOrderByTimestampDesc(since);
    }

    /**
     * 标记指定告警事件为已解决。
     *
     * @param id 事件 ID
     * @return 更新后的事件；事件不存在时返回空
     */
    @Transactional
    public Optional<AlertEvent> resolve(Long id) {
        return eventRepository.findById(id)
                .map(event -> {
                    event.setResolved(true);
                    event.setResolvedAt(LocalDateTime.now());
                    return eventRepository.save(event);
                });
    }
}