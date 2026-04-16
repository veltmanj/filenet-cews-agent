#!/usr/bin/env sh
set -eu

if [ "$#" -lt 3 ] || [ "$#" -gt 4 ]; then
  echo "Usage: $0 <cell> <node> <server> [agent_dir]" >&2
  exit 1
fi

CELL="$1"
NODE="$2"
SERVER="$3"
AGENT_DIR="${4:-/opt/filenet-cews-agent}"
AGENT_JAR="$AGENT_DIR/filenet-cews-agent-0.1.4.jar"
OUTPUT_FILE="$AGENT_DIR/cews-capture.ndjson"
AGENT_SPEC="-javaagent:${AGENT_JAR}=profile=filenet-cews-low-overhead,output=${OUTPUT_FILE}"
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WSADMIN_CMD="${WSADMIN_CMD:-wsadmin.sh}"

${WSADMIN_CMD} ${WSADMIN_ARGS:-} -lang jython -f "$SCRIPT_DIR/configure-javaagent.py" add "$CELL" "$NODE" "$SERVER" "$AGENT_SPEC"