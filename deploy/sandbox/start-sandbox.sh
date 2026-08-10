#!/usr/bin/env bash
# NexusChain Sandbox: Local multi-node chain simulation
# Usage: ./start-sandbox.sh [node_count]
# Starts N core nodes locally with shared genesis, simulating a multi-node network.

set -e
NODE_COUNT=${1:-4}
BASE_PORT_RPC=19585
BASE_PORT_P2P=9585
SANDBOX_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$SANDBOX_DIR/data"

echo "=== NexusChain Sandbox: Starting $NODE_COUNT nodes ==="

# Clean previous sandbox data
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR"

# Generate bootstrap list
BOOTSTRAPS=""
for i in $(seq 0 $((NODE_COUNT - 1))); do
  P2P_PORT=$((BASE_PORT_P2P + i))
  if [ -n "$BOOTSTRAPS" ]; then BOOTSTRAPS="$BOOTSTRAPS,"; fi
  BOOTSTRAPS="${BOOTSTRAPS}nexus://127.0.0.1:${P2P_PORT}"
done

echo "Bootstraps: $BOOTSTRAPS"

# Start each node
for i in $(seq 0 $((NODE_COUNT - 1))); do
  RPC_PORT=$((BASE_PORT_RPC + i))
  P2P_PORT=$((BASE_PORT_P2P + i))
  NODE_DIR="$DATA_DIR/node-$i"
  mkdir -p "$NODE_DIR"

  echo "Starting node-$i (RPC:$RPC_PORT, P2P:$P2P_PORT)..."

  # In a real setup, this would launch the nexus-core jar.
  # For sandbox simulation, we create a marker file and log the config.
  cat > "$NODE_DIR/node.conf" << EOF
node_id=node-$i
rpc_port=$RPC_PORT
p2p_port=$P2P_PORT
bootstraps=$BOOTSTRAPS
genesis_file=$SANDBOX_DIR/../k8s/genesis.json
validators_file=$SANDBOX_DIR/../k8s/validators.json
mining_enabled=true
discovery_enabled=true
data_dir=$NODE_DIR
EOF

  echo "  Config written to $NODE_DIR/node.conf"
done

echo ""
echo "=== Sandbox Ready ==="
echo "Nodes configured: $NODE_COUNT"
echo "RPC endpoints: http://127.0.0.1:$BASE_PORT_RPC .. http://127.0.0.1:$((BASE_PORT_RPC + NODE_COUNT - 1))"
echo ""
echo "To start actual nodes (requires nexus-core jar):"
echo "  for i in \$(seq 0 $((NODE_COUNT - 1))); do"
echo "    java -jar nexus-core.jar --config=\$DATA_DIR/node-\$i/node.conf &"
echo "  done"
echo ""
echo "To stop: pkill -f nexus-core"
