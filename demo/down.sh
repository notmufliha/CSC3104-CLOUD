#!/usr/bin/env bash
# down.sh - tears down the demo. By default only deletes the 'scamguard' namespace (keeps the minikube
# VM/container running, so the next `up.sh` is fast). Pass --all to delete minikube itself too.
#
# Usage: ./demo/down.sh [--all]
set -euo pipefail
NAMESPACE=scamguard

if [ "${1:-}" = "--all" ]; then
  echo "Deleting namespace '$NAMESPACE' and the minikube cluster..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found --timeout=60s || true
  minikube delete
  echo "Done. Next 'up.sh' will create a fresh minikube cluster from scratch (slower)."
else
  echo "Deleting namespace '$NAMESPACE' (minikube itself stays running)..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found --timeout=60s
  echo "Done. Run './demo/up.sh' to bring it back (images are already built, so this will be fast)."
  echo "Use './demo/down.sh --all' to also delete the minikube cluster."
fi
