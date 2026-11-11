"""plot_rounds.py - Workstream G.7 / G.8: accuracy per round, with and without noise, for the report.

Reads every backend/federated/results/fedavg_*.json that started from empty models (one per run of server.py) and
draws ROC-AUC after each round on every fixed human-labelled test set of every model, one line per noise level,
with the centrally trained hashing model (G.1) as a dashed reference. Also writes results/rounds_table.csv.

  python backend/federated/plot_rounds.py   ->  backend/federated/results/rounds.png
"""
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

RESULTS = Path(__file__).resolve().parent / "results"
COLORS = ["#1f6feb", "#d97706", "#b42318", "#6b21a8", "#047857"]


def main():
    # Only runs from empty models: they show how fast FedAvg learns (runs from the current models are the
    # weekly-update case, reported by their release checks instead).
    runs = [json.loads(p.read_text()) for p in RESULTS.glob("fedavg_*.json")]
    runs = sorted((r for r in runs if r.get("init", "zeros") == "zeros" and "models" in r),
                  key=lambda r: r["noise_multiplier"])
    if not runs:
        raise SystemExit(f"no results in {RESULTS}: run run_local.py first")
    rows = [{"run": r["run"], "noise": r["noise_multiplier"], "clip": r["clipping_norm"], **h}
            for r in runs for h in r["history"]]
    pd.DataFrame(rows).to_csv(RESULTS / "rounds_table.csv", index=False)
    panels = [k[:-len("_roc_auc")] for k in runs[0]["history"][0] if k.endswith("_roc_auc")]
    central = {f"{g['model']}/{g['test_set']}": g["hash_roc_auc"]
               for gold in runs[0]["central_gold"].values() for g in gold}

    cols = 3
    nrows = -(-len(panels) // cols)
    fig, axes = plt.subplots(nrows, cols, figsize=(13, 3.6 * nrows), sharex=True)
    axes = axes.ravel()
    for ax, key in zip(axes, panels):
        for color, r in zip(COLORS, runs):
            xs = [h["round"] for h in r["history"]]
            ys = [h[f"{key}_roc_auc"] for h in r["history"]]
            name = "no noise" if r["noise_multiplier"] == 0 else f"noise {r['noise_multiplier']:g} (clip {r['clipping_norm']:g})"
            ax.plot(xs, ys, marker="o", ms=3, lw=1.8, color=color, label=name)
        if key in central:
            ax.axhline(central[key], color="#444", ls="--", lw=1.1, label="central model (G.1)")
        model, test_set = key.split("/")
        ax.set_title(f"{model}: {test_set}", fontsize=10.5, loc="left")
        ax.set_ylim(0.4, 1.0)
        ax.set_xticks(range(0, max(r["rounds"] for r in runs) + 1, 2))
        ax.grid(alpha=0.25)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    for ax in axes[len(panels):]:
        ax.axis("off")
    for ax in axes[::cols]:
        ax.set_ylabel("ROC-AUC")
    for ax in axes[-cols:]:
        ax.set_xlabel("round")
    handles, labels = axes[0].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=len(labels), fontsize=9, frameon=False)
    fig.suptitle(f"Federated learning, {runs[0]['phones']} simulated phones, FedAvg: accuracy after each round",
                 fontsize=12, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0.05, 1, 1))
    out = RESULTS / "rounds.png"
    fig.savefig(out, dpi=150)
    print(f"wrote {out}")
    last = pd.DataFrame(rows).query("round == round.max()")
    print(last[["run"] + [c for c in last.columns if c.endswith("roc_auc")]].to_string(index=False))


if __name__ == "__main__":
    main()
