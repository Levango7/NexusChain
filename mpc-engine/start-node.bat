@echo off
set MPC_ENGINE_HOST=0.0.0.0
set MPC_ENGINE_PORT=%1
set MPC_PARTY_INDEX=%2
set MPC_PARTY_ID=party-%2
set MPC_PEERS=%3
set MPC_STORAGE_KEY=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
set MPC_AUTH_TOKEN=dev-mpc-engine-token-change-in-prod
set MPC_REQUIRE_AUTH=true
set RUST_LOG=info
"F:\Nexus\NexusChain\mpc-engine\target\debug\mpc-engine.exe"