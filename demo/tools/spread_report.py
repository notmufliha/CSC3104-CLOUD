#!/usr/bin/env python3
"""spread_report.py - shows how many gRPC requests each pod of each scalable service has handled, plus
HPA/pod status. Called by demo/spread.sh and demo/spread.ps1 (kept in one Python file so the logic is
tested once, not twice).

Two ways to count were considered (DEMO_TASK.md asks to "pick the more reliable one after testing"):
  1. grep each pod's logs for a per-request log line.
  2. Envoy admin's /clusters endpoint, which reports per-UPSTREAM-HOST counters (rq_total, rq_active,
     health) -- exactly the per-pod granularity we want, in one HTTP call, independent of each service's
     own log format.
Chosen: (2), because it is one call that works the same way for all 8 upstream clusters (6 workers +
aggregator + bert), and host 1.(DEMO_TASK.md section "Scripts" / demo/spread). (1) would need a different
log pattern per service and a kubectl logs call per pod.

Usage: python demo/tools/spread_report.py
"""
import re
import socket
import subprocess
import sys
import time
import urllib.request

NAMESPACE = "scamguard"
ADMIN_PORT = 9901


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def fetch_clusters_text(local_port: int) -> str:
    url = f"http://127.0.0.1:{local_port}/clusters"
    for _ in range(20):
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                return r.read().decode()
        except Exception:
            time.sleep(0.3)
    raise RuntimeError("could not reach Envoy admin /clusters through the port-forward")


def parse_clusters(text: str) -> dict:
    """cluster_name::ip:port::rq_total::N (and ::health_flags::, ::cx_active::, ...) -> {cluster: [(host, rq_total)]}"""
    per_host_total: dict = {}
    for line in text.splitlines():
        m = re.match(r"^([\w-]+)::([\d.]+:\d+)::rq_total::(\d+)$", line)
        if m:
            cluster, host, total = m.group(1), m.group(2), int(m.group(3))
            per_host_total.setdefault(cluster, {})[host] = total
    return per_host_total


def main() -> int:
    local_port = free_port()
    pf = subprocess.Popen(
        ["kubectl", "port-forward", "-n", NAMESPACE, "svc/envoy-lb", f"{local_port}:{ADMIN_PORT}"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    try:
        text = fetch_clusters_text(local_port)
    except Exception as e:
        print(f"Could not read Envoy admin stats: {e}", file=sys.stderr)
        print("Is the demo up? (demo/up.sh) Is envoy-lb running? kubectl get pods -n scamguard -l app=envoy-lb", file=sys.stderr)
        pf.terminate()
        return 1
    finally:
        pf.terminate()
        try:
            pf.wait(timeout=5)
        except Exception:
            pf.kill()

    per_cluster = parse_clusters(text)
    if not per_cluster:
        print("No cluster stats found yet -- Envoy may have just started, or nothing has sent traffic "
              "through it yet. Run demo/tools/load_sim.py or demo_flow.py first, then try again.")
        return 0

    print(f"{'CLUSTER':<20} {'ENDPOINT':<22} {'REQUESTS':>10}")
    print("-" * 54)
    for cluster in sorted(per_cluster):
        hosts = per_cluster[cluster]
        for host, total in sorted(hosts.items()):
            print(f"{cluster:<20} {host:<22} {total:>10}")
        if len(hosts) > 1:
            spread = list(hosts.values())
            print(f"{'':<20} {'(spread ' + str(min(spread)) + '-' + str(max(spread)) + ')':<22}")
        print()

    print("\n--- kubectl get hpa,pods -n scamguard ---")
    subprocess.run(["kubectl", "get", "hpa,pods", "-n", NAMESPACE])
    return 0


if __name__ == "__main__":
    sys.exit(main())
