#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CHECK_DATA="$SCRIPT_DIR/data/check-1k.txt"

export JAVA_HOME="${JAVA_HOME:-$(dirname $(dirname $(readlink -f $(which javac) 2>/dev/null || echo /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin/javac)))}"
export PATH="$JAVA_HOME/bin:$PATH"

# Build C binary
make -C "$SCRIPT_DIR" -s streaming_aggregator

# Build Java validator if needed
mkdir -p "$SCRIPT_DIR/out"
javac -d "$SCRIPT_DIR/out" "$SCRIPT_DIR"/src/*.java 2>&1 | grep -v "^Note:" || true

# Generate check data if needed
if [[ ! -f "$CHECK_DATA" ]]; then
    java -cp "$SCRIPT_DIR/out" DataGenerator 1000 "$CHECK_DATA"
fi

"$SCRIPT_DIR/streaming_aggregator" "$CHECK_DATA" | sed 's/,updated$/,new/' | sort > /tmp/check-c.txt
java -cp "$SCRIPT_DIR/out" BatchValidator "$CHECK_DATA" | sort > /tmp/check-batch.txt

if diff -q /tmp/check-c.txt /tmp/check-batch.txt > /dev/null 2>&1; then
    echo "CHECKS PASSED" >&2
else
    echo "CHECKS FAILED: output mismatch" >&2
    diff /tmp/check-c.txt /tmp/check-batch.txt | head -20 >&2
    exit 1
fi
