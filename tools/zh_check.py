"""
zh_check.py: type one Chinese sentence, get urgency + money + impersonation at once.

It sends the text to the three RUNNING worker pods over gRPC (the real servers),
so what you see is exactly what the pipeline gets.

Run it with tools/zh_check.sh (starts the 3 containers for you), or by hand:
    python zh_check.py                       interactive, type 'q' to quit
    python zh_check.py "请把5000新币转到这个安全账户"

Worker addresses (defaults = container names on the docker network):
    ZH_URGENCY=urgency_zh:50051  ZH_MONEY=money_zh:50051  ZH_IMPERSONATION=impersonation_zh:50051
"""
import os
import sys

import grpc
import scam_detect_pb2 as pb
import scam_detect_pb2_grpc as pbg

WORKERS = {
    "urgency": os.environ.get("ZH_URGENCY", "urgency_zh:50051"),
    "money": os.environ.get("ZH_MONEY", "money_zh:50051"),
    "impersonation": os.environ.get("ZH_IMPERSONATION", "impersonation_zh:50051"),
}
LABEL = {"urgency": "Urgency", "money": "Money", "impersonation": "Impersonation"}
MEANING = {"urgency": "pressure / deadline / threat / secrecy",
           "money": "asks to pay, transfer or give bank details / OTP",
           "impersonation": "claims to be police, govt, bank, platform, courier, telco"}
YES, MAYBE = 0.50, 0.25          # display bands (the aggregator itself uses the raw scores)
WEIGHTS = {"urgency": 1 / 3, "money": 1 / 3, "impersonation": 1 / 3}   # <- team's real w1..w3

# colours (turned off automatically when output is not a terminal)
C = sys.stdout.isatty()
def col(s, code): return f"\033[{code}m{s}\033[0m" if C else s
GREEN, YELLOW, RED, DIM, BOLD = "32", "33", "31", "2", "1"

# the request message name comes from the shared proto, so read it instead of guessing
_REQ = getattr(pb, pb.DESCRIPTOR.services_by_name["WorkerService"].methods_by_name["ClassifyTranscript"].input_type.name)
STUBS = {w: pbg.WorkerServiceStub(grpc.insecure_channel(addr)) for w, addr in WORKERS.items()}


def ask(worker, text):
    try:
        return STUBS[worker].ClassifyTranscript(_REQ(transcript=text), timeout=10).score
    except grpc.RpcError as e:
        return f"{worker} worker not reachable at {WORKERS[worker]} ({e.code().name})"


def bar(p, width=20):
    n = round(p * width)
    return "█" * n + col("░" * (width - n), DIM)


def check(text):
    scores = {w: ask(w, text) for w in WORKERS}
    errors = [s for s in scores.values() if isinstance(s, str)]
    print()
    print(col("  Text: ", BOLD) + text)
    print("  " + "─" * 66)
    if errors:
        for e in errors:
            print(col("  ✖ " + e, RED))
        print(col("    start the workers first:  ./tools/zh_check.sh", DIM))
        return
    found = []
    for w, p in scores.items():
        if p >= YES:
            tag, c = "YES  ", RED; found.append(LABEL[w])
        elif p >= MAYBE:
            tag, c = "maybe", YELLOW
        else:
            tag, c = "no   ", GREEN
        print(f"  {LABEL[w]:<14} {bar(p)} {p:5.2f}  {col(tag, c)}  {col(MEANING[w], DIM)}")
    print("  " + "─" * 66)
    combined = sum(WEIGHTS[w] * p for w, p in scores.items())
    verdict = "SCAM" if combined >= 0.6 else ("UNSURE" if combined >= 0.45 else "OK")
    vc = {"SCAM": RED, "UNSURE": YELLOW, "OK": GREEN}[verdict]
    print("  " + col("Signals found: ", BOLD) + (col(" + ".join(found), RED) if found else col("none", GREEN)))
    print("  " + col("Aggregator:    ", BOLD) + f"{combined:.2f} → " + col(verdict, vc)
          + col("   (equal weights, SCAM ≥ 0.60, UNSURE ≥ 0.45)", DIM))


def main():
    if len(sys.argv) > 1:
        check(" ".join(sys.argv[1:]))
        return
    print(col("\n  Chinese scam-signal checker", BOLD) + col("  (urgency_zh · money_zh · impersonation_zh)", DIM))
    print(col("  Type a Chinese sentence and press Enter. 'q' to quit.", DIM))
    while True:
        try:
            text = input(col("\n> ", BOLD)).strip()
        except UnicodeDecodeError:
            print(col("  could not read that input; run ./tools/zh_check.sh instead (it reads input outside docker)", RED))
            continue
        except (EOFError, KeyboardInterrupt):
            print()
            break
        if text.lower() in {"q", "quit", "exit"}:
            break
        if text:
            check(text)


if __name__ == "__main__":
    main()
