"""run_phones.py - N simulated phones in ONE container, for docker compose (Kubernetes runs one pod per phone instead,
an Indexed Job). Starts client.py once per phone id 0..FL_PHONES-1 and exits when they all have.

  FL_PHONES=10 FL_SERVER=fl-server:8080 python run_phones.py
"""
import os
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent


def main():
    n = int(os.environ.get("FL_PHONES", 10))
    procs = [subprocess.Popen([sys.executable, str(HERE / "client.py")], env={**os.environ, "PHONE_ID": str(i)})
             for i in range(n)]
    codes = [p.wait() for p in procs]
    print(f"phones finished: {codes.count(0)}/{n} ok", flush=True)
    sys.exit(0 if all(c == 0 for c in codes) else 1)


if __name__ == "__main__":
    main()
