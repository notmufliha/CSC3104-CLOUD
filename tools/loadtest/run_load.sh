#!/usr/bin/env bash
# F.4 + F.5: same load per mode, with latency from k6 and CPU/memory from docker stats.
# From the repo root, stack running: bash tools/loadtest/run_load.sh [rate] [duration]
set -euo pipefail
RATE="${1:-20}"; DURATION="${2:-60s}"
OUT="data/eval/load"; HERE="tools/loadtest"
mkdir -p "$OUT"
command -v k6 >/dev/null || { echo "k6 missing: brew install k6"; exit 1; }
python3 "$HERE/build_lines.py" android-app/app/src/main/assets/scripts "$OUT/lines.json"

for MODE in fast bert_only cascade; do
  echo "=== $MODE at $RATE lines/s for $DURATION ==="
  AGG_MODE="$MODE" docker compose up -d aggregator 2>/dev/null
  sleep 8
  # wake the lazy BERT connection, so the first line of the run isn't a one-off timeout
  for i in 1 2 3; do
    curl -s -o /dev/null -X POST localhost:8080/v1/utterance -H 'Content-Type: application/json' \
      -d "{\"call_id\":\"warm-$MODE-$$\",\"seq\":$i,\"speaker\":\"caller\",\"lang\":\"en\",\"text\":\"Hello, this is a test line.\",\"t_ms\":20000}"
  done
  sleep 2
  STATS="$OUT/stats_${MODE}_$RATE.csv"; : > "$STATS"
  ( while true; do
      docker stats --no-stream --format '{{.Name}},{{.CPUPerc}},{{.MemUsage}}' >> "$STATS" || true
    done ) &
  SAMPLER=$!
  k6 run --quiet --no-usage-report -e MODE="$MODE" -e RATE="$RATE" -e DURATION="$DURATION" -e RUN="$MODE-$$" \
    -e LINES="$PWD/$OUT/lines.json" -e OUT="$PWD/$OUT/k6_${MODE}_$RATE.json" "$HERE/load_test.js"
  kill "$SAMPLER" 2>/dev/null || true
  wait "$SAMPLER" 2>/dev/null || true
done

AGG_MODE=cascade docker compose up -d aggregator 2>/dev/null
python3 "$HERE/summarize_load.py" "$OUT" "$RATE" | tee "$OUT/report_load_$RATE.md"
echo ALL_DONE
