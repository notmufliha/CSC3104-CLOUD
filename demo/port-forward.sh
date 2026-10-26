#!/usr/bin/env bash
# port-forward.sh - exposes the in-cluster gateway on localhost:8080 (and your Wi-Fi IP) so the Android
# app (emulator or real phone) can reach it.
#
# IMPORTANT: `kubectl port-forward` is tied to ONE gateway pod. If that pod restarts (chaos.sh kills it,
# a crash, a rollout), the forward dies silently. This script loops and restarts it automatically, so you
# normally don't need to think about this -- but if the app suddenly can't connect, check this terminal:
# if it shows "forward dropped, restarting in 1s" repeatedly, the gateway pod itself is the problem (check
# `kubectl get pods -n scamguard -l app=gateway`), not the port-forward.
#
# Usage: ./demo/port-forward.sh   (leave it running in its own terminal during the demo)
set -uo pipefail
NAMESPACE=scamguard

LAPTOP_IP=$(python3 - <<'PY'
import socket
try:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("8.8.8.8", 80))
    print(s.getsockname()[0])
    s.close()
except Exception:
    print("<could not detect -- run `ipconfig` / `ifconfig` and look for your Wi-Fi adapter's IPv4 address>")
PY
)

echo ""
echo "=============================================================================="
echo "  EMULATOR URL      : http://10.0.2.2:8080/"
echo "  REAL PHONE URL    : http://${LAPTOP_IP}:8080/   (phone must be on the same Wi-Fi)"
echo "  USB ALTERNATIVE   : adb reverse tcp:8080 tcp:8080   then use http://localhost:8080/ in the app"
echo "=============================================================================="
echo ""
echo "Starting port-forward (Ctrl+C to stop)..."

trap 'echo; echo "stopped."; exit 0' INT TERM

while true; do
  kubectl port-forward --address 0.0.0.0 -n "$NAMESPACE" svc/gateway 8080:8080
  echo "forward dropped (gateway pod likely restarted) -- restarting in 1s..."
  sleep 1
done
