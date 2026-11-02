#!/usr/bin/env bash
# expose.sh - Workstream E: gives the phone ONE stable HTTPS address for the backend.
#
# The phone talks to the Ingress controller (ingress-nginx), never to a gateway pod. The Ingress follows
# the gateway Service, so deleting or restarting the gateway pod does not change the address and does not
# drop this forward (port-forward.sh, by contrast, is pinned to one gateway pod).
#
# Why a port-forward and not `minikube tunnel`: with minikube's Docker driver on Windows/Mac, the tunnel
# only listens on the laptop's 127.0.0.1, which a phone on Wi-Fi cannot reach. This forward listens on
# every interface, so the phone uses https://<laptop Wi-Fi IP>:8443/api/. See demo/STABLE_ADDRESS.md.
#
# Each run also refreshes the HTTPS certificate if the laptop's IP changed (new Wi-Fi network). The CA
# stays the same, so the phone keeps trusting it.
#
# Usage: ./demo/expose.sh   (leave it running in its own terminal during the demo)
PORT=8443
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR/.."

PY=python3; command -v python3 >/dev/null 2>&1 || PY=python
"$PY" demo/make_tls.py --apply || { echo "ERROR: could not refresh the certificate (is the cluster up? run ./demo/up.sh)"; exit 1; }

LAPTOP_IP=""
if command -v ipconfig >/dev/null 2>&1 && ipconfig getifaddr en0 >/dev/null 2>&1; then
  LAPTOP_IP=$(ipconfig getifaddr en0)                     # macOS Wi-Fi
elif command -v hostname >/dev/null 2>&1 && hostname -I >/dev/null 2>&1; then
  LAPTOP_IP=$(hostname -I | awk '{print $1}')             # Linux / WSL
fi
[ -z "$LAPTOP_IP" ] && LAPTOP_IP="<your Wi-Fi IPv4 address>"

cat <<EOF

==============================================================================
  Save ONE of these in the app's Settings (it stays valid across cluster
  restarts and gateway pod restarts; only a new Wi-Fi network changes the IP):
  EMULATOR   : https://10.0.2.2:${PORT}/api/
  REAL PHONE : https://${LAPTOP_IP}:${PORT}/api/   (same Wi-Fi)
  USB        : adb reverse tcp:${PORT} tcp:${PORT}   then https://127.0.0.1:${PORT}/api/
  First time only: install demo/tls/ca.crt on the device (demo/STABLE_ADDRESS.md).
==============================================================================

Forwarding ${PORT} -> ingress-nginx :443 (Ctrl+C to stop)...
EOF

while true; do
  kubectl port-forward --address 0.0.0.0 -n ingress-nginx svc/ingress-nginx-controller "${PORT}:443"
  echo "forward dropped (ingress controller restarted?) -- restarting in 1s..."
  sleep 1
done
