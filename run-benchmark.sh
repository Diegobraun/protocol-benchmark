#!/usr/bin/env bash
set -euo pipefail

HTTP_PORT="${HTTP_PORT:-8080}"
GRPC_PORT="${GRPC_PORT:-9090}"
if [[ -z "${JAVA_OPTS+x}" ]]; then
  JAVA_MAJOR="$(java -XshowSettings:properties -version 2>&1 | awk -F'= ' '/java.specification.version/ {print $2}')"
  JAVA_OPTS=""
  if (( ${JAVA_MAJOR%%.*} >= 23 )); then
    JAVA_OPTS="--sun-misc-unsafe-memory-access=allow"
  fi
fi

cd "$(dirname "$0")"

if [[ ! -f server/target/server-1.0.0-exec.jar || ! -f bench/target/bench.jar ]]; then
  ./mvnw -B -q install -DskipTests
fi

java $JAVA_OPTS -jar server/target/server-1.0.0-exec.jar \
  --server.port="$HTTP_PORT" --grpc.port="$GRPC_PORT" > server.log 2>&1 &
SERVER_PID=$!
trap 'kill $SERVER_PID 2>/dev/null || true' EXIT

for _ in $(seq 1 60); do
  if nc -z localhost "$HTTP_PORT" 2>/dev/null && nc -z localhost "$GRPC_PORT" 2>/dev/null; then
    break
  fi
  sleep 0.5
done

java $JAVA_OPTS -jar bench/target/bench.jar --http-port="$HTTP_PORT" --grpc-port="$GRPC_PORT" "$@"
