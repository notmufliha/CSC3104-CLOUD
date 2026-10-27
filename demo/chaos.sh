#!/usr/bin/env bash
# chaos.sh - kills one random pod of a chosen service and shows it recovering. Best run while
# demo/tools/load_sim.py is running in another terminal, so you can see in-flight requests survive.
#
# Usage: ./demo/chaos.sh [service]     (default: impersonation-en)
set -euo pipefail
NAMESPACE=scamguard
SERVICE="${1:-impersonation-en}"

PODS=$(kubectl get pods -n "$NAMESPACE" -l "app=${SERVICE}" -o jsonpath='{.items[*].metadata.name}')
if [ -z "$PODS" ]; then
  echo "No pods found for app=${SERVICE} in namespace ${NAMESPACE}."
  echo "Known services: redis, envoy-lb, aggregator, bert, gateway, urgency-en, money-en, impersonation-en, urgency-zh, money-zh, impersonation-zh"
  exit 1
fi
PODS_ARRAY=($PODS)
VICTIM=${PODS_ARRAY[$RANDOM % ${#PODS_ARRAY[@]}]}

echo "Before:"
kubectl get pods -n "$NAMESPACE" -l "app=${SERVICE}"
echo
echo ">>> Deleting pod: $VICTIM"
kubectl delete pod -n "$NAMESPACE" "$VICTIM"

echo
echo "Watching it come back (Ctrl+C to stop watching -- the demo keeps running):"
kubectl get pods -n "$NAMESPACE" -l "app=${SERVICE}" -w
