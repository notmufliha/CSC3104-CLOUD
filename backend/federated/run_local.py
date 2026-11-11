"""run_local.py - run one federated round set on this laptop, without Kubernetes: the Flower server and the
simulated phones as separate processes talking over localhost gRPC (the same code the pods run).

  python backend/federated/run_local.py                               # 10 rounds, 10 phones, no noise
  python backend/federated/run_local.py --noise 0.1 --port 8082       # G.8: clipping (norm 10) + noise
  python backend/federated/run_local.py --publish --init current      # G.10: a "weekly" round that publishes
                                                                      # passing models (needs local_stack.py running)

Each run writes backend/federated/results/<run>.json; plot_rounds.py turns them into the G.7/G.8 chart.
"""
import argparse
import os
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rounds", type=int, default=10)
    ap.add_argument("--phones", type=int, default=10)
    ap.add_argument("--noise", type=float, default=0.0)
    ap.add_argument("--clip", type=float, default=10.0)
    ap.add_argument("--init", choices=["zeros", "current"], default="zeros")
    ap.add_argument("--publish", action="store_true", help="publish passing models to the registry (local_stack.py)")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--run", default=None)
    args = ap.parse_args()

    env = {**os.environ, "FL_ROUNDS": str(args.rounds), "FL_PHONES": str(args.phones), "FL_NOISE": str(args.noise),
           "FL_CLIP": str(args.clip), "FL_INIT": args.init, "FL_PUBLISH": "1" if args.publish else "0",
           "FL_ADDRESS": f"127.0.0.1:{args.port}", "PYTHONIOENCODING": "utf-8"}
    if args.publish:
        env.setdefault("REVIEW_URL", "http://127.0.0.1:8090")   # local_stack.py's registry
    if args.run:
        env["FL_RUN"] = args.run
    server = subprocess.Popen([sys.executable, str(HERE / "server.py")], env=env)
    time.sleep(8)  # let the server load the test sets and open its port
    phones = [subprocess.Popen([sys.executable, str(HERE / "client.py")],
                               env={**env, "PHONE_ID": str(i), "FL_SERVER": f"127.0.0.1:{args.port}"},
                               stdout=subprocess.DEVNULL)
              for i in range(args.phones)]
    code = server.wait()
    for p in phones:
        p.wait(timeout=60)
    sys.exit(code)


if __name__ == "__main__":
    main()
