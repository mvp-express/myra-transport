#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

TS="$(date -u +%Y%m%dT%H%M%SZ)"
OUT_DIR="$ROOT/benchmarks/build/results/jmh"
LOG_DIR="$ROOT/benchmarks/build/logs"
mkdir -p "$OUT_DIR" "$LOG_DIR"

LOG_FILE="$LOG_DIR/standard-pingpong-1m-5m-$TS.log"
RESULT_NETTY_NIO="$OUT_DIR/standard-pingpong-1m-5m-$TS-nio-netty.json"
RESULT_MYRA="$OUT_DIR/standard-pingpong-1m-5m-$TS-myra.json"

LOG_FILE_RW="$LOG_DIR/standard-realworld-1m-5m-$TS.log"
RESULT_RW_NETTY_NIO="$OUT_DIR/standard-realworld-1m-5m-$TS-nio-netty.json"
RESULT_RW_MYRA="$OUT_DIR/standard-realworld-1m-5m-$TS-myra.json"

JAR="$ROOT/benchmarks/build/libs/benchmarks-0.2.0-jmh.jar"

kill_running_jmh() {
  # Be conservative: only kill JVMs that are running our JMH jar.
  local pids
  pids=$(pgrep -f "${JAR//\//\\/}" || true)
  if [[ -z "$pids" ]]; then
    return 0
  fi

  echo "[$(date -u +%F\ %T)] Found running JMH processes for this jar: $pids"
  echo "[$(date -u +%F\ %T)] Sending SIGTERM..."
  kill $pids 2>/dev/null || true
  sleep 2

  # Anything still alive: SIGKILL.
  local still
  still=$(pgrep -f "${JAR//\//\\/}" || true)
  if [[ -n "$still" ]]; then
    echo "[$(date -u +%F\ %T)] Still running after SIGTERM, sending SIGKILL: $still"
    kill -9 $still 2>/dev/null || true
  fi
}

kill_running_jmh

LOCK_FILE="/tmp/jmh.lock"
if [[ -f "$LOCK_FILE" ]]; then
  echo "[$(date -u +%F\ %T)] Removing stale JMH lock: $LOCK_FILE"
  rm -f "$LOCK_FILE" || true
fi

{
  echo "[$(date -u +%F\ %T)] Building JMH jar..."
  ./gradlew -q :benchmarks:jmhJar

  echo "[$(date -u +%F\ %T)] Running PingPongBenchmark: NIO + NETTY (bufferMode fixed to STANDARD)"
  java -Djmh.ignoreLock=true --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" \
    ".*PingPongBenchmark.*" \
    -bm sample -tu us -f 1 \
    -wi 2 -w 60s \
    -i 5 -r 300s \
    -rf json -rff "$RESULT_NETTY_NIO" \
    -p implementation=NIO,NETTY \
    -p bufferMode=STANDARD \
    -p pinning=true \
    -p serverCore=0 -p clientCore=1 \
    -p serverSqPollCore=2 -p clientSqPollCore=3 \
    -p clientPollerCore=3

  echo "[$(date -u +%F\ %T)] Running PingPongBenchmark: MYRA + MYRA_SQPOLL + MYRA_TOKEN (all buffer modes)"
  java -Djmh.ignoreLock=true --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" \
    ".*PingPongBenchmark.*" \
    -bm sample -tu us -f 1 \
    -wi 2 -w 60s \
    -i 5 -r 300s \
    -rf json -rff "$RESULT_MYRA" \
    -p implementation=MYRA,MYRA_SQPOLL,MYRA_TOKEN \
    -p bufferMode=STANDARD,FIXED,BUFFER_RING,ZERO_COPY \
    -p pinning=true \
    -p serverCore=0 -p clientCore=1 \
    -p serverSqPollCore=2 -p clientSqPollCore=3 \
    -p clientPollerCore=3

  echo "[$(date -u +%F\ %T)] Running RealWorldPayloadBenchmark: NIO + NETTY (bufferMode fixed to STANDARD)"
  java -Djmh.ignoreLock=true --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" \
    ".*RealWorldPayloadBenchmark.*" \
    -bm sample -tu us -f 1 \
    -wi 2 -w 60s \
    -i 5 -r 300s \
    -rf json -rff "$RESULT_RW_NETTY_NIO" \
    -p implementation=NIO,NETTY \
    -p bufferMode=STANDARD \
    -p pinning=true \
    -p serverCore=0 -p clientCore=1 \
    -p serverSqPollCore=2 -p clientSqPollCore=3 \
    -p clientPollerCore=3

  echo "[$(date -u +%F\ %T)] Running RealWorldPayloadBenchmark: MYRA + MYRA_SQPOLL + MYRA_TOKEN (all buffer modes)"
  java -Djmh.ignoreLock=true --enable-native-access=ALL-UNNAMED \
    -jar "$JAR" \
    ".*RealWorldPayloadBenchmark.*" \
    -bm sample -tu us -f 1 \
    -wi 2 -w 60s \
    -i 5 -r 300s \
    -rf json -rff "$RESULT_RW_MYRA" \
    -p implementation=MYRA,MYRA_SQPOLL,MYRA_TOKEN \
    -p bufferMode=STANDARD,FIXED,BUFFER_RING,ZERO_COPY \
    -p pinning=true \
    -p serverCore=0 -p clientCore=1 \
    -p serverSqPollCore=2 -p clientSqPollCore=3 \
    -p clientPollerCore=3

  echo "[$(date -u +%F\ %T)] Done. Results:"
  echo "  $RESULT_NETTY_NIO"
  echo "  $RESULT_MYRA"
  echo "  $RESULT_RW_NETTY_NIO"
  echo "  $RESULT_RW_MYRA"
} 2>&1 | tee "$LOG_FILE"
