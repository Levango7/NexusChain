//! ConPay Rust 合约模板入口
//!
//! 这是 ConPay (CPAY) 链上 WASM 智能合约的起点。
//! 实现合约的初始化和业务逻辑入口函数。

use serde::{Deserialize, Serialize};

/// 合约存储状态
#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct ContractState {
    /// 合约所有者地址
    pub owner: String,
    /// 合约存储的键值对
    pub data: std::collections::BTreeMap<String, String>,
}

impl Default for ContractState {
    fn default() -> Self {
        Self {
            owner: String::new(),
            data: std::collections::BTreeMap::new(),
        }
    }
}

/// 合约初始化函数
/// 在合约部署时由 ConPay 虚拟机调用。
///
/// # 参数
/// - `owner`: 合约所有者地址
///
/// # 返回
/// 初始化后的合约状态
#[no_mangle]
pub extern "C" fn init(owner: &str) -> ContractState {
    ContractState {
        owner: owner.to_string(),
        data: std::collections::BTreeMap::new(),
    }
}

/// 设置存储值
///
/// # 参数
/// - `state`: 当前合约状态（可变引用）
/// - `key`: 存储键
/// - `value`: 存储值
#[no_mangle]
pub extern "C" fn set(state: &mut ContractState, key: &str, value: &str) {
    state.data.insert(key.to_string(), value.to_string());
}

/// 读取存储值
///
/// # 参数
/// - `state`: 当前合约状态
/// - `key`: 存储键
///
/// # 返回
/// 对应的存储值，若不存在则返回 None
#[no_mangle]
pub extern "C" fn get(state: &ContractState, key: &str) -> Option<&str> {
    state.data.get(key).map(|s| s.as_str())
}

/// 获取合约所有者
#[no_mangle]
pub extern "C" fn get_owner(state: &ContractState) -> &str {
    &state.owner
}

// ---- 测试 ----

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_init_and_set_get() {
        let mut state = init("0xABC123");
        assert_eq!(state.owner, "0xABC123");

        set(&mut state, "name", "ConPay");
        assert_eq!(get(&state, "name"), Some("ConPay"));
    }

    #[test]
    fn test_get_missing_key() {
        let state = init("0xOWNER");
        assert_eq!(get(&state, "nonexistent"), None);
    }
}
