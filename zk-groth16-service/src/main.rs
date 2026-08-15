// ZK Groth16 真实验证服务（方案 C：全链路真实，Rust arkworks）
// gRPC (50061) + HTTP JSON (50062, Java 桥接无 protoc 环境)
mod bridge;
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
        tracing::info!(curve = %req.curve, inputs = req.public_inputs_hex.len(), "rpc Verify");
        let resp = match demo_verify(&req.public_inputs_hex) {
            Ok(valid) => VerifyResponse { valid, error: String::new() },
            Err(e) => VerifyResponse { valid: false, error: format!("{e}") },
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
    /// 电路 JSON（Java R1csToJsonBridge 产出）——正式电路桥接载荷
    circuit_json: Option<String>,
}

#[derive(serde::Serialize)]
struct HttpVerifyResponse {
    valid: bool,
    error: String,
}

async fn http_verify(State(_): State<VerifierImpl>, Json(req): Json<HttpVerifyRequest>) -> Json<HttpVerifyResponse> {
    // 正式电路桥接：Java R1CS JSON → 动态 arkworks 电路 → 真实 Groth16 验证
    if let Some(json) = &req.circuit_json {
        return match bridge::bridge_verify(json) {
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
        .route("/health", axum::routing::get(http_health))
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
