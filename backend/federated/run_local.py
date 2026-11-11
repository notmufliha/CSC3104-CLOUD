"""run_local.py - run one federated experiment on this laptop, without Kubernetes: the Flower server and the
simulated phones as separate processes talking over localhost gRPC (the same code the pods run).

  python backend/federated/run_local.py                      # 10 rounds, 10 phones, no noise
  python backend/federated/run_local.py --noise 1.0 --clip 2  # G.8: with clipping + noise

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
    ap.add_argument("--clip", type=float, default=2.0)
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--run", default=None)
    args = ap.parse_args()

    env = {**os.environ, "FL_ROUNDS": str(args.rounds), "FL_PHONES": str(args.phones), "FL_NOISE": str(args.noise),
           "FL_CLIP": str(args.clip), "FL_ADDRESS": f"127.0.0.1:{args.port}", "PYTHONIOENCODING": "utf-8"}
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
