#!/bin/sh
set -eu

DATA_DIR="${NM_DATA_DIR:-/data}"
mkdir -p "$DATA_DIR/uploads" "$DATA_DIR/plugins" "$DATA_DIR/knowledge"

echo "[nekomate] data dir: $DATA_DIR"
echo "[nekomate] starting uvicorn on ${NM_HOST:-0.0.0.0}:${NM_PORT:-${PORT:-8080}}"

exec uvicorn app.main:app \
  --host "${NM_HOST:-0.0.0.0}" \
  --port "${NM_PORT:-${PORT:-8080}}" \
  --proxy-headers \
  --forwarded-allow-ips="*" \
  --workers 1 \
  --no-access-log \
  --no-server-header
