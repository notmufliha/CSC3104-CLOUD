"""bridge_client.py - Workstream G: brings REAL phones into a federated round.

Real phones can't sit in a Flower round (they train when they can and upload when they're online), so they upload
their model change to the inbox (inbox.py) at any time. When the server starts a round, this one extra Flower
client takes every waiting upload whose base version is the version the round starts from, averages them, clips
the result like any phone's (G.8), and returns it. It reports num_examples = the number of real phones it carries,
so in FedAvg each real phone counts exactly as much as one simulated phone. Used uploads move to inbox/used.

Only round 1 carries them: an upload is a change against the published version, which is what round 1 starts from
(FL_INIT=current). In later rounds it returns the model unchanged with weight 0.

  INBOX_DIR=... REVIEW_URL=... FL_SERVER=localhost:8080 python backend/federated/bridge_client.py
"""
import base64
import os
import sys
import time
from pathlib import Path

import flwr as fl
import numpy as np
from flwr.common.differential_privacy_constants import KEY_CLIPPING_NORM

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fedcore  # noqa: E402
import inbox  # noqa: E402
import registry  # noqa: E402


def current_versions() -> dict:
    out = {}
    for m in fedcore.MODELS:
        meta = registry.latest(f"{m}_en_phone")
        out[m] = meta["version"] if meta else None
    return out


class Bridge(fl.client.NumPyClient):
    def get_parameters(self, config):
        return fedcore.zeros()

    def fit(self, parameters, config):
        rnd = int(config.get("round", 0))
        if rnd != 1:
            return parameters, 0, {"update_norm": 0.0, "real_phones": 0}
        versions = current_versions()
        used, phones = [], set()
        sums = {m: [np.zeros(fedcore.dims(m)), 0.0, 0] for m in fedcore.MODELS}   # delta sum, intercept sum, count
        for path, doc in inbox.pending():
            took = False
            for m, entry in doc["models"].items():
                if entry["base"] != versions.get(m):
                    print(f"[bridge] phone {doc['phone'][:8]}: {m} trained on {entry['base']}, round starts from "
                          f"{versions.get(m)}: skipped (the phone will retrain after it downloads the new model)",
                          flush=True)
                    continue
                d = np.frombuffer(base64.b64decode(entry["delta_b64"]), dtype="<f4").astype(np.float64)
                sums[m][0] += d
                sums[m][1] += float(entry["intercept_delta"])
                sums[m][2] += 1
                took = True
            if took:
                used.append(path)
                phones.add(doc["phone"])
        if not phones:
            print("[bridge] no real-phone updates waiting for this version", flush=True)
            return parameters, 0, {"update_norm": 0.0, "real_phones": 0}
        new = []
        for k, m in enumerate(fedcore.MODELS):
            w, b = parameters[2 * k], parameters[2 * k + 1]
            total, icpt, n = sums[m]
            if n:
                # The average change of the phones that trained this model; phones that didn't train it add nothing.
                w = (w.astype(np.float64) + total / n).astype(np.float32)
                b = np.array([float(b[0]) + icpt / n], dtype=np.float32)
            new += [w, b]
        clip = float(config[KEY_CLIPPING_NORM]) if KEY_CLIPPING_NORM in config else float("inf")
        new, norm = fedcore.clip_update(new, parameters, clip)
        inbox.mark_used(used)
        print(f"[bridge] round 1: {len(phones)} real phone(s): " +
              ", ".join(f"{m} from {sums[m][2]}" for m in fedcore.MODELS if sums[m][2]) + f"; change norm {norm:.3f}",
              flush=True)
        return new, len(phones), {"update_norm": float(norm), "real_phones": len(phones)}


def main():
    server = os.environ.get("FL_SERVER", "localhost:8080")
    for attempt in range(60):
        try:
            fl.client.start_client(server_address=server, client=Bridge().to_client())
            return
        except Exception as e:
            print(f"[bridge] server not ready ({type(e).__name__}), retry {attempt + 1}/60", flush=True)
            time.sleep(5)
    sys.exit(f"bridge: could not reach {server}")


if __name__ == "__main__":
    main()
