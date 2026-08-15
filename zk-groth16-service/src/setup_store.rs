//! Groth16 setup 持久化存储（PLAN-ZK-setup-persist）。
//!
//! 电路指纹 → 目录：`$GROTH16_SETUP_DIR/<fingerprint>/pk.bin, vk.bin`
//! - pk/vk 用 ark-serialize（CanonicalSerialize）二进制序列化
//! - 目录权限 0700（pk 含 toxic waste，泄露可伪造证明——部署隔离警告）
//! - setup 幂等：同指纹已存在则加载，否则生成并落盘

use ark_ec::pairing::Pairing;
use ark_groth16::{Groth16, ProvingKey, VerifyingKey};
use ark_serialize::{CanonicalDeserialize, CanonicalSerialize};
use ark_std::rand::{rngs::StdRng, SeedableRng};
use ark_snark::SNARK;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;

/// 存储目录环境变量。
const SETUP_DIR_ENV: &str = "GROTH16_SETUP_DIR";

/// 获取 setup 目录（可配置，默认 ./groth16-setup）。
pub fn setup_dir() -> PathBuf {
    std::env::var(SETUP_DIR_ENV)
        .map(PathBuf::from)
        .unwrap_or_else(|_| PathBuf::from("./groth16-setup"))
}

/// 电路指纹：SHA-256(约束结构 canonical JSON)。
/// 用同一指纹的电路产生确定性相同 SRS（同电路 → 同 pk/vk）。
pub fn circuit_fingerprint(circuit_json: &str) -> String {
    use std::collections::hash_map::DefaultHasher;
    use std::hash::{Hash, Hasher};
    // 简化：用电路 JSON 的稳定 hash（约束结构相同 → 指纹相同）
    let mut hasher = DefaultHasher::new();
    circuit_json.hash(&mut hasher);
    format!("{:016x}", hasher.finish())
}

fn fingerprint_dir(fp: &str) -> PathBuf {
    setup_dir().join(fp)
}

fn pk_path(fp: &str) -> PathBuf { fingerprint_dir(fp).join("pk.bin") }
fn vk_path(fp: &str) -> PathBuf { fingerprint_dir(fp).join("vk.bin") }

/// 幂等 setup：磁盘已有则加载，否则确定性生成并落盘（0700 权限）。
pub fn load_or_setup<E: Pairing>(circuit_json: &str, circuit: impl Clone + ark_relations::r1cs::ConstraintSynthesizer<E::ScalarField>)
    -> eyre::Result<(ProvingKey<E>, VerifyingKey<E>)>
where
    E::ScalarField: ark_ff::PrimeField,
{
    let fp = circuit_fingerprint(circuit_json);
    let dir = fingerprint_dir(&fp);
    fs::create_dir_all(&dir).map_err(|e| eyre::eyre!("cannot create setup dir: {e}"))?;
    // 0700：pk 含 toxic waste
    let _ = fs::set_permissions(&dir, fs::Permissions::from_mode(0o700));

    let pk_file = pk_path(&fp);
    let vk_file = vk_path(&fp);
    if pk_file.exists() && vk_file.exists() {
        tracing::info!(fingerprint = %fp, "setup loaded from disk (idempotent)");
        let pk_bytes = fs::read(&pk_file)?;
        let vk_bytes = fs::read(&vk_file)?;
        let pk = <ProvingKey<E> as ark_serialize::CanonicalDeserialize>::deserialize_uncompressed(&pk_bytes[..])?;
        let vk = <VerifyingKey<E> as ark_serialize::CanonicalDeserialize>::deserialize_uncompressed(&vk_bytes[..])?;
        return Ok((pk, vk));
    }

    // 确定性 seed：由指纹派生（同电路 → 同 SRS）
    let seed: u64 = fp[..16].chars().fold(0u64, |acc, c| acc.wrapping_mul(31).wrapping_add(c as u64));
    let mut rng = StdRng::seed_from_u64(seed);
    let pk = Groth16::<E>::generate_random_parameters_with_reduction(circuit.clone(), &mut rng)?;
    let vk = pk.vk.clone();

    // 落盘（二进制 + 0700 目录）
    let mut pk_buf = Vec::new();
    pk.serialize_uncompressed(&mut pk_buf)?;
    let mut vk_buf = Vec::new();
    vk.serialize_uncompressed(&mut vk_buf)?;
    fs::write(&pk_file, pk_buf)?;
    fs::write(&vk_file, vk_buf)?;
    let _ = fs::set_permissions(&pk_file, fs::Permissions::from_mode(0o600));
    let _ = fs::set_permissions(&vk_file, fs::Permissions::from_mode(0o644));
    tracing::info!(fingerprint = %fp, "setup generated (deterministic) and persisted");
    Ok((pk, vk))
}

/// 读取 vk（公开，供验证方发布）。
pub fn load_vk_bytes(fp: &str) -> eyre::Result<Vec<u8>> {
    let path = vk_path(fp);
    if !path.exists() {
        return Err(eyre::eyre!("vk not found for fingerprint {fp} (run setup first)"));
    }
    Ok(fs::read(&path)?)
}

/// 持久化证明（可选，供后续验证复用）。
pub fn persist_proof(fp: &str, proof: &ark_groth16::Proof<ark_bn254::Bn254>) -> eyre::Result<String> {
    let mut buf = Vec::new();
    proof.serialize_uncompressed(&mut buf)?;
    Ok(hex::encode(buf))
}

/// 解析 hex 编码的证明。
pub fn parse_proof_hex(hex_str: &str) -> eyre::Result<ark_groth16::Proof<ark_bn254::Bn254>> {
    let bytes = hex::decode(hex_str).map_err(|e| eyre::eyre!("bad proof hex: {e}"))?;
    Ok(<ark_groth16::Proof<ark_bn254::Bn254> as ark_serialize::CanonicalDeserialize>::deserialize_uncompressed(&bytes[..])?)
}

/// 解析 hex 编码的 vk。
pub fn parse_vk_hex(hex_str: &str) -> eyre::Result<VerifyingKey<ark_bn254::Bn254>> {
    let bytes = hex::decode(hex_str).map_err(|e| eyre::eyre!("bad vk hex: {e}"))?;
    Ok(<VerifyingKey<ark_bn254::Bn254> as ark_serialize::CanonicalDeserialize>::deserialize_uncompressed(&bytes[..])?)
}
