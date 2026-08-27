// ZK Groth16 真实验证服务（方案 C：全链路真实，Rust arkworks）
// gRPC (50061) + HTTP JSON (50062, Java 桥接无 protoc 环境)
mod bridge;
mod setup_store;
mod proto {
    tonic::include_proto!("zk_groth16");
}

use ark_bn254::{Bn254, Fr};
use ark_groth16::Groth16;
use ark_snark::SNARK;
use proto::groth16_verifier_service_server::{Groth16VerifierService, Groth16VerifierServiceServer};
use proto::{VerifyRequest, VerifyResponse};
use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystemRef, LinearCombination, SynthesisError, Variable};
use ark_std::rand::{rngs::StdRng, SeedableRng};
use std::str::FromStr;
use tonic::{transport::Server, Request, Response, Status};

#[derive(Default, Clone)]
pub struct VerifierImpl;

#[tonic::async_trait]
impl Groth16VerifierService for VerifierImpl {
    async fn verify(&self, req: Request<VerifyRequest>) -> Result<Response<VerifyResponse>, Status> {
        let req = req.into_inner();
        tracing::info!(curve = %req.curve, inputs = req.public_inputs_hex.len(), has_circuit = !req.circuit_json.is_empty(), "rpc Verify");
        // A1-R2：gRPC Verify 支持真实电路桥接（与 HTTP /v1/verify 一致）——
        // circuit_json 非空时走动态电路（Java R1CS JSON → arkworks → 真实 BN254 配对验证）；
        // 空载荷仅作演示电路 fallback（x^3+x+5=35，测试用途）。
        let resp = if req.circuit_json.is_empty() {
            match demo_verify(&req.public_inputs_hex) {
                Ok(valid) => VerifyResponse { valid, error: String::new() },
                Err(e) => VerifyResponse { valid: false, error: format!("{e}") },
            }
        } else {
            match bridge::bridge_verify(&req.circuit_json) {
                Ok(valid) => VerifyResponse { valid, error: String::new() },
                Err(e) => VerifyResponse { valid: false, error: format!("{e}") },
            }
        };
        Ok(Response::new(resp))
    }
}

/// 真实 Groth16 全链路（演示电路 x^3+x+5=35）：setup→prove→verify，BN254 配对。
/// 正式接入：电路由 Java R1CS 描述经桥接转换；vk/proof 由 setup/prove 阶段持久化。
fn demo_verify(inputs_hex: &[String]) -> eyre::Result<bool> {
    let mut rng = StdRng::seed_from_u64(42);
    let circuit = DemoCircuit { x: Some(3) };
    let pk = Groth16::<Bn254>::generate_random_parameters_with_reduction(circuit, &mut rng)
        .map_err(|e| eyre::eyre!("setup failed: {e}"))?;
    let vk = pk.vk.clone();
    let proof = Groth16::<Bn254>::prove(&pk, DemoCircuit { x: Some(3) }, &mut rng)
        .map_err(|e| eyre::eyre!("prove failed: {e}"))?;
    let inputs: Vec<Fr> = if inputs_hex.is_empty() {
        vec![Fr::from(35u64)]
    } else {
        inputs_hex.iter().map(|h| Fr::from_str(h)).collect::<Result<_, _>>()
            .map_err(|_| eyre::eyre!("bad input hex"))?
    };
    let ok = Groth16::<Bn254>::verify(&vk, &inputs, &proof)
        .map_err(|e| eyre::eyre!("verify error: {e}"))?;
    Ok(ok)
}

struct DemoCircuit { x: Option<u64> }
impl ConstraintSynthesizer<Fr> for DemoCircuit {
    fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
        let x = cs.new_witness_variable(|| self.x.map(Fr::from).ok_or(SynthesisError::AssignmentMissing))?;
        let x2 = cs.new_witness_variable(|| self.x.map(|v| Fr::from(v) * Fr::from(v)).ok_or(SynthesisError::AssignmentMissing))?;
        let x3 = cs.new_witness_variable(|| self.x.map(|v| Fr::from(v) * Fr::from(v) * Fr::from(v)).ok_or(SynthesisError::AssignmentMissing))?;
        let out = cs.new_input_variable(|| Ok(Fr::from(35u64)))?;
        cs.enforce_constraint(LinearCombination::from(x), LinearCombination::from(x), LinearCombination::from(x2))?;
        cs.enforce_constraint(LinearCombination::from(x2), LinearCombination::from(x), LinearCombination::from(x3))?;
        cs.enforce_constraint(
            LinearCombination::from(x3) + LinearCombination::from(x) + (Fr::from(5u64), Variable::One),
            LinearCombination::from(Variable::One),
            LinearCombination::from(out))?;
        Ok(())
    }
}

// ===== HTTP JSON 验证端点（Java 桥接：无 protoc 环境，HttpClient 直调）=====
use std::collections::HashMap;
use axum::{routing::post, Json, Router, extract::State};

#[derive(serde::Deserialize)]
struct HttpVerifyRequest {
    circuit_id: Option<String>,
    #[serde(default)]
    public_inputs_hex: Vec<String>,
    /// 电路 JSON（Java R1csToJsonBridge 产出）——正式电路桥接载荷（对象，非字符串）
    circuit_json: Option<serde_json::Value>,
}

#[derive(serde::Serialize)]
struct HttpVerifyResponse {
    valid: bool,
    error: String,
}

async fn http_verify(State(_): State<VerifierImpl>, Json(req): Json<HttpVerifyRequest>) -> Json<HttpVerifyResponse> {
    // 正式电路桥接：Java R1CS JSON → 动态 arkworks 电路 → 真实 Groth16 验证
    if let Some(json) = &req.circuit_json {
        return match bridge::bridge_verify(&json.to_string()) {
            Ok(valid) => Json(HttpVerifyResponse { valid, error: String::new() }),
            Err(e) => Json(HttpVerifyResponse { valid: false, error: format!("{e}") }),
        };
    }
    // 演示电路（x^3+x+5=35）
    match demo_verify(&req.public_inputs_hex) {
        Ok(valid) => Json(HttpVerifyResponse { valid, error: String::new() }),
        Err(e) => Json(HttpVerifyResponse { valid: false, error: format!("{e}") }),
    }
}

async fn http_health() -> Json<HashMap<String, String>> {
    let mut m = HashMap::new();
    m.insert("status".into(), "ok".into());
    m.insert("engine".into(), "arkworks-groth16".into());
    m.insert("curve".into(), "bn254".into());
    Json(m)
}

async fn serve_http() -> eyre::Result<()> {
    let port: u16 = std::env::var("ZK_HTTP_PORT").unwrap_or_else(|_| "50062".to_string()).parse()?;
    let app = Router::new()
        .route("/v1/verify", post(http_verify))
        .route("/v1/setup", post(http_setup))
        .route("/v1/setup-external", post(http_setup_external))
        .route("/v1/prove", post(http_prove))
        .route("/v1/verify-sep", post(http_verify_sep))
        .route("/health", axum::routing::get(http_health))
        .route("/v1/bench", axum::routing::get(http_bench))
        .with_state(VerifierImpl::default());
    let addr = format!("0.0.0.0:{port}");
    let listener = tokio::net::TcpListener::bind(&addr).await?;
    tracing::info!("zk-groth16-service HTTP endpoint on {addr} (/v1/verify, /health)");
    axum::serve(listener, app).await?;
    Ok(())
}

#[tokio::main]
async fn main() -> eyre::Result<()> {
    let host = std::env::var("ZK_SERVICE_HOST").unwrap_or_else(|_| "0.0.0.0".to_string());
    let port: u16 = std::env::var("ZK_SERVICE_PORT").unwrap_or_else(|_| "50061".to_string()).parse()?;
    let addr = format!("{host}:{port}").parse()?;
    tracing_subscriber::fmt().with_env_filter("info").init();
    tracing::info!("zk-groth16-service starting gRPC {addr} + HTTP 50062 (真实 BN254 Groth16 验证)");
    let grpc = async {
        Server::builder()
            .add_service(Groth16VerifierServiceServer::new(VerifierImpl::default()))
            .serve(addr)
            .await
            .map_err(|e| eyre::eyre!("grpc serve: {e}"))
    };
    tokio::select! {
        r = grpc => r?,
        _ = serve_http() => {},
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn real_groth16_verify_true() {
        let ok = demo_verify(&[]).expect("demo verify");
        assert!(ok, "真实 Groth16 验证应通过（x^3+x+5=35, x=3）");
    }

    #[test]
    fn real_groth16_verify_wrong_input_false() {
        let ok = demo_verify(&["23".to_string()]).expect("demo verify");
        assert!(!ok, "错误输入应验证失败");
    }
}

// ===== 持久化 setup / 分离证明模式端点（PLAN-ZK-setup-persist）=====

#[derive(serde::Deserialize)]
struct SetupRequest {
    circuit_json: serde_json::Value,
}

#[derive(serde::Serialize)]
struct SetupResponse {
    fingerprint: String,
    vk_hex: String,
    error: String,
}

async fn http_setup(Json(req): Json<SetupRequest>) -> Json<SetupResponse> {
    match bridge::setup_public(&req.circuit_json.to_string()) {
        Ok((fp, vk_hex)) => Json(SetupResponse { fingerprint: fp, vk_hex, error: String::new() }),
        Err(e) => Json(SetupResponse { fingerprint: String::new(), vk_hex: String::new(), error: format!("{e}") }),
    }
}

#[derive(serde::Deserialize)]
struct ProveRequest {
    circuit_json: serde_json::Value,
}

#[derive(serde::Serialize)]
struct ProveResponse {
    fingerprint: String,
    proof_hex: String,
    error: String,
}

async fn http_prove(Json(req): Json<ProveRequest>) -> Json<ProveResponse> {
    match bridge::prove_real(&req.circuit_json.to_string()) {
        Ok((fp, proof_hex)) => Json(ProveResponse { fingerprint: fp, proof_hex, error: String::new() }),
        Err(e) => Json(ProveResponse { fingerprint: String::new(), proof_hex: String::new(), error: format!("{e}") }),
    }
}

#[derive(serde::Deserialize)]
struct VerifySepRequest {
    /// 电路 JSON（原分离验证载荷；有 fingerprint 时忽略，公共输入以 public_inputs 传输）
    circuit_json: Option<serde_json::Value>,
    /// 电路指纹（A1-R6：Java verify 无电路 JSON 时按指纹定位持久化 vk）
    fingerprint: Option<String>,
    proof_hex: String,
    /// 公共输入（十进制字符串；fingerprint 模式必需）
    #[serde(default)]
    public_inputs: Vec<String>,
}

#[derive(serde::Serialize)]
struct VerifySepResponse {
    valid: bool,
    error: String,
}

async fn http_verify_sep(Json(req): Json<VerifySepRequest>) -> Json<VerifySepResponse> {
    // A1-R6：优先按指纹分离验证（Java verify 阶段无电路 JSON 的场景）
    if let Some(fp) = &req.fingerprint {
        return match bridge::verify_with_fingerprint_and_inputs(fp, &req.proof_hex, &req.public_inputs) {
            Ok(valid) => Json(VerifySepResponse { valid, error: String::new() }),
            Err(e) => Json(VerifySepResponse { valid: false, error: format!("{e}") }),
        };
    }
    // 兼容：原 circuit_json + proof_hex 载荷
    match req.circuit_json {
        Some(json) => match bridge::verify_with_proof(&json.to_string(), &req.proof_hex) {
            Ok(valid) => Json(VerifySepResponse { valid, error: String::new() }),
            Err(e) => Json(VerifySepResponse { valid: false, error: format!("{e}") }),
        },
        None => Json(VerifySepResponse { valid: false, error: "either fingerprint or circuit_json required".to_string() }),
    }
}

// ===== 可信设置仪式导入端点（外部 setup 注入）=====

#[derive(serde::Deserialize)]
struct SetupExternalRequest {
    circuit_json: serde_json::Value,
    pk_hex: String,
    vk_hex: String,
}

#[derive(serde::Serialize)]
struct SetupExternalResponse {
    fingerprint: String,
    imported: bool,
    error: String,
}

async fn http_setup_external(Json(req): Json<SetupExternalRequest>) -> Json<SetupExternalResponse> {
    let fp = crate::setup_store::circuit_fingerprint(&req.circuit_json.to_string());
    match crate::setup_store::import_external_setup(&fp, &req.pk_hex, &req.vk_hex) {
        Ok(()) => Json(SetupExternalResponse { fingerprint: fp, imported: true, error: String::new() }),
        Err(e) => Json(SetupExternalResponse { fingerprint: fp, imported: false, error: format!("{e}") }),
    }
}

// ===== 性能基准端点（证明/验证耗时测量）=====

#[derive(serde::Serialize)]
struct BenchResponse {
    iterations: usize,
    prove_avg_ms: f64,
    verify_avg_ms: f64,
    error: String,
}

async fn http_bench() -> Json<BenchResponse> {
    const ITERS: usize = 10;
    let mut rng = ark_std::rand::rngs::StdRng::seed_from_u64(42);
    let circuit = bridge::DynamicCircuit::from_json(
        "{\"num_public\":1,\"num_private\":3,\"witness\":[1,35,3,9,27],\
         \"constraints\":[{\"a\":{\"2\":1},\"b\":{\"2\":1},\"c\":{\"3\":1}},\
         {\"a\":{\"3\":1},\"b\":{\"2\":1},\"c\":{\"4\":1}},\
         {\"a\":{\"4\":1,\"2\":1,\"0\":5},\"b\":{\"0\":1},\"c\":{\"1\":1}}]}")
        .unwrap();
    let pk = match ark_groth16::Groth16::<ark_bn254::Bn254>::generate_random_parameters_with_reduction(
        circuit.clone(), &mut rng) {
        Ok(pk) => pk,
        Err(e) => return Json(BenchResponse { iterations: 0, prove_avg_ms: 0.0, verify_avg_ms: 0.0, error: format!("setup: {e}") }),
    };
    let vk = pk.vk.clone();

    let mut prove_total = 0.0;
    let mut verify_total = 0.0;
    let mut proof = None;
    for _ in 0..ITERS {
        let t0 = std::time::Instant::now();
        let p = match ark_groth16::Groth16::<ark_bn254::Bn254>::prove(&pk, circuit.clone(), &mut rng) {
            Ok(p) => p,
            Err(e) => return Json(BenchResponse { iterations: 0, prove_avg_ms: 0.0, verify_avg_ms: 0.0, error: format!("prove: {e}") }),
        };
        prove_total += t0.elapsed().as_secs_f64() * 1000.0;
        let t1 = std::time::Instant::now();
        let _ = ark_groth16::Groth16::<ark_bn254::Bn254>::verify(&vk, &[ark_bn254::Fr::from(35u64)], &p);
        verify_total += t1.elapsed().as_secs_f64() * 1000.0;
        proof = Some(p);
    }
    Json(BenchResponse {
        iterations: ITERS,
        prove_avg_ms: prove_total / ITERS as f64,
        verify_avg_ms: verify_total / ITERS as f64,
        error: String::new(),
    })
}
