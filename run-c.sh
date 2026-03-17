#!/bin/bash
set -euo pipefail

SCALE="${1:-10m}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DATA_DIR="$SCRIPT_DIR/data"
DATA="$DATA_DIR/measurements-${SCALE}.txt"

# Build C binary
make -C "$SCRIPT_DIR" -s streaming_aggregator

# Generate data if missing (requires Java)
if [[ ! -f "$DATA" ]]; then
    echo "Generating $SCALE dataset (requires Java)..." >&2
    export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which javac))))}"
    export PATH="$JAVA_HOME/bin:$PATH"
    mkdir -p "$SCRIPT_DIR/out"
    javac -d "$SCRIPT_DIR/out" "$SCRIPT_DIR"/src/*.java 2>/dev/null
    java -cp "$SCRIPT_DIR/out" DataGenerator "$SCALE" "$DATA"
fi

ROWS=$(wc -l < "$DATA" | tr -d ' ')

START_NS=$(date +%s%N)
"$SCRIPT_DIR/streaming_aggregator" "$DATA" > /tmp/autoresearch-streaming-output-c.txt
END_NS=$(date +%s%N)

ELAPSED_MS=$(( (END_NS - START_NS) / 1000000 ))
THROUGHPUT=$(echo "scale=0; $ROWS * 1000 / $ELAPSED_MS" | bc)

echo "METRIC throughput=$THROUGHPUT"
echo "METRIC elapsed_ms=$ELAPSED_MS"
echo "METRIC rows=$ROWS"
