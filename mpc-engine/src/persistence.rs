//! MPC 会话持久化（方案 A 多进程缺口 1：dkg 份额落盘，重启后 Sign 可恢复）。
//!
//! DkgSession（serde 序列化，注释明确"序列化存储供 Sign 阶段重建"）——
//! DKG 完成后落盘到 `MPC_ENGINE_SESSION_DIR`（默认 `./mpc-sessions`），
//! Sign 时内存缺失则从盘恢复。份额以 JSON 落盘（引擎侧隔离进程持有，
//! 密钥材料不跨进程传输——方案 A"份额只在参与者进程"语义）。

use crate::gg20::DkgSession;
use eyre::eyre;
use std::fs;
use std::path::{Path, PathBuf};

/// 会话目录环境变量。
const SESSION_DIR_ENV: &str = "MPC_ENGINE_SESSION_DIR";

/// 获取会话目录（可配置，默认 ./mpc-sessions）。
pub fn session_dir() -> PathBuf {
    std::env::var(SESSION_DIR_ENV)
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./mpc-sessions"))
}

fn session_path(session_id: &str) -> PathBuf {
    session_dir().join(format!("session-{}.json", session_id))
}

/// 持久化 DKG 会话（份额材料落盘，重启可恢复）。
pub fn persist_session(session_id: &str, session: &DkgSession) -> eyre::Result<()> {
    let dir = session_dir();
    fs::create_dir_all(&dir)
        .map_err(|e| eyre!("cannot create session dir {}: {e}", dir.display()))?;
    let json = serde_json::to_vec_pretty(session)
        .map_err(|e| eyre!("session serialize failed: {e}"))?;
    let path = session_path(session_id);
    fs::write(&path, json)
        .map_err(|e| eyre!("cannot write session {}: {e}", path.display()))?;
    tracing::info!(session_id = %session_id, path = %path.display(), "dkg session persisted");
    Ok(())
}

/// 从盘恢复会话（供 Sign 阶段使用）。
pub fn load_session(session_id: &str) -> eyre::Result<Option<DkgSession>> {
    let path = session_path(session_id);
    if !Path::new(&path).exists() {
        return Ok(None);
    }
    let bytes = fs::read(&path)
        .map_err(|e| eyre!("cannot read session {}: {e}", path.display()))?;
    let session: DkgSession = serde_json::from_slice(&bytes)
        .map_err(|e| eyre!("session deserialize failed: {e}"))?;
    tracing::info!(session_id = %session_id, "dkg session restored from disk");
    Ok(Some(session))
}

/// 删除会话（密钥轮换/清理）。
pub fn remove_session(session_id: &str) {
    let path = session_path(session_id);
    if path.exists() {
        let _ = fs::remove_file(&path);
        tracing::info!(session_id = %session_id, "dkg session removed");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn persist_and_restore_round_trip() {
        // 使用真实 DKG 会话验证序列化往返
        let (_, _, session) = crate::gg20::run_keygen(1, 2)
            .expect("GG20 DKG failed");
        let id = "persist-test-1";
        persist_session(id, &session).expect("persist");
        let restored = load_session(id).expect("load").expect("some");
        assert_eq!(restored.params.threshold, session.params.threshold);
        assert_eq!(restored.params.share_count, session.params.share_count);
        assert_eq!(restored.y_sum, session.y_sum, "聚合公钥应一致");
        remove_session(id);
    }

    #[test]
    fn load_missing_returns_none() {
        let r = load_session("no-such-session").expect("no error");
        assert!(r.is_none());
    }
}
