//! Build script：从 proto/mpc_crypto.proto 生成 tonic gRPC 服务端 stub。
//!
//! 依赖系统 `protoc`（Dockerfile builder 阶段已安装 protobuf-compiler）。

fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_build::configure()
        .build_server(true)
        .build_client(false)
        .compile_protos(&["proto/mpc_crypto.proto"], &["proto"])?;

    println!("cargo:rerun-if-changed=proto/mpc_crypto.proto");
    println!("cargo:rerun-if-changed=build.rs");
    Ok(())
}