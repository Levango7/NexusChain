//! MPC-P2-F5 session_id 调用方身份绑定。
//!
//! 分布式安全模型下，每个 session_id 在创建时绑定调用方 `party_id` 与 `party_index`，
//! 后续操作（Sign/Aggregate）验证调用方身份与 session 绑定身份一致，防止跨方
//! 重放或冒用 session_id 提取其他方份额。
//!
//! `SessionManager` 持有 `HashMap<session_id, SessionInfo>`，提供创建、查询、
//! 状态转换与身份校验方法。线程安全通过 `Mutex` 保护（锁不跨 .await）。

use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// Session 状态机。
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SessionState {
    /// DKG 进行中或已完成（会话已缓存，可执行 Sign）。
    DkgReady,
    /// Sign 进行中或已完成（部分签名已缓存，可执行 Aggregate）。
    SignReady,
    /// 签名聚合完成（最终签名已产出）。
    Aggregated,
    /// 会话已过期或显式销毁。
    Closed,
}

/// 单个 session 的元数据与状态。
///
/// **密钥材料安全擦除说明**：`SessionInfo` 仅持有 session 元数据
///（session_id、party_id、party_index、时间戳、状态），**不含私钥份额
/// 或其他密钥材料**，故未实现 `Zeroize`。此外 `created_at` / `updated_at`
/// 为 `std::time::Instant`，该类型未实现 `Zeroize`，阻碍派生。
/// 实际密钥材料（私钥份额、签名份额）存储于 `DkgSession` / `SignCache`
///（见 gg20.rs），已实现 `Zeroize`。
#[derive(Clone, Debug)]
pub struct SessionInfo {
    /// session_id（全局唯一，由调用方传入）。
    pub session_id: String,
    /// 创建该 session 的调用方 party_id（来自 PartyConfig）。
    pub party_id: String,
    /// 创建该 session 的调用方 party_index（来自 DKG/Sign RPC 请求）。
    pub party_index: usize,
    /// 创建时间（用于过期判断）。
    pub created_at: Instant,
    /// 最后更新时间。
    pub updated_at: Instant,
    /// 当前状态。
    pub state: SessionState,
}

/// Session 默认过期阈值（30 分钟无活动）。
///
/// 超过此时长未活动的 session 在 `cleanup_expired_sessions` 中被回收。
/// 同时回收 `SessionState::Closed` 状态的 session。
pub const DEFAULT_SESSION_TIMEOUT: Duration = Duration::from_secs(1800);

/// Session 数量默认上限（防 DoS）。
///
/// `create_session` 在 `sessions.len() >= max_sessions` 时拒绝创建新 session。
pub const DEFAULT_MAX_SESSIONS: usize = 100;

/// Session 管理器（MPC-P2-F5 身份绑定）。
///
/// 持有 `session_id -> SessionInfo` 映射，提供：
///   * `create_session`：创建 session 并绑定调用方身份。
///   * `verify_caller`：校验调用方身份与 session 绑定身份一致。
///   * `transition`：状态转换（DkgReady → SignReady → Aggregated）。
///   * `get`/`remove`：查询与销毁。
///   * `cleanup_expired_sessions`：回收 Closed 或超时无活动的 session（中10）。
///
/// 线程安全：内部 `Mutex<HashMap>`，锁在同步代码块内获取释放，不跨 .await。
///
/// # DoS 防护（中11）
/// `max_sessions` 限制同时存在的 session 数量，默认 `DEFAULT_MAX_SESSIONS`(100)。
/// `create_session` 在达到上限时返回错误，防止攻击者无限制创建 session 耗尽内存。
pub struct SessionManager {
    sessions: Mutex<HashMap<String, SessionInfo>>,
    /// Session 过期阈值（无活动超过此时长则回收）。
    session_timeout: Duration,
    /// Session 数量上限（防 DoS）。
    max_sessions: usize,
}

impl Default for SessionManager {
    fn default() -> Self {
        Self::new()
    }
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            session_timeout: DEFAULT_SESSION_TIMEOUT,
            max_sessions: DEFAULT_MAX_SESSIONS,
        }
    }

    /// 创建带自定义过期阈值与 session 数量上限的管理器。
    ///
    /// * `session_timeout`：无活动超过此阈值的 session 在 `cleanup_expired_sessions` 中被回收。
    /// * `max_sessions`：同时存在的 session 数量上限（防 DoS）。
    pub fn with_limits(session_timeout: Duration, max_sessions: usize) -> Self {
        Self {
            sessions: Mutex::new(HashMap::new()),
            session_timeout,
            max_sessions: max_sessions.max(1),
        }
    }

    /// 当前配置的 session 过期阈值。
    pub fn session_timeout(&self) -> Duration {
        self.session_timeout
    }

    /// 当前配置的 session 数量上限。
    pub fn max_sessions(&self) -> usize {
        self.max_sessions
    }

    /// 创建 session 并绑定调用方身份。
    ///
    /// 若 session_id 已存在且绑定身份不同，返回错误（防止跨方冒用 session_id）。
    /// 若 session_id 已存在且绑定身份相同，返回已有 session（幂等）。
    ///
    /// # DoS 防护（中11）
    /// 若当前 session 数量已达 `max_sessions` 上限且 session_id 不存在（非幂等创建），
    /// 返回错误，防止攻击者无限制创建 session 耗尽内存。
    pub fn create_session(
        &self,
        session_id: &str,
        party_id: &str,
        party_index: usize,
    ) -> eyre::Result<SessionInfo> {
        let mut guard = self
            .sessions
            .lock()
            .map_err(|e| eyre::eyre!("session manager lock poisoned: {e}"))?;

        if let Some(existing) = guard.get(session_id) {
            if existing.party_id != party_id || existing.party_index != party_index {
                tracing::warn!(
                    session_id = %session_id,
                    existing_party_id = %existing.party_id,
                    existing_party_index = existing.party_index,
                    requested_party_id = %party_id,
                    requested_party_index = party_index,
                    "MPC-P2-F5: session_id already bound to a different party — \
                     cross-party session hijack attempt denied"
                );
                return Err(eyre::eyre!(
                    "MPC-P2-F5: session_id '{}' already bound to party {} (index {}), \
                     cannot rebind to party {} (index {}) — cross-party hijack denied",
                    session_id,
                    existing.party_id,
                    existing.party_index,
                    party_id,
                    party_index
                ));
            }
            // 幂等创建：刷新 updated_at（视为一次活动）
            // 借用拆分：先取 info 更新 updated_at，再 clone 返回（避免 get_mut 与 clone 同时借用）
            let updated = {
                let info = guard.get_mut(session_id).expect("just verified by get");
                info.updated_at = Instant::now();
                info.clone()
            };
            return Ok(updated);
        }

        // 中11: session 数量上限检查（防 DoS）
        if guard.len() >= self.max_sessions {
            tracing::warn!(
                session_id = %session_id,
                current_count = guard.len(),
                max_sessions = self.max_sessions,
                "中11: session count reached max_sessions limit — refusing new session (DoS protection)"
            );
            return Err(eyre::eyre!(
                "中11: max sessions limit ({}) reached — refusing to create new session '{}' \
                 (DoS protection; raise max_sessions via SessionManager::with_limits if needed)",
                self.max_sessions,
                session_id
            ));
        }

        let now = Instant::now();
        let info = SessionInfo {
            session_id: session_id.to_string(),
            party_id: party_id.to_string(),
            party_index,
            created_at: now,
            updated_at: now,
            state: SessionState::DkgReady,
        };
        guard.insert(session_id.to_string(), info.clone());
        tracing::info!(
            session_id = %session_id,
            party_id = %party_id,
            party_index,
            current_count = guard.len(),
            max_sessions = self.max_sessions,
            "MPC-P2-F5: session created and bound to caller identity"
        );
        Ok(info)
    }

    /// 校验调用方身份与 session 绑定身份一致。
    ///
    /// 不一致返回错误（跨方冒用 session_id 拒绝）。
    pub fn verify_caller(
        &self,
        session_id: &str,
        party_id: &str,
        party_index: usize,
    ) -> eyre::Result<SessionInfo> {
        let guard = self
            .sessions
            .lock()
            .map_err(|e| eyre::eyre!("session manager lock poisoned: {e}"))?;
        match guard.get(session_id) {
            Some(info) => {
                if info.party_id != party_id || info.party_index != party_index {
                    tracing::warn!(
                        session_id = %session_id,
                        bound_party_id = %info.party_id,
                        bound_party_index = info.party_index,
                        caller_party_id = %party_id,
                        caller_party_index = party_index,
                        "MPC-P2-F5: caller identity does not match session binding — \
                         cross-party access denied"
                    );
                    return Err(eyre::eyre!(
                        "MPC-P2-F5: session_id '{}' bound to party {} (index {}), \
                         caller is party {} (index {}) — cross-party access denied",
                        session_id,
                        info.party_id,
                        info.party_index,
                        party_id,
                        party_index
                    ));
                }
                Ok(info.clone())
            }
            None => {
                tracing::warn!(
                    session_id = %session_id,
                    "MPC-P2-F5: session_id not found in session manager — \
                     caller must run Dkg first to bind identity"
                );
                Err(eyre::eyre!(
                    "MPC-P2-F5: session_id '{}' not found — run Dkg first to create \
                     and bind session identity",
                    session_id
                ))
            }
        }
    }

    /// 状态转换（DkgReady → SignReady → Aggregated）。
    ///
    /// 非法转换返回错误（如从 Aggregated 转回 DkgReady）。
    pub fn transition(&self, session_id: &str, new_state: SessionState) -> eyre::Result<()> {
        let mut guard = self
            .sessions
            .lock()
            .map_err(|e| eyre::eyre!("session manager lock poisoned: {e}"))?;
        let info = guard.get_mut(session_id).ok_or_else(|| {
            eyre::eyre!(
                "MPC-P2-F5: session_id '{}' not found for state transition",
                session_id
            )
        })?;
        // 校验状态转换合法性
        let valid = match (&info.state, &new_state) {
            (SessionState::DkgReady, SessionState::SignReady) => true,
            (SessionState::SignReady, SessionState::Aggregated) => true,
            (SessionState::Aggregated, SessionState::Closed) => true,
            (SessionState::DkgReady, SessionState::Closed) => true,
            (SessionState::SignReady, SessionState::Closed) => true,
            (s, t) if s == t => true, // 同状态转换合法（幂等）
            _ => false,
        };
        if !valid {
            return Err(eyre::eyre!(
                "MPC-P2-F5: illegal session state transition {:?} -> {:?} for session_id '{}'",
                info.state,
                new_state,
                session_id
            ));
        }
        info.state = new_state;
        info.updated_at = Instant::now();
        Ok(())
    }

    /// 查询 session 信息。
    pub fn get(&self, session_id: &str) -> Option<SessionInfo> {
        self.sessions
            .lock()
            .ok()
            .and_then(|g| g.get(session_id).cloned())
    }

    /// 销毁 session。
    pub fn remove(&self, session_id: &str) {
        if let Ok(mut guard) = self.sessions.lock() {
            if guard.remove(session_id).is_some() {
                tracing::info!(session_id = %session_id, "MPC-P2-F5: session removed");
            }
        }
    }

    /// 中10: 回收过期 session（Closed 状态或超过 timeout 无活动）。
    ///
    /// 遍历 `sessions` HashMap，移除满足以下任一条件的 session：
    ///   * 状态为 `SessionState::Closed`（已显式销毁或过期）
    ///   * `now - updated_at >= session_timeout`（超过过期阈值未活动）
    ///
    /// `updated_at` 在 `create_session`（含幂等创建）、`transition` 时刷新，
    /// 视为该 session 的"最后活动时间"。
    ///
    /// 返回被回收的 session 数量。建议在 server.rs 的定时任务或每次 RPC 调用时触发。
    /// 使用 `HashMap::retain` 原地过滤，避免二次分配。
    pub fn cleanup_expired_sessions(&self) -> usize {
        let mut guard = match self.sessions.lock() {
            Ok(g) => g,
            Err(e) => {
                tracing::warn!(
                    error = %e,
                    "中10: session manager lock poisoned — skip cleanup"
                );
                return 0;
            }
        };
        let now = Instant::now();
        let timeout = self.session_timeout;
        let before = guard.len();
        guard.retain(|session_id, session| {
            // 已 Closed：回收
            if session.state == SessionState::Closed {
                tracing::info!(
                    session_id = %session_id,
                    state = ?session.state,
                    "中10: session reaped (Closed state)"
                );
                return false;
            }
            // 超时无活动：回收
            // elapsed 失败（时钟回退，理论上 Instant 单调不会发生）时保留 session，避免误杀
            let elapsed = now.duration_since(session.updated_at);
            if elapsed >= timeout {
                tracing::info!(
                    session_id = %session_id,
                    state = ?session.state,
                    elapsed_secs = elapsed.as_secs(),
                    timeout_secs = timeout.as_secs(),
                    "中10: session reaped (inactivity timeout exceeded)"
                );
                return false;
            }
            true
        });
        let reaped = before - guard.len();
        if reaped > 0 {
            tracing::info!(
                before,
                after = guard.len(),
                reaped,
                timeout_secs = timeout.as_secs(),
                "中10: expired sessions cleanup completed"
            );
        }
        reaped
    }

    /// 当前 session 数量。
    pub fn len(&self) -> usize {
        self.sessions.lock().map(|g| g.len()).unwrap_or(0)
    }

    /// 是否为空。
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_and_verify_session() {
        let mgr = SessionManager::new();
        let info = mgr.create_session("sess-1", "party-0", 0).expect("create");
        assert_eq!(info.party_id, "party-0");
        assert_eq!(info.party_index, 0);
        assert_eq!(info.state, SessionState::DkgReady);

        // 同身份重复创建：幂等
        let info2 = mgr
            .create_session("sess-1", "party-0", 0)
            .expect("idempotent create");
        assert_eq!(info2.party_id, "party-0");

        // 校验调用方身份
        mgr.verify_caller("sess-1", "party-0", 0)
            .expect("same identity should pass");
    }

    #[test]
    fn deny_cross_party_hijack() {
        let mgr = SessionManager::new();
        mgr.create_session("sess-2", "party-0", 0).expect("create");

        // 不同身份重新绑定：拒绝
        let err = mgr.create_session("sess-2", "party-1", 1).unwrap_err();
        assert!(err.to_string().contains("cross-party hijack denied"));

        // 不同身份校验：拒绝
        let err = mgr.verify_caller("sess-2", "party-1", 1).unwrap_err();
        assert!(err.to_string().contains("cross-party access denied"));
    }

    #[test]
    fn state_transitions() {
        let mgr = SessionManager::new();
        mgr.create_session("sess-3", "party-0", 0).expect("create");

        mgr.transition("sess-3", SessionState::SignReady)
            .expect("DkgReady -> SignReady");
        mgr.transition("sess-3", SessionState::Aggregated)
            .expect("SignReady -> Aggregated");

        // 非法转换：Aggregated -> DkgReady
        let err = mgr
            .transition("sess-3", SessionState::DkgReady)
            .unwrap_err();
        assert!(err.to_string().contains("illegal session state transition"));
    }

    #[test]
    fn verify_nonexistent_session_fails() {
        let mgr = SessionManager::new();
        let err = mgr.verify_caller("no-such", "party-0", 0).unwrap_err();
        assert!(err.to_string().contains("not found"));
    }

    // ===== 中10: SessionManager 过期清理 =====

    #[test]
    fn cleanup_removes_closed_sessions() {
        let mgr = SessionManager::new();
        mgr.create_session("s-closed", "party-0", 0)
            .expect("create");
        mgr.create_session("s-active", "party-0", 0)
            .expect("create");
        // 关闭一个
        mgr.transition("s-closed", SessionState::Closed)
            .expect("close");
        let reaped = mgr.cleanup_expired_sessions();
        assert_eq!(reaped, 1, "Closed session should be reaped");
        assert!(mgr.get("s-closed").is_none());
        assert!(
            mgr.get("s-active").is_some(),
            "active session should remain"
        );
    }

    #[test]
    fn cleanup_keeps_active_sessions() {
        let mgr = SessionManager::new();
        mgr.create_session("s1", "party-0", 0).expect("create");
        mgr.create_session("s2", "party-0", 0).expect("create");
        let reaped = mgr.cleanup_expired_sessions();
        assert_eq!(reaped, 0, "no sessions should be reaped");
        assert_eq!(mgr.len(), 2);
    }

    #[test]
    fn cleanup_with_short_timeout_reaps_inactive() {
        // 1ns 超时：任何已创建 session 都视为过期
        let mgr = SessionManager::with_limits(Duration::from_nanos(1), 100);
        mgr.create_session("s-old", "party-0", 0).expect("create");
        // 让时间推进（Instant::now + 1ns 不可直接构造，但 cleanup 内部 now > updated_at）
        std::thread::sleep(Duration::from_millis(2));
        let reaped = mgr.cleanup_expired_sessions();
        assert_eq!(
            reaped, 1,
            "inactive session should be reaped with 1ns timeout"
        );
        assert!(mgr.get("s-old").is_none());
    }

    #[test]
    fn cleanup_returns_zero_on_empty() {
        let mgr = SessionManager::new();
        assert_eq!(mgr.cleanup_expired_sessions(), 0);
    }

    // ===== 中11: session 数量上限 =====

    #[test]
    fn max_sessions_limit_enforced() {
        let mgr = SessionManager::with_limits(DEFAULT_SESSION_TIMEOUT, 2);
        mgr.create_session("s1", "party-0", 0).expect("create 1");
        mgr.create_session("s2", "party-0", 0).expect("create 2");
        // 第三个应失败
        let err = mgr.create_session("s3", "party-0", 0).unwrap_err();
        assert!(err.to_string().contains("max sessions limit"), "err: {err}");
        assert_eq!(mgr.len(), 2);
    }

    #[test]
    fn max_sessions_idempotent_create_does_not_count() {
        // 幂等创建（同 session_id 同身份）不应被 max_sessions 拒绝
        let mgr = SessionManager::with_limits(DEFAULT_SESSION_TIMEOUT, 1);
        mgr.create_session("s1", "party-0", 0).expect("create 1");
        // 重复创建同 session_id：幂等，应成功
        mgr.create_session("s1", "party-0", 0).expect("idempotent");
        assert_eq!(mgr.len(), 1);
        // 不同 session_id：应失败
        let err = mgr.create_session("s2", "party-0", 0).unwrap_err();
        assert!(err.to_string().contains("max sessions limit"));
    }

    #[test]
    fn default_max_sessions_is_100() {
        let mgr = SessionManager::new();
        assert_eq!(mgr.max_sessions(), DEFAULT_MAX_SESSIONS);
        assert_eq!(mgr.max_sessions(), 100);
    }

    #[test]
    fn with_limits_clamps_max_sessions_to_at_least_1() {
        let mgr = SessionManager::with_limits(DEFAULT_SESSION_TIMEOUT, 0);
        assert_eq!(mgr.max_sessions(), 1, "max_sessions=0 should clamp to 1");
    }
}
