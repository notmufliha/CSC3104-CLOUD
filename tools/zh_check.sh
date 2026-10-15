#!/usr/bin/env bash
# Chinese scam-signal checker: starts the 3 _zh worker containers and opens the checker.
#
#   ./tools/zh_check.sh --build          build the 3 images (first time, or after code/data changes)
#   ./tools/zh_check.sh                  interactive: type Chinese, press Enter, 'q' to quit
#   ./tools/zh_check.sh "请把钱转到安全账户"   check one sentence
#
# Run from anywhere inside the repo. Needs Docker Desktop running.
set -e
cd "$(dirname "$0")/.."                      # repo root
WORKERS="urgency_zh money_zh impersonation_zh"
NET=zhcheck

if [ "$1" = "--build" ]; then
  shift
  for w in $WORKERS; do
    echo "building $w (about 1 min)..."
    docker build -q -f backend/workers/$w/Dockerfile -t $w backend >/dev/null
  done
  echo "done."
fi

for w in $WORKERS; do
  docker image inspect $w >/dev/null 2>&1 || { echo "image '$w' not found. Run: ./tools/zh_check.sh --build"; exit 1; }
done

docker network inspect $NET >/dev/null 2>&1 || docker network create $NET >/dev/null
cleanup() { for w in $WORKERS; do docker rm -f zhcheck_$w >/dev/null 2>&1 || true; done; }
trap cleanup EXIT
cleanup

echo -n "starting workers"
for w in $WORKERS; do
  docker run -d --name zhcheck_$w --network $NET $w >/dev/null
done
for w in $WORKERS; do                          # wait until each server says it is listening
  for i in $(seq 1 60); do
    docker logs zhcheck_$w 2>&1 | grep -q "listening" && break
    if ! docker ps -q -f name=zhcheck_$w | grep -q .; then echo; echo "zhcheck_$w crashed:"; docker logs zhcheck_$w; exit 1; fi
    echo -n "."; sleep 0.5
  done
done
echo " ready."

# checker container (has grpc + the generated stubs); typing happens in THIS terminal, not inside docker,
# so Chinese input from the Mac keyboard / IME is read correctly
docker rm -f zhcheck_client >/dev/null 2>&1 || true
docker run -d --name zhcheck_client --network $NET \
  -e ZH_URGENCY=zhcheck_urgency_zh:50051 -e ZH_MONEY=zhcheck_money_zh:50051 -e ZH_IMPERSONATION=zhcheck_impersonation_zh:50051 \
  -e PYTHONIOENCODING=utf-8 -e LANG=C.UTF-8 \
  -v "$PWD/tools/zh_check.py:/app/zh_check.py:ro" \
  money_zh sleep infinity >/dev/null
trap 'cleanup; docker rm -f zhcheck_client >/dev/null 2>&1 || true' EXIT

T=""; [ -t 1 ] && T="-t"                       # colours when printing to a terminal
check() { docker exec $T zhcheck_client python zh_check.py "$1"; }

if [ $# -gt 0 ]; then check "$*"; exit 0; fi

printf "\n  \033[1mChinese scam-signal checker\033[0m  (urgency_zh · money_zh · impersonation_zh)\n"
printf "  Type a Chinese sentence and press Enter. 'q' to quit.\n"
while IFS= read -r -e -p $'\n> ' line; do
  case "$line" in q|quit|exit) break ;; "") continue ;; esac
  check "$line"
done
echo "stopping workers..."
