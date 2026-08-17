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
use std::time::Instant;

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

/// Session 管理器（MPC-P2-F5 身份绑定）。
///
/// 持有 `session_id -> SessionInfo` 映射，提供：
///   * `create_session`：创建 session 并绑定调用方身份。
///   * `verify_caller`：校验调用方身份与 session 绑定身份一致。
///   * `transition`：状态转换（DkgReady → SignReady → Aggregated）。
///   * `get`/`remove`：查询与销毁。
///
/// 线程安全：内部 `Mutex<HashMap>`，锁在同步代码块内获取释放，不跨 .await。
pub struct SessionManager {
    sessions: Mutex<HashMap<String, SessionInfo>>,
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
        }
    }

    /// 创建 session 并绑定调用方身份。
    ///
    /// 若 session_id 已存在且绑定身份不同，返回错误（防止跨方冒用 session_id）。
    /// 若 session_id 已存在且绑定身份相同，返回已有 session（幂等）。
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
            return Ok(existing.clone());
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
            eyre::eyre!("MPC-P2-F5: session_id '{}' not found for state transition", session_id)
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
        let info = mgr
            .create_session("sess-1", "party-0", 0)
            .expect("create");
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
        mgr.create_session("sess-2", "party-0", 0)
            .expect("create");

        // 不同身份重新绑定：拒绝
        let err = mgr
            .create_session("sess-2", "party-1", 1)
            .unwrap_err();
        assert!(err.to_string().contains("cross-party hijack denied"));

        // 不同身份校验：拒绝
        let err = mgr
            .verify_caller("sess-2", "party-1", 1)
            .unwrap_err();
        assert!(err.to_string().contains("cross-party access denied"));
    }

    #[test]
    fn state_transitions() {
        let mgr = SessionManager::new();
        mgr.create_session("sess-3", "party-0", 0)
            .expect("create");

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
        let err = mgr
            .verify_caller("no-such", "party-0", 0)
            .unwrap_err();
        assert!(err.to_string().contains("not found"));
    }
}