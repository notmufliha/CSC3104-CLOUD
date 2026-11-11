"""client.py - Workstream G.6: one simulated phone in the federated round (a Flower client).

What a phone does when the server picks it for a round:
  1. receives the current global models (urgency, money, impersonation)
  2. trains each one a few local passes on its OWN labelled lines for that signal (fedcore.local_train); the lines
     never leave it. A phone with no lines for a signal sends that model back unchanged.
  3. if the server asks for clipping (G.8), scales its whole change down to the clipping norm
  4. sends back only the new weights

In Kubernetes each phone is one pod of an Indexed Job; the pod index picks the data slices. Locally,
run_local.py starts ten of these.

  PHONE_ID=3 FL_SERVER=localhost:8080 python backend/federated/client.py
"""
import os
import sys
import time
from pathlib import Path

import flwr as fl
import numpy as np
from flwr.common.differential_privacy_constants import KEY_CLIPPING_NORM
from scipy import sparse

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import fedcore  # noqa: E402

DATA = Path(os.environ.get("FL_DATA", HERE / "data"))


class Phone(fl.client.NumPyClient):
    def __init__(self, phone_id: int):
        self.id = phone_id
        self.data = {}
        for m in fedcore.MODELS:
            base = DATA / f"phone_{phone_id}_{m}"
            self.data[m] = (sparse.load_npz(f"{base}.npz").tocsr(), np.load(f"{base}.npy"), np.load(f"{base}_w.npy"))
        print(f"phone-{phone_id}: " + ", ".join(f"{m} {len(y)} lines ({int(y.sum())} positive)"
                                                 for m, (_, y, _) in self.data.items()), flush=True)

    def get_parameters(self, config):
        return fedcore.zeros()

    def fit(self, parameters, config):
        rnd = int(config.get("round", 0))
        new = []
        for k, (m, (w, b)) in enumerate(fedcore.split(parameters).items()):
            X, y, sw = self.data[m]
            if len(y):
                lr = float(config.get(f"lr_{m}", fedcore.LEARNING_RATE[m]))      # the server decides per round
                epochs = int(config.get("epochs", fedcore.LOCAL_EPOCHS))
                w, b = fedcore.local_train(w, b, X, y, sw, lr=lr, epochs=epochs, seed=rnd * 100 + self.id * 3 + k)
            new += [w, b]
        clip = float(config[KEY_CLIPPING_NORM]) if KEY_CLIPPING_NORM in config else float("inf")
        new, norm = fedcore.clip_update(new, parameters, clip)
        print(f"phone-{self.id} round {rnd}: trained, update norm {norm:.3f}", flush=True)
        # num_examples = 1: every phone counts equally in the average. The DP noise is calibrated for that
        # (Flower's DP wrappers assume equal weighting), and it stops one heavy user from dominating.
        return new, 1, {"update_norm": float(norm)}


def main():
    phone_id = int(os.environ.get("PHONE_ID", os.environ.get("JOB_COMPLETION_INDEX", 0)))
    server = os.environ.get("FL_SERVER", "localhost:8080")
    client = Phone(phone_id).to_client()
    # The server pod may start after the phones: retry for up to 5 minutes before giving up.
    for attempt in range(60):
        try:
            fl.client.start_client(server_address=server, client=client)
            return
        except Exception as e:  # connection refused while the server starts
            print(f"phone-{phone_id}: server not ready ({type(e).__name__}), retry {attempt + 1}/60", flush=True)
            time.sleep(5)
    sys.exit(f"phone-{phone_id}: could not reach {server}")


if __name__ == "__main__":
    main()
