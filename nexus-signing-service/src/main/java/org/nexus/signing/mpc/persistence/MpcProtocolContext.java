package org.nexus.signing.mpc.persistence;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * MPC 协议上下文实体。
 *
 * <p>记录单个会话在单个轮次的中间状态（如本地 k_i、MtA 中间结果、聚合 R 等），
 * 用于：</p>
 * <ul>
 *   <li>崩溃恢复：节点崩溃后可从最新上下文恢复，无需重启整个会话。</li>
 *   <li>审计：保留每轮中间状态用于事后追查恶意参与者。</li>
 *   <li>WAL 配合：与 {@code WriteAheadLog} 协同保证消息不丢失。</li>
 * </ul>
 *
 * <p>{@code state} 是键值对集合，序列化为 JSON 存入关系库
 * （JPA {@code @Convert} 或 JSONB 列）。</p>
 */
public class MpcProtocolContext {

    /** 自增 ID（JPA 生成）。 */
    private Long id;

    /** 会话 ID。 */
    private String sessionId;

    /** 轮次号。 */
    private int round;

    /** 参与者 ID（该上下文所属的本地参与者）。 */
    private String participantId;

    /** 中间状态键值对。 */
    private Map<String, String> state;

    /** 创建时间。 */
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }
    public String getParticipantId() { return participantId; }
    public void setParticipantId(String participantId) { this.participantId = participantId; }
    public Map<String, String> getState() { return state; }
    public void setState(Map<String, String> state) { this.state = state; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}