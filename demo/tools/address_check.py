#!/usr/bin/env python3
"""address_check.py - Workstream E "done when" check: the phone's saved address keeps working.

1. Calls GET /healthz and one POST /v1/utterance on the address the phone would save, over HTTPS,
   verifying the certificate against demo/tls/ca.crt exactly as the app does (no "ignore certificate").
2. With --kill-gateway: deletes the gateway pod, then keeps calling the SAME address until a new pod
   answers, and reports how long the gap was and the old and new pod names (and pod IPs).

Cluster restart check: run `minikube stop`, `minikube start`, `.\\demo\\up.ps1`, `.\\demo\\expose.ps1`, then
run this script again with the same --url. Same address, still answers = done.

Usage (with demo/expose.ps1 or expose.sh running):
    python demo/tools/address_check.py                                   # https://127.0.0.1:8443/api/
    python demo/tools/address_check.py --url https://192.168.1.23:8443/api/  # the phone's address
    python demo/tools/address_check.py --kill-gateway
"""
import argparse
import json
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

CA = Path(__file__).resolve().parents[1] / "tls" / "ca.crt"
NAMESPACE = "scamguard"


def call(ctx, url, path, body=None, timeout=4.0):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url + path, data=data, method="POST" if data else "GET",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout, context=ctx) as r:
        return r.status, json.loads(r.read() or b"{}")


def gateway_pod():
    out = subprocess.run(["kubectl", "get", "pods", "-n", NAMESPACE, "-l", "app=gateway",
                          "--field-selector=status.phase=Running",
                          "-o", "jsonpath={range .items[*]}{.metadata.name} {.status.podIP}{\"\\n\"}{end}"],
                         capture_output=True, text=True, check=True).stdout.split("\n")
    return [line for line in out if line.strip()]


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--url", default="https://127.0.0.1:8443/api/")
    ap.add_argument("--ca", default=str(CA))
    ap.add_argument("--kill-gateway", action="store_true")
    ap.add_argument("--max-wait", type=float, default=120.0)
    args = ap.parse_args()
    url = args.url if args.url.endswith("/") else args.url + "/"

    ctx = ssl.create_default_context(cafile=args.ca) if url.startswith("https") else None
    if ctx is None:
        print("WARNING: plain http -- transcripts would cross the network unencrypted.")

    print(f"Address: {url}")
    try:
        status, health = call(ctx, url, "healthz")
        print(f"  GET  /healthz       -> {status} {health}")
        if health.get("status") != "ok":
            # e.g. nginx's own built-in /healthz (empty 200) answered instead of the gateway
            print("FAILED: that answer did not come from the gateway. Kubernetes HTTPS addresses end in /api/.")
            return 1
        line = {"call_id": f"addr-check-{int(time.time())}", "seq": 1, "speaker": "caller", "lang": "en",
                "text": "Hello, this is a check of the stable address."}
        status, ans = call(ctx, url, "v1/utterance", line)
        print(f"  POST /v1/utterance  -> {status} verdict={ans.get('verdict')} latency_ms={ans.get('latency_ms')}")
    except ssl.SSLError as e:
        print(f"FAILED (certificate): {e}\n  Re-run: python demo/make_tls.py --apply   (does the certificate cover this IP?)")
        return 1
    except (urllib.error.URLError, OSError) as e:
        print(f"FAILED: {e}\n  Is demo/expose.ps1 (or expose.sh) running? Is the cluster up?")
        return 1

    if not args.kill_gateway:
        print("OK")
        return 0

    before = gateway_pod()
    print(f"\nGateway pod before: {before}")
    name = before[0].split()[0]
    subprocess.run(["kubectl", "delete", "pod", "-n", NAMESPACE, name, "--wait=false"], check=True)
    t0 = time.monotonic()
    failures = 0
    while True:
        elapsed = time.monotonic() - t0
        if elapsed > args.max_wait:
            print(f"FAILED: no answer on {url} within {args.max_wait:.0f}s")
            return 1
        try:
            status, h = call(ctx, url, "healthz", timeout=2.0)
            # The old pod can still answer while it shuts down; wait until a different pod is serving.
            after = gateway_pod()
            if status == 200 and h.get("status") == "ok" and after and all(name != a.split()[0] for a in after):
                break
        except (urllib.error.URLError, OSError):
            failures += 1
        time.sleep(0.25)
    print(f"Gateway pod after:  {after}")
    print(f"Same address {url} answered again {elapsed:.1f}s after the delete "
          f"({failures} failed attempts in between).")
    print("OK: the address did not change.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
