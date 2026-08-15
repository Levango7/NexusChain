fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_build::configure()
        .compile(&["proto/zk_groth16.proto"], &["proto"])?;
    Ok(())
}
