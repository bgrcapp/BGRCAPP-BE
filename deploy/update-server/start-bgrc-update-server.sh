#!/usr/bin/env sh
set -eu

ROOT="$HOME/bgrc-updates"
PID_FILE="$ROOT/bgrc-update-server.pid"
PROCESS_ARGUMENT="$ROOT/bgrc_update_server.py"
LOG_FILE="$ROOT/update-server.log"

if [ -r "$PID_FILE" ]; then
    PID=$(tr -cd '0-9' < "$PID_FILE")
    if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null \
        && ps -p "$PID" -o args= 2>/dev/null | grep -Fq "$PROCESS_ARGUMENT"; then
        exit 0
    fi
fi

mkdir -p "$ROOT/public/stable" "$ROOT/public/releases"
nohup /usr/bin/python3 "$PROCESS_ARGUMENT" --root "$ROOT/public" --bind 127.0.0.1 --port 18080 \
    >> "$LOG_FILE" 2>&1 &
echo "$!" > "$PID_FILE"
