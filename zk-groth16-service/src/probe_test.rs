// 对照验证：probe 的固定电路（此前 GROTH16_VERIFY=true）在当前环境是否仍成功
use ark_bn254::{Bn254, Fr};
use ark_groth16::Groth16;
use ark_snark::SNARK;
use ark_relations::r1cs::{ConstraintSynthesizer, ConstraintSystemRef, LinearCombination, SynthesisError, Variable};
use ark_std::rand::{rngs::StdRng, SeedableRng};

struct CubeCircuit { x: Option<u64> }
impl ConstraintSynthesizer<Fr> for CubeCircuit {
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

#[test]
fn probe_fixed_circuit_verify_true() {
    let mut rng = StdRng::seed_from_u64(42);
    let circuit = CubeCircuit { x: Some(3) };
    let pk = Groth16::<Bn254>::generate_random_parameters_with_reduction(circuit, &mut rng).unwrap();
    let vk = pk.vk.clone();
    let proof = Groth16::<Bn254>::prove(&pk, CubeCircuit { x: Some(3) }, &mut rng).unwrap();
    let ok = Groth16::<Bn254>::verify(&vk, &[Fr::from(35u64)], &proof).unwrap();
    eprintln!("[probe-control] fixed circuit verify: {}", ok);
    assert!(ok);
}
