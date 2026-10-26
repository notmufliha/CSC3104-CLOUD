#!/usr/bin/env bash
# watch.sh - live view for the presentation: pods + HPA state, refreshing. Ctrl+C to stop.
set -euo pipefail
NAMESPACE=scamguard
kubectl get pods,hpa -n "$NAMESPACE" -w
