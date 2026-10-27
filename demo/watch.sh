#!/usr/bin/env bash
# watch.sh - live view for the presentation: pods + HPA state, refreshing every 2s. Ctrl+C to stop.
#
# Not `kubectl get pods,hpa -n scamguard -w`: kubectl's --watch only supports ONE resource type at a
# time on several kubectl versions (this repo's: "error: you may only specify a single resource type") --
# found for real while testing this script. A clear-and-reprint loop works everywhere and shows both.
set -uo pipefail
NAMESPACE=scamguard

trap 'echo; exit 0' INT TERM

while true; do
  clear
  echo "ScamGuard demo -- namespace: $NAMESPACE -- $(date '+%H:%M:%S') (Ctrl+C to stop)"
  echo
  kubectl get pods,hpa -n "$NAMESPACE"
  sleep 2
done
