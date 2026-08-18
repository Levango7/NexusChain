//! Build script：从 proto/mpc_crypto.proto 生成 tonic gRPC 服务端 stub。
//!
//! 依赖系统 `protoc`（Dockerfile builder 阶段已安装 protobuf-compiler）。

fn main() -> Result<(), Box<dyn std::error::Error>> {
    // build_client(true)：生成 client stub 供 tests/integration_test.rs 使用
    // （多节点集成测试作为 gRPC 客户端连接到已启动的 mpc-engine 节点）
    tonic_build::configure()
        .build_server(true)
        .build_client(true)
        .compile_protos(&["proto/mpc_crypto.proto"], &["proto"])?;

    println!("cargo:rerun-if-changed=proto/mpc_crypto.proto");
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}