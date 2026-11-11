"""local_stack.py - Workstream G without Kubernetes: the model registry + model-download service on this laptop,
so a phone on the same Wi-Fi can download federated models.

Starts the real review service (backend/review, which holds the registry; data in backend/federated/data/registry)
on 127.0.0.1:8090, and the download service on 0.0.0.0:8000 (reachable from the phone). Then, in another terminal,
publish models with:
    python backend/federated/run_local.py --publish --init current
and on the phone set the server address to the http:// address this prints, and tap "Check for model updates".

Needs: pip install -r backend/review/requirements.txt (fastapi, uvicorn, cryptography, python-multipart).
Ctrl+C stops both. Options (for a second, separate stack): --data DIR --review-port 8090 --port 8000.
"""
import argparse
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = HERE / "data" / "registry"


def lan_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))   # no packet is sent; this only picks the Wi-Fi interface's address
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", type=Path, default=HERE / "data", help="holds registry/ and inbox/")
    ap.add_argument("--review-port", type=int, default=8090)
    ap.add_argument("--port", type=int, default=8000, help="what the phone connects to")
    a = ap.parse_args()
    DATA = a.data / "registry"
    DATA.mkdir(parents=True, exist_ok=True)
    key_file = DATA / "review.key"
    if not key_file.exists():
        from cryptography.fernet import Fernet
        key_file.write_bytes(Fernet.generate_key())
    env = {**os.environ, "REVIEW_KEY": key_file.read_text().strip(), "REVIEW_DATA_DIR": str(DATA),
           "REVIEW_URL": f"http://127.0.0.1:{a.review_port}", "PORT": str(a.port), "PYTHONIOENCODING": "utf-8",
           "INBOX_DIR": str(a.data / "inbox")}   # real phones upload their model changes here (inbox.py)
    review = subprocess.Popen([sys.executable, "-m", "uvicorn", "app:app", "--host", "127.0.0.1", "--port", str(a.review_port)],
                              cwd=HERE.parent / "review", env=env)
    time.sleep(3)
    download = subprocess.Popen([sys.executable, str(HERE / "download_service.py")], env=env)
    ip = lan_ip()
    print(f"\nRegistry (internal):     http://127.0.0.1:{a.review_port}/models/urgency_en_phone"
          f"\nPhones download from:    http://{ip}:{a.port}/models/manifest"
          f"\nOn the phone, Settings -> server address:  http://{ip}:{a.port}/   then 'Check for model updates'"
          f"\nPublish models (other terminal): python backend/federated/run_local.py --publish --init current "
          f"--rounds 3 [--real-phones]"
          f"\nReal-phone uploads land in: {a.data / 'inbox' / 'pending'}\n", flush=True)
    try:
        review.wait()
    except KeyboardInterrupt:
        pass
    finally:
        for p in (download, review):
            p.terminate()


if __name__ == "__main__":
    main()
