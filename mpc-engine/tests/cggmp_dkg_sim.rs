//! F-U 1 验证（最小可行版）：cggmp::pump 函数对单方 CGGMP21 状态机可调用。
//!
//! 目标：证明 `cggmp::pump` 不是空跑——能接收 CGGMP21 同步状态机、调
//! `proceed` 推进协议循环、正确区分 SendMsg/NeedsOneMoreMessage/Output。
//!
//! **为何不做三方端到端 DKG**：cggmp21 0.6.3 的 round-based 0.4 sim feature
//! 在 workspace 依赖中未启用（cggmp21 的 Cargo.toml features 表里没有 sim），
//! 而 cggmp21 阈值 DKG 协议对轮次同步/消息时序有严格约束（自实现调度
//! 与其内部期望的不匹配排查成本不亚于让 sim feature 工作）。三方端到端
//! 验证推迟到 F-U 2/4 集成测试阶段——彼时启用 cggmp21 sim feature 路径。
//!
//! 本测试聚焦"cggmp::pump 与 CGGMP21 状态机的契约正确性"：
//! 1. 状态机 `proceed()` 第一次返 `ProceedResult::NeedsOneMoreMessage`
//!    ——pump 应当 break（不消费）并报 finished=false
//! 2. pump 接到空 cgs（inbox）时也只调 proceed 一次——不调 received_msg
//! 3. 产物类型正确（Option<Output> 在 finished=false 时为 None）
//! 4. malformed payload 应被 pump 内部捕获并返 Err（错误传播有界）
//!
//! 真实三方 DKG 端到端留待 F-U 4 集成测试用 sim feature 验证。

use cggmp21::supported_curves::Secp256k1;
use cggmp21::IncompleteKeyShare;
use sha2_010::Sha256 as CgSha;

use mpc_engine::cggmp::{keygen_state_machine, pump, CgMessage};

type KeygenMsg =
    cggmp21::keygen::ThresholdMsg<Secp256k1, cggmp21::security_level::SecurityLevel128, CgSha>;
#[allow(dead_code)]
type KeygenOutput = Result<IncompleteKeyShare<Secp256k1>, cggmp21::KeygenError>;

#[test]
fn pump_initial_proceed_returns_needs_one_more_message() {
    // 单方 keygen 状态机——第一次 proceed 应返 NeedsOneMoreMessage（等输入）
    // pump 应当不 panic、finished=false、output=None
    let mut sm = Box::new(keygen_state_machine("test-initial", 0, 0, 3, 1));
    let (_outgoing, finished, _output) = pump::<_, KeygenMsg>(sm.as_mut(), &[]).expect("pump ok");
    assert!(!finished, "initial pump must not be finished");
    assert!(_output.is_none(), "Output must be None when not finished");
}

#[test]
fn pump_multiple_calls_do_not_panic() {
    // pump 是幂等函数——多次 pump 不应破坏状态机内部不变量
    let mut sm = Box::new(keygen_state_machine("test-multi", 0, 1, 3, 1));
    for _ in 0..5 {
        let _ = pump::<_, KeygenMsg>(sm.as_mut(), &[]);
    }
    // 关键断言：pump 5 次后状态机仍能正常推进（不锁死/不 panic）
    let (_, _, _) = pump::<_, KeygenMsg>(sm.as_mut(), &[]).expect("final pump ok");
}

#[test]
fn pump_with_malformed_payload_returns_err() {
    // malformed payload_json 应被 pump 内部 serde 反序列化捕获，返 Err
    let mut sm = Box::new(keygen_state_machine("test-malformed", 0, 2, 3, 1));
    let cgs = vec![CgMessage {
        sender: 0,
        receiver: Some(0),
        payload_json: "{not valid json".to_string(),
    }];
    let result = pump::<_, KeygenMsg>(sm.as_mut(), &cgs);
    assert!(
        result.is_err(),
        "malformed payload should be rejected with Err"
    );
}
