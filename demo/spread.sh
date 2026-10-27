#!/usr/bin/env bash
# spread.sh - shows how many requests each pod handled (via Envoy admin stats) + kubectl get hpa,pods.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$SCRIPT_DIR/tools/spread_report.py"
