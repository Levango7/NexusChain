//! Java R1CS → arkworks 动态电路桥接（ZK 方案 C 正式电路桥接）。

use ark_bn254::Fr;
use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystemRef, LinearCombination, SynthesisError, Variable};
use ark_std::rand::{rngs::StdRng, SeedableRng};
use ark_snark::SNARK;
use ark_serialize::{CanonicalDeserialize as _, CanonicalSerialize as _};
use serde_json::Value;
use std::collections::BTreeMap;

/// 从 JSON 构建的动态电路。
#[derive(Clone)]
pub struct DynamicCircuit {
    num_public: usize,
    num_private: usize,
    witness: Vec<u64>,
    constraints: Vec<(BTreeMap<usize, i64>, BTreeMap<usize, i64>, BTreeMap<usize, i64>)>,
}

impl DynamicCircuit {
    pub fn from_json(json: &str) -> eyre::Result<Self> {
        let v: Value = serde_json::from_str(json).map_err(|e| eyre::eyre!("bad json: {e}"))?;
        let num_public = v["num_public"].as_u64().ok_or_else(|| eyre::eyre!("missing num_public"))? as usize;
        let num_private = v["num_private"].as_u64().ok_or_else(|| eyre::eyre!("missing num_private"))? as usize;
        let witness = v["witness"].as_array()
            .ok_or_else(|| eyre::eyre!("missing witness"))?
            .iter().map(|x| x.as_u64().unwrap_or(0)).collect();
        let mut constraints = Vec::new();
        if let Some(cons) = v["constraints"].as_array() {
            for c in cons {
                constraints.push((parse_coeffs(&c["a"]), parse_coeffs(&c["b"]), parse_coeffs(&c["c"])));
            }
        }
        Ok(DynamicCircuit { num_public, num_private, witness, constraints })
    }
}

fn parse_coeffs(v: &Value) -> BTreeMap<usize, i64> {
    let mut m = BTreeMap::new();
    if let Some(obj) = v.as_object() {
        for (k, val) in obj {
            if let Ok(idx) = k.parse::<usize>() {
                if let Some(c) = val.as_i64().or_else(|| val.as_u64().map(|u| u as i64)) {
                    m.insert(idx, c);
                }
            }
        }
    }
    m
}

/// 系数 → Fr（负系数经 mod P 取正）
fn coeff_fr(c: i64) -> Fr {
    if c >= 0 { Fr::from(c as u64) } else { -Fr::from((-c) as u64) }
}

/// 变量 → arkworks Variable（Instance/Witness 索引从 0 开始，0 号变量=One）
fn var_from_idx(idx: usize, num_public: usize, total: usize) -> Variable {
    if idx == 0 { return Variable::One; }
    if idx <= num_public {
        Variable::Instance(idx - 1)
    } else if idx <= total {
        Variable::Witness(idx - num_public - 1)
    } else {
        Variable::One
    }
}

/// 构建 LinearCombination
fn lc_from(coeffs: &BTreeMap<usize, i64>, num_public: usize, total: usize) -> LinearCombination<Fr> {
    let mut lc = LinearCombination::<Fr>::zero();
    for (idx, coeff) in coeffs {
        lc = lc + (coeff_fr(*coeff), var_from_idx(*idx, num_public, total));
    }
    lc
}

impl ConstraintSynthesizer<Fr> for DynamicCircuit {
    fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
        let total = self.num_public + self.num_private;
        // 分配变量（含 witness 值）：witness[0]=1 常量，witness[1..=num_public] 公共，其余私有
        // 关键：保存 new_witness_variable/new_input_variable 返回的变量对象，
        // lc 用保存对象（编号由 arkworks 分配，绝不错位）——var_from_idx 手动构造
        // 的 Variable::Witness(n) 编号与实际分配可能不一致（gamma_abc 不同根因）。
        let mut vals = Vec::with_capacity(total + 1);
        for i in 0..=total {
            vals.push(Fr::from(self.witness.get(i).copied().unwrap_or(0)));
        }
        // vars[i] = witness 索引 i 对应的 arkworks Variable（0 = One）
        // 索引对齐：vars[1..=num_public] 公共（Instance），vars[num_public+1..=total] 私有（Witness）
        let mut vars = vec![Variable::One];
        vars.resize(total + 1, Variable::One);
        for i in 1..=self.num_public {
            let v = cs.new_input_variable(|| Ok(vals[i]))?;
            vars[i] = v;
        }
        for i in (self.num_public + 1)..=total {
            let v = cs.new_witness_variable(|| Ok(vals[i]))?;
            vars[i] = v;
        }
        for (a, b, c) in &self.constraints {
            cs.enforce_constraint(
                lc_from_vars(a, &vars),
                lc_from_vars(b, &vars),
                lc_from_vars(c, &vars),
            )?;
        }
        Ok(())
    }
}

/// 用保存的变量对象构建 LinearCombination（系数 × 变量）
fn lc_from_vars(coeffs: &BTreeMap<usize, i64>, vars: &[Variable]) -> LinearCombination<Fr> {
    let mut lc = LinearCombination::<Fr>::zero();
    for (idx, coeff) in coeffs {
        let var = if *idx == 0 {
            Variable::One
        } else {
            vars.get(*idx).copied().unwrap_or(Variable::One)
        };
        lc = lc + (coeff_fr(*coeff), var);
    }
    lc
}

/// 桥接验证：解析 Java R1CS JSON → 动态电路 → 真实 Groth16 prove+verify。
pub fn bridge_verify(json: &str) -> eyre::Result<bool> {
    let circuit = DynamicCircuit::from_json(json)?;
    let mut rng = StdRng::seed_from_u64(42);
    let pk = ark_groth16::Groth16::<ark_bn254::Bn254>::generate_random_parameters_with_reduction(
        circuit.clone(), &mut rng)?;
    let vk = pk.vk.clone();
    let proof = ark_groth16::Groth16::<ark_bn254::Bn254>::prove(
        &pk, circuit.clone(), &mut rng)?;
    let v: Value = serde_json::from_str(json)?;
    let num_public = v["num_public"].as_u64().unwrap_or(0) as usize;
    let witness: Vec<u64> = v["witness"].as_array()
        .map(|a| a.iter().map(|x| x.as_u64().unwrap_or(0)).collect()).unwrap_or_default();
    let public: Vec<Fr> = witness.iter().skip(1).take(num_public).map(|w| Fr::from(*w)).collect();
    let ok = ark_groth16::Groth16::<ark_bn254::Bn254>::verify(&vk, &public, &proof)?;
    Ok(ok)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ark_bn254::Bn254;
    use ark_groth16::Groth16;

    fn demo_circuit_json() -> String {
        r#"{
          "num_public": 1,
          "num_private": 3,
          "witness": [1, 35, 3, 9, 27],
          "constraints": [
            {"a": {"2": 1}, "b": {"2": 1}, "c": {"3": 1}},
            {"a": {"3": 1}, "b": {"2": 1}, "c": {"4": 1}},
            {"a": {"4": 1, "2": 1, "0": 5}, "b": {"0": 1}, "c": {"1": 1}}
          ]
        }"#.to_string()
    }

    /// 对照：固定电路（probe 已验证成功）vs 动态电路（同一 seed → vk 应一致）
    #[test]
    fn bridge_vk_matches_fixed_circuit() {
        // 固定电路：先 witness 后 input，witness[2..4]=3,9,27，input[0]=35
        let mut rng1 = StdRng::seed_from_u64(42);
        let fixed = FixedCircuit;
        let pk_fixed = Groth16::<Bn254>::generate_random_parameters_with_reduction(fixed, &mut rng1).unwrap();

        let mut rng2 = StdRng::seed_from_u64(42);
        let dynamic = DynamicCircuit::from_json(&demo_circuit_json()).unwrap();
        let pk_dynamic = Groth16::<Bn254>::generate_random_parameters_with_reduction(dynamic, &mut rng2).unwrap();

        eprintln!("[vk-cmp] fixed delta_g2: {:?}", pk_fixed.vk.delta_g2);
        eprintln!("[vk-cmp] dyn   delta_g2: {:?}", pk_dynamic.vk.delta_g2);
        assert_eq!(pk_fixed.vk.delta_g2, pk_dynamic.vk.delta_g2, "同 seed 同电路结构 → vk 应一致");
    }

    struct FixedCircuit;
    impl ConstraintSynthesizer<Fr> for FixedCircuit {
        fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
            let x = cs.new_witness_variable(|| Ok(Fr::from(3u64)))?;
            let x2 = cs.new_witness_variable(|| Ok(Fr::from(9u64)))?;
            let x3 = cs.new_witness_variable(|| Ok(Fr::from(27u64)))?;
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
}

#[cfg(test)]
mod tests2 {
    use super::*;
    use ark_bn254::Bn254;
    use ark_groth16::Groth16;

    fn demo_circuit_json() -> String {
        r#"{
          "num_public": 1,
          "num_private": 3,
          "witness": [1, 35, 3, 9, 27],
          "constraints": [
            {"a": {"2": 1}, "b": {"2": 1}, "c": {"3": 1}},
            {"a": {"3": 1}, "b": {"2": 1}, "c": {"4": 1}},
            {"a": {"4": 1, "2": 1, "0": 5}, "b": {"0": 1}, "c": {"1": 1}}
          ]
        }"#.to_string()
    }

    struct FixedC { x: Option<u64> }
    impl ConstraintSynthesizer<Fr> for FixedC {
        fn generate_constraints(self, cs: ConstraintSystemRef<Fr>) -> Result<(), SynthesisError> {
            let x = cs.new_witness_variable(|| self.x.map(Fr::from).ok_or(SynthesisError::AssignmentMissing))?;
            let x2 = cs.new_witness_variable(|| self.x.map(|v| Fr::from(v)*Fr::from(v)).ok_or(SynthesisError::AssignmentMissing))?;
            let x3 = cs.new_witness_variable(|| self.x.map(|v| Fr::from(v)*Fr::from(v)*Fr::from(v)).ok_or(SynthesisError::AssignmentMissing))?;
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

    #[test]
    fn full_flow_compare() {
        let mut rng = StdRng::seed_from_u64(42);
        let pk = Groth16::<Bn254>::generate_random_parameters_with_reduction(
            FixedC { x: Some(3) }, &mut rng).unwrap();
        let vk = pk.vk.clone();
        let proof = Groth16::<Bn254>::prove(&pk, FixedC { x: Some(3) }, &mut rng).unwrap();
        eprintln!("[flow] fixed verify: {}", Groth16::<Bn254>::verify(&vk, &[Fr::from(35u64)], &proof).unwrap());

        let mut rng2 = StdRng::seed_from_u64(42);
        let dyn_circuit = DynamicCircuit::from_json(&demo_circuit_json()).unwrap();
        let pk2 = Groth16::<Bn254>::generate_random_parameters_with_reduction(
            dyn_circuit, &mut rng2).unwrap();
        let vk2 = pk2.vk.clone();
        let proof2 = Groth16::<Bn254>::prove(
            &pk2, DynamicCircuit::from_json(&demo_circuit_json()).unwrap(), &mut rng2).unwrap();
        eprintln!("[flow] proof.a fixed: {:?}", proof.a);
        eprintln!("[flow] proof.a dynamic: {:?}", proof2.a);
        eprintln!("[flow] gamma_abc fixed: {:?}", vk.gamma_abc_g1);
        eprintln!("[flow] gamma_abc dynamic: {:?}", vk2.gamma_abc_g1);
        eprintln!("[flow] dynamic verify: {}", Groth16::<Bn254>::verify(&vk2, &[Fr::from(35u64)], &proof2).unwrap());
    }
}

/// 生成证明（用持久化 pk；返回指纹 + 证明 hex）。
pub fn prove_real(json: &str) -> eyre::Result<(String, String)> {
    let circuit = DynamicCircuit::from_json(json)?;
    let fp = crate::setup_store::circuit_fingerprint(json);
    let (pk, _vk) = crate::setup_store::load_or_setup::<ark_bn254::Bn254>(json, circuit.clone())?;
    let mut rng = ark_std::rand::rngs::StdRng::seed_from_u64(1);
    let proof = ark_groth16::Groth16::<ark_bn254::Bn254>::prove(&pk, circuit, &mut rng)?;
    let proof_hex = crate::setup_store::persist_proof(&fp, &proof)?;
    Ok((fp, proof_hex))
}

/// 用外部证明 + 持久化 vk + 公共输入验证（非自证明）。
pub fn verify_with_proof(json: &str, proof_hex: &str) -> eyre::Result<bool> {
    let fp = crate::setup_store::circuit_fingerprint(json);
    let vk_bytes = crate::setup_store::load_vk_bytes(&fp)?;
    let vk = <ark_groth16::VerifyingKey<ark_bn254::Bn254> as ark_serialize::CanonicalDeserialize>::deserialize_uncompressed(&vk_bytes[..])?;
    let proof = crate::setup_store::parse_proof_hex(proof_hex)?;
    let v: Value = serde_json::from_str(json)?;
    let num_public = v["num_public"].as_u64().unwrap_or(0) as usize;
    let witness: Vec<u64> = v["witness"].as_array()
        .map(|a| a.iter().map(|x| x.as_u64().unwrap_or(0)).collect()).unwrap_or_default();
    let public: Vec<Fr> = witness.iter().skip(1).take(num_public).map(|w| Fr::from(*w)).collect();
    let ok = ark_groth16::Groth16::<ark_bn254::Bn254>::verify(&vk, &public, &proof)?;
    Ok(ok)
}

/// 幂等 setup：返回指纹 + vk hex（公开验证密钥）。
pub fn setup_public(json: &str) -> eyre::Result<(String, String)> {
    let circuit = DynamicCircuit::from_json(json)?;
    let fp = crate::setup_store::circuit_fingerprint(json);
    let (_pk, vk) = crate::setup_store::load_or_setup::<ark_bn254::Bn254>(json, circuit)?;
    let mut buf = Vec::new();
    ark_serialize::CanonicalSerialize::serialize_uncompressed(&vk, &mut buf)?;
    Ok((fp, hex::encode(buf)))
}

#[cfg(test)]
mod persist_tests {
    use super::*;

    fn demo_json() -> String {
        r#"{
          "num_public": 1,
          "num_private": 3,
          "witness": [1, 35, 3, 9, 27],
          "constraints": [
            {"a": {"2": 1}, "b": {"2": 1}, "c": {"3": 1}},
            {"a": {"3": 1}, "b": {"2": 1}, "c": {"4": 1}},
            {"a": {"4": 1, "2": 1, "0": 5}, "b": {"0": 1}, "c": {"1": 1}}
          ]
        }"#.to_string()
    }

    #[test]
    fn setup_is_deterministic_and_idempotent() {
        // 同电路两次 setup → vk 一致（确定性）+ 幂等（磁盘复用）
        let (fp1, vk1) = setup_public(&demo_json()).expect("setup 1");
        let (fp2, vk2) = setup_public(&demo_json()).expect("setup 2");
        assert_eq!(fp1, fp2, "同电路 → 同指纹");
        assert_eq!(vk1, vk2, "同电路 → 同 vk（确定性 setup 实证）");
    }

    #[test]
    fn prove_then_separate_verify() {
        // 分离模式：prove 产出证明 → verify_with_proof 用持久化 vk 独立验证
        let (fp, proof_hex) = prove_real(&demo_json()).expect("prove");
        assert!(!proof_hex.is_empty());
        let ok = verify_with_proof(&demo_json(), &proof_hex).expect("verify");
        assert!(ok, "prove→verify 分离闭环应通过");
        let _ = fp;
    }
}

#[cfg(test)]
mod persist_tests2 {
    use super::*;

    fn demo_json() -> String {
        r#"{
          "num_public": 1,
          "num_private": 3,
          "witness": [1, 35, 3, 9, 27],
          "constraints": [
            {"a": {"2": 1}, "b": {"2": 1}, "c": {"3": 1}},
            {"a": {"3": 1}, "b": {"2": 1}, "c": {"4": 1}},
            {"a": {"4": 1, "2": 1, "0": 5}, "b": {"0": 1}, "c": {"1": 1}}
          ]
        }"#.to_string()
    }

    #[test]
    fn tampered_proof_rejected_robust() {
        let (_fp, proof_hex) = prove_real(&demo_json()).expect("prove");
        let mut bytes = hex::decode(&proof_hex).expect("hex");
        bytes[0] ^= 0xFF;
        let tampered = hex::encode(bytes);
        // Err（反序列化失败）或 Ok(false)（配对失败）都算拒绝
        match verify_with_proof(&demo_json(), &tampered) {
            Ok(ok) => assert!(!ok, "篡改证明配对必须失败"),
            Err(_) => {}  // 反序列化拒绝同样安全
        }
    }
}

#[cfg(test)]
mod ceremony_tests {
    use super::*;

    fn demo_json() -> String {
        r#"{
          "num_public": 1,
          "num_private": 3,
          "witness": [1, 35, 3, 9, 27],
          "constraints": [
            {"a": {"2": 1}, "b": {"2": 1}, "c": {"3": 1}},
            {"a": {"3": 1}, "b": {"2": 1}, "c": {"4": 1}},
            {"a": {"4": 1, "2": 1, "0": 5}, "b": {"0": 1}, "c": {"1": 1}}
          ]
        }"#.to_string()
    }

    #[test]
    fn external_setup_import_then_prove_verify() {
        // 仪式模拟：确定性生成 pk/vk 作为"仪式产出" → 导出 → 导入 → 分离验证
        let circuit = DynamicCircuit::from_json(&demo_json()).unwrap();
        let (pk, vk) = crate::setup_store::load_or_setup::<ark_bn254::Bn254>(
            &demo_json(), circuit).expect("setup");
        let pk_hex = crate::setup_store::export_pk_hex(&pk).expect("export pk");
        let mut vk_buf = Vec::new();
        ark_serialize::CanonicalSerialize::serialize_uncompressed(&vk, &mut vk_buf).unwrap();
        let vk_hex = hex::encode(vk_buf);

        let fp = crate::setup_store::circuit_fingerprint(&demo_json());
        // 导入（模拟仪式产出经 API 上传）
        crate::setup_store::import_external_setup(&fp, &pk_hex, &vk_hex).expect("import");

        // 导入后 prove → verify 闭环（外部 pk 可用）
        let (_fp2, proof_hex) = prove_real(&demo_json()).expect("prove with external pk");
        let ok = verify_with_proof(&demo_json(), &proof_hex).expect("verify");
        assert!(ok, "外部 setup 导入后 prove→verify 应闭环通过");

        // vk 与仪式产出一致（非确定性 seed 生成）
        let vk2_hex = setup_public(&demo_json()).expect("setup public").1;
        assert_eq!(vk_hex, vk2_hex, "导入后 vk 应与仪式产出一致");
    }
}
