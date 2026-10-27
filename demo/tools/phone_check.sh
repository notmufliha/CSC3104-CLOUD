#!/usr/bin/env bash
# phone_check.sh - checks the gateway is reachable the way a real phone on the same Wi-Fi would reach it:
# by the laptop's Wi-Fi IP, not localhost. Run this AFTER demo/port-forward.sh is running in another
# terminal (or after `docker compose up`, which also listens on all interfaces).
#
# Usage: ./demo/tools/phone_check.sh [laptop-ip]   (auto-detects the IP if not given)
set -uo pipefail

IP="${1:-}"
if [ -z "$IP" ]; then
  IP=$(python3 - <<'PY'
import socket
try:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("8.8.8.8", 80))
    print(s.getsockname()[0])
    s.close()
except Exception:
    print("")
PY
)
fi
if [ -z "$IP" ]; then
  echo "Could not auto-detect your Wi-Fi IP. Run 'ifconfig' (Mac/Linux) or 'ipconfig' (Windows) and pass it:"
  echo "  ./demo/tools/phone_check.sh 192.168.1.23"
  exit 1
fi

URL="http://${IP}:8080"
echo "Checking gateway at ${URL} (this is the address you'd type into the app's Settings)..."
echo

echo "--- GET ${URL}/healthz ---"
if ! curl -sS --max-time 5 "${URL}/healthz"; then
  echo
  echo "FAILED. Checklist:"
  echo "  1. Is 'demo/port-forward.sh' (or docker compose) running?"
  echo "  2. Windows: allow inbound 8080 on Private networks:"
  echo "       New-NetFirewallRule -DisplayName \"ScamGuard demo 8080\" -Direction Inbound -LocalPort 8080 -Protocol TCP -Action Allow -Profile Private"
  echo "  3. Phone and laptop on the SAME Wi-Fi (venue Wi-Fi often blocks phone-to-phone/laptop traffic --"
  echo "     use a phone hotspot instead, laptop joins the phone's hotspot)."
  echo "  4. USB alternative: adb reverse tcp:8080 tcp:8080, then use http://localhost:8080/ in the app."
  exit 1
fi
echo
echo

echo "--- POST ${URL}/v1/utterance (one sample caller line) ---"
curl -sS --max-time 5 -X POST "${URL}/v1/utterance" \
  -H "Content-Type: application/json" \
  -d '{"call_id": "phone-check", "seq": 1, "speaker": "caller", "lang": "en", "text": "This is the police. Transfer the money to the safe account now and read me the OTP."}'
echo
echo
echo "If you saw a verdict above, the phone/emulator will work with this URL: ${URL}/"
