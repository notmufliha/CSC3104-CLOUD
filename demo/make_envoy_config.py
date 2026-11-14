#!/usr/bin/env python3
"""make_envoy_config.py - generates the Envoy bootstrap config used as the internal gRPC load
balancer for the ScamGuard demo (see demo/README.md and demo/ARCHITECTURE.md for why Envoy exists).

Problem this solves (measured on an earlier version of this code, see DEMO_TASK.md section 3): a gRPC
client (the gateway, the aggregator) opens ONE long-lived HTTP/2 connection to its upstream. A plain
Kubernetes ClusterIP Service load-balances per TCP CONNECTION, not per request, so every request from one
gateway pod lands on the SAME backend pod forever -- extra replicas added by the HPA sit idle, and killing
"the lucky" pod breaks the call. Fix: put Envoy in front of every scalable gRPC service, point it at a
headless Service (so Envoy's own DNS resolution sees every pod IP) and let ENVOY round-robin per request.

Usage:
    python demo/make_envoy_config.py                 # writes demo/k8s/envoy-config.yaml (Kubernetes FQDNs)
    python demo/make_envoy_config.py --docker         # writes demo/envoy-config.docker.yaml (plain compose
                                                       # service names, for the Phase-3 plain-Docker test --
                                                       # FQDNs like *.svc.cluster.local do not resolve there)
    python demo/make_envoy_config.py --out <path>     # override the output path

Every setting that matters for the demo sits in the block below, with a plain-English comment, so changing
a port or a timeout never means hand-editing YAML.
"""
import argparse
import sys
from pathlib import Path

# --------------------------------------------------------------------------------------------------
# SETTINGS -- change these, not the YAML-building code below.
# --------------------------------------------------------------------------------------------------

NAMESPACE = "scamguard"  # Kubernetes namespace the demo manifests use (must match make_manifests.py)

# The 6 workers, in the same order docker-compose.yml lists them. Each gets one Envoy listener port.
# Port numbers match DEMO_TASK.md section 3 (50061-50066).
WORKERS = [
    ("urgency_en", 50061),
    ("money_en", 50062),
    ("impersonation_en", 50063),
    ("urgency_zh", 50064),
    ("money_zh", 50065),
    ("impersonation_zh", 50066),
]
AGGREGATOR_PORT = 50067
BERT_PORT = 50068
ADMIN_PORT = 9901

# Every scalable service's own gRPC port (what the pod itself listens on -- unchanged by Envoy).
WORKER_UPSTREAM_PORT = 50051
AGGREGATOR_UPSTREAM_PORT = 50052
# The aggregator's small HTTP server (AGGREGATOR_HTTP_ADDR in docker-compose.yml). Envoy health-checks the
# aggregator here with GET /health, because the aggregator does NOT serve the grpc.health.v1 service: a gRPC
# health check gets UNIMPLEMENTED, Envoy marks both aggregator pods failed_active_hc and falls into panic mode.
AGGREGATOR_HTTP_PORT = 8082
BERT_UPSTREAM_PORT = 50051

# Gateway's WORKER_TIMEOUT_MS / aggregator's AGGREGATOR_TIMEOUT_MS / BERT_TIMEOUT_MS from docker-compose.yml.
# Envoy's route timeout for each hop is set a bit ABOVE the caller's own timeout, so the caller (gateway or
# aggregator) is always the one that gives up first and decides what to do (missing/timeout), rather than
# Envoy cutting the connection first and turning a slow-but-alive answer into a hard error.
GATEWAY_WORKER_TIMEOUT_MS = 1400
AGGREGATOR_TIMEOUT_MS = 550
BERT_TIMEOUT_MS = 300
# Workstream B: the gateway's END_CALL_TIMEOUT_MS. EndCall runs the end-of-call review (up to 1.5 s), so it gets
# its own route on the aggregator listener instead of Aggregate's short timeout.
END_CALL_TIMEOUT_MS = 2000
END_CALL_PATH = "/scam_detect.AggregatorService/EndCall"
ENVOY_TIMEOUT_MARGIN_MS = 200  # how far above the caller's own timeout Envoy's route timeout sits

# Active health checks: how Envoy itself decides a pod is ready to receive traffic (independent of
# Kubernetes readiness, since Envoy's own DNS cache / connection pool needs its own view).
# Workers and BERT: gRPC health check. Aggregator: HTTP GET /health on port 8082 (see AGGREGATOR_HTTP_PORT).
HEALTH_CHECK_INTERVAL_S = 2
HEALTHY_THRESHOLD = 1      # 1 good check -> back in rotation (fast recovery for the demo)
UNHEALTHY_THRESHOLD = 2    # 2 bad checks -> taken out of rotation

# Outlier detection: eject a pod that is actively erroring, even if health checks still pass.
CONSECUTIVE_5XX = 2
CONSECUTIVE_GATEWAY_FAILURE = 2

# Retry policy for transient failures (a pod restarting, a connection reset mid-request).
RETRY_ON = "connect-failure,refused-stream,unavailable,reset"
NUM_RETRIES = 2

DNS_REFRESH_RATE_S = 2  # how often Envoy re-resolves the headless Service to pick up new/dead pod IPs


# --------------------------------------------------------------------------------------------------


def fqdn(svc_name: str, docker: bool) -> str:
    """svc_name uses hyphens (Kubernetes Service naming, e.g. 'urgency-en'); docker mode uses the
    docker-compose service name instead (underscores, e.g. 'urgency_en'), passed in separately."""
    if docker:
        return svc_name
    return f"{svc_name}-hl.{NAMESPACE}.svc.cluster.local"


def route_timeout_s(caller_timeout_ms: int) -> str:
    return f"{(caller_timeout_ms + ENVOY_TIMEOUT_MARGIN_MS) / 1000:.2f}s"


def health_check_yaml(health: str) -> str:
    """The one health_checks entry for a cluster. 'grpc' = the standard grpc.health.v1 check (the six workers
    and BERT serve it). 'http' = GET /health on the aggregator's HTTP port (the aggregator has no gRPC health
    service, so a gRPC check would always fail with UNIMPLEMENTED)."""
    if health == "http":
        check = "http_health_check: { path: /health }"
    else:
        check = "grpc_health_check: {}"
    return f"""    health_checks:
      - timeout: 1s
        interval: {HEALTH_CHECK_INTERVAL_S}s
        healthy_threshold: {HEALTHY_THRESHOLD}
        unhealthy_threshold: {UNHEALTHY_THRESHOLD}
        {check}
"""


def cluster_yaml(name: str, host: str, port: int, health: str = "grpc", health_port: int = 0) -> str:
    # health_port: when the health check must go to a different port than the traffic (the aggregator:
    # gRPC on 50052, HTTP /health on 8082), Envoy needs health_check_config on the endpoint.
    hc_port = ""
    if health_port:
        hc_port = "\n                health_check_config: { port_value: %d }" % health_port
    return f"""  - name: {name}
    connect_timeout: 0.25s
    type: STRICT_DNS
    dns_refresh_rate: {DNS_REFRESH_RATE_S}s
    dns_lookup_family: V4_ONLY
    lb_policy: ROUND_ROBIN
    typed_extension_protocol_options:
      envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
        "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
        explicit_http_config:
          http2_protocol_options: {{}}
    load_assignment:
      cluster_name: {name}
      endpoints:
        - lb_endpoints:
            - endpoint:
                address:
                  socket_address: {{ address: {host}, port_value: {port} }}{hc_port}
{health_check_yaml(health)}    outlier_detection:
      consecutive_5xx: {CONSECUTIVE_5XX}
      consecutive_gateway_failure: {CONSECUTIVE_GATEWAY_FAILURE}
      interval: 2s
      base_ejection_time: 5s
"""


def route_yaml(match: str, cluster: str, route_timeout: str) -> str:
    return f"""                      - match: {{ {match} }}
                        route:
                          cluster: {cluster}
                          timeout: {route_timeout}
                          retry_policy:
                            retry_on: "{RETRY_ON}"
                            num_retries: {NUM_RETRIES}
                            per_try_timeout: {route_timeout}
"""


def listener_yaml(name: str, listen_port: int, cluster: str, route_timeout: str, extra_routes: str = "") -> str:
    """extra_routes: more specific routes, matched before the catch-all "/"."""
    return f"""  - name: listener_{name}
    address:
      socket_address: {{ address: 0.0.0.0, port_value: {listen_port} }}
    filter_chains:
      - filters:
          - name: envoy.filters.network.http_connection_manager
            typed_config:
              "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
              stat_prefix: {name}
              codec_type: AUTO
              http2_protocol_options: {{}}
              route_config:
                name: local_route
                virtual_hosts:
                  - name: {name}_service
                    domains: ["*"]
                    routes:
{extra_routes}{route_yaml('prefix: "/"', cluster, route_timeout)}              http_filters:
                - name: envoy.filters.http.router
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
"""


def build(docker: bool) -> str:
    listeners = []
    clusters = []

    for svc, port in WORKERS:
        host = fqdn(svc.replace("_", "-"), docker) if not docker else svc
        listeners.append(listener_yaml(svc, port, svc, route_timeout_s(GATEWAY_WORKER_TIMEOUT_MS)))
        clusters.append(cluster_yaml(svc, host, WORKER_UPSTREAM_PORT))

    agg_host = fqdn("aggregator", docker) if not docker else "aggregator"
    end_call_route = route_yaml(f'path: "{END_CALL_PATH}"', "aggregator", route_timeout_s(END_CALL_TIMEOUT_MS))
    listeners.append(listener_yaml("aggregator", AGGREGATOR_PORT, "aggregator", route_timeout_s(AGGREGATOR_TIMEOUT_MS),
                                   extra_routes=end_call_route))
    clusters.append(cluster_yaml("aggregator", agg_host, AGGREGATOR_UPSTREAM_PORT,
                                 health="http", health_port=AGGREGATOR_HTTP_PORT))

    bert_host = fqdn("bert", docker) if not docker else "bert"
    listeners.append(listener_yaml("bert", BERT_PORT, "bert", route_timeout_s(BERT_TIMEOUT_MS)))
    clusters.append(cluster_yaml("bert", bert_host, BERT_UPSTREAM_PORT))

    mode = "plain Docker/compose (short service names)" if docker else f"Kubernetes (namespace {NAMESPACE}, headless-Service FQDNs)"
    header = f"""# GENERATED by demo/make_envoy_config.py -- do not hand-edit, re-run the script instead.
# Mode: {mode}
#
# Why this file exists: see the big comment at the top of make_envoy_config.py (gRPC's one-connection-per-
# client problem defeats Kubernetes' per-connection Service load balancing; Envoy load-balances per request
# instead, against a headless Service so it can see every pod IP).
#
# Ports: workers {WORKERS[0][1]}-{WORKERS[-1][1]}, aggregator {AGGREGATOR_PORT}, bert {BERT_PORT}, admin {ADMIN_PORT}.
"""
    admin = f"""admin:
  address:
    socket_address: {{ address: 0.0.0.0, port_value: {ADMIN_PORT} }}
"""
    body = "static_resources:\n  listeners:\n" + "".join(listeners) + "  clusters:\n" + "".join(clusters)
    return header + admin + body


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--docker", action="store_true", help="write plain short service names for a Docker/compose test instead of Kubernetes FQDNs")
    ap.add_argument("--out", type=Path, default=None, help="output path (default: demo/k8s/envoy-config.yaml, or demo/envoy-config.docker.yaml with --docker)")
    args = ap.parse_args()

    out = args.out
    if out is None:
        demo_dir = Path(__file__).resolve().parent
        # k8s output goes under k8s/_generated/, NOT directly in k8s/, because `kubectl apply -f demo/k8s/`
        # (non-recursive) would otherwise try to apply this raw Envoy config as if it were a Kubernetes
        # object and fail ("didn't recognize object type"). make_manifests.py embeds this file's contents
        # into a ConfigMap (02-envoy-configmap.yaml), which IS a valid object kubectl apply picks up.
        out = (demo_dir / "envoy-config.docker.yaml") if args.docker else (demo_dir / "k8s" / "_generated" / "envoy-config.yaml")

    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(build(args.docker), encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
