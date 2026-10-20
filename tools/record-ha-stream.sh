#!/usr/bin/env bash
# Record the SSE bodies the Ollama relay tests replay.
#
# The fixtures under app/app/src/test/resources/ollama/ are real captures from
# llama.cpp at the revision the app pins, so a change in the engine's streaming
# format shows up as a failing test rather than as a broken Home Assistant
# answer. Re-run this after moving the submodule and commit the new files.
#
#   BACKEND=http://127.0.0.1:18099 ./tools/record-ha-stream.sh
#
# The backend must already be running (any model; the request shape is what
# matters, not the words).
set -euo pipefail

BACKEND="${BACKEND:-http://127.0.0.1:18099}"
OUT="$(cd "$(dirname "$0")/.." && pwd)/app/app/src/test/resources/ollama"
mkdir -p "$OUT"

curl -sN --max-time 300 "$BACKEND/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "record",
    "messages": [
      {"role": "system", "content": "Answer in one short sentence."},
      {"role": "user", "content": "Name three colours."}
    ],
    "stream": true,
    "stream_options": {"include_usage": true},
    "temperature": 0.2,
    "max_tokens": 120
  }' > "$OUT/chat-stream.sse"

curl -sN --max-time 300 "$BACKEND/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "record",
    "messages": [
      {"role": "system", "content": "You control Home Assistant. Use tools when asked about devices."},
      {"role": "user", "content": "Turn on the kitchen light."}
    ],
    "tools": [{
      "type": "function",
      "function": {
        "name": "HassTurnOn",
        "description": "Turns on a device or entity",
        "parameters": {
          "type": "object",
          "properties": {"name": {"type": "string"}},
          "required": ["name"]
        }
      }
    }],
    "stream": true,
    "stream_options": {"include_usage": true},
    "temperature": 0,
    "max_tokens": 200
  }' > "$OUT/chat-toolcall.sse"

echo "recorded into $OUT:"
wc -l "$OUT/chat-stream.sse" "$OUT/chat-toolcall.sse"
