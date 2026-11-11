"""plot_rounds.py - Workstream G.7 / G.8: accuracy per round, with and without noise, for the report.

Reads every backend/federated/results/*.json (one per run of server.py) and draws ROC-AUC on the two fixed
human-labelled test sets after each round, one line per noise level, with the centrally trained hashing model
(G.1) as a dashed reference line. Also writes the numbers as results/rounds_table.csv.

  python backend/federated/plot_rounds.py   ->  backend/federated/results/rounds.png
"""
import json
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt  # noqa: E402
import pandas as pd  # noqa: E402

RESULTS = Path(__file__).resolve().parent / "results"
SETS = [("gold_handwritten", "Handwritten gold set (59 lines)"), ("robocall_gold", "Robocall set (181 lines)")]
COLORS = ["#1f6feb", "#d97706", "#b42318", "#6b21a8", "#047857"]


def main():
    # Only runs that start from an empty model: they show how fast FedAvg learns (runs from the current model
    # are the weekly-update case, reported by their release check instead).
    runs = [json.loads(p.read_text()) for p in RESULTS.glob("fedavg_*.json")]
    runs = sorted((r for r in runs if r.get("init", "zeros") == "zeros"), key=lambda r: r["noise_multiplier"])
    if not runs:
        raise SystemExit(f"no results in {RESULTS}: run run_local.py first")
    rows = [{"run": r["run"], "noise": r["noise_multiplier"], "clip": r["clipping_norm"], **h}
            for r in runs for h in r["history"]]
    pd.DataFrame(rows).to_csv(RESULTS / "rounds_table.csv", index=False)
    central = {g["test_set"]: g["roc_auc"] for g in runs[0]["central_gold"] if g["model"].startswith("hash")}

    fig, axes = plt.subplots(1, 2, figsize=(11, 4.2), sharey=True)
    for ax, (key, title) in zip(axes, SETS):
        for color, r in zip(COLORS, runs):
            xs = [h["round"] for h in r["history"]]
            ys = [h[f"{key}_roc_auc"] for h in r["history"]]
            name = "no noise" if r["noise_multiplier"] == 0 else f"noise {r['noise_multiplier']:g} (clip {r['clipping_norm']:g})"
            ax.plot(xs, ys, marker="o", ms=3.5, lw=2, color=color, label=name)
        if key in central:
            ax.axhline(central[key], color="#444", ls="--", lw=1.2, label=f"central model (G.1) {central[key]:.3f}")
        ax.set_title(title, fontsize=11, loc="left")
        ax.set_xlabel("round")
        ax.set_xticks(range(0, max(r["rounds"] for r in runs) + 1))
        ax.set_ylim(0.4, 1.0)
        ax.grid(alpha=0.25)
        for side in ("top", "right"):
            ax.spines[side].set_visible(False)
    axes[0].set_ylabel("ROC-AUC (urgency)")
    handles, labels = axes[1].get_legend_handles_labels()
    fig.legend(handles, labels, loc="lower center", ncol=len(labels), fontsize=9, frameon=False)
    phones = runs[0]["phones"]
    fig.suptitle(f"Federated urgency model: {phones} simulated phones, FedAvg, accuracy after each round",
                 fontsize=12, x=0.01, ha="left")
    fig.tight_layout(rect=(0, 0.07, 1, 1))
    out = RESULTS / "rounds.png"
    fig.savefig(out, dpi=160)
    print(f"wrote {out}")
    print(pd.DataFrame(rows).query("round == round.max()").to_string(index=False))


if __name__ == "__main__":
    main()
