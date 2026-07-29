#!/usr/bin/env bash
#
# verify-openrouter-stt.sh
# -------------------------
# GATE for the OpenRouter STT provider (M5 follow-up).
#
# Goal: prove that OpenRouter actually forwards AUDIO to the model
#   nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free
# BEFORE we write functions/providers/openrouter.js.
#
# If the audio path is accepted, we build the module. If OpenRouter
# rejects audio for this model, we ABORT and report why (matching the
# M5 plan: "verify via curl ... before building openrouter.js").
#
# Usage:
#   export OPENROUTER_API_KEY="sk-or-..."
#   ./verify-openrouter-stt.sh <audio-file.wav|mp3|m4a|webm>
#
# A short SPOKEN clip works best. A tone / notification sound only
# proves the audio *path* is accepted (transcript will be empty).
#
set -u

MODEL="${OPENROUTER_MODEL:-nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free}"
API="https://openrouter.ai/api/v1/chat/completions"

if [ -z "${OPENROUTER_API_KEY:-}" ]; then
  echo "ERROR: set OPENROUTER_API_KEY first (it is read from your shell env, never logged):" >&2
  echo '  export OPENROUTER_API_KEY="sk-or-..."' >&2
  exit 1
fi

AUDIO_FILE="${1:-}"
if [ -z "$AUDIO_FILE" ] || [ ! -f "$AUDIO_FILE" ]; then
  echo "Usage: $0 <audio-file.wav|mp3|m4a|webm>" >&2
  echo "  (a short spoken clip works best; a tone only tests audio-path acceptance)" >&2
  exit 1
fi

# Map file extension -> OpenAI/OpenRouter audio "format" hint.
case "$AUDIO_FILE" in
  *.wav|*.WAV)           FMT=wav ;;
  *.mp3|*.MP3)           FMT=mp3 ;;
  *.m4a|*.M4A|*.aac)     FMT=mp4 ;;  # OR/OpenAI name the AAC container "mp4"
  *.webm|*.WEBM)         FMT=webm ;;
  *.ogg|*.OGG|*.opus)    FMT=ogg ;;
  *)                      FMT=wav ;;
esac

B64=$(base64 "$AUDIO_FILE" | tr -d '\n')

# Opt-in for TLS-intercepting proxies (e.g. NODE_TLS_REJECT_UNAUTHORIZED set).
# Set OR_INSECURE=1 only against a known API you trust.
CURL_EXTRA=""
if [ -n "${OR_INSECURE:-}" ]; then CURL_EXTRA="-k"; fi
PROMPT="Transcribe the audio clip verbatim. If there is no speech, reply with the single token [inaudible]."

# Build the OpenAI-compatible audio-input payload (input_audio is the
# current OpenAI/OpenRouter audio-input schema). No jq dependency.
printf '{"model":"%s","messages":[{"role":"user","content":[{"type":"text","text":"%s"},{"type":"input_audio","input_audio":{"data":"%s","format":"%s"}}]}]}' \
  "$MODEL" "$PROMPT" "$B64" "$FMT" > /tmp/or_payload.json

BYTES=$(wc -c < "$AUDIO_FILE" | tr -d ' ')
echo "==> POST $API"
echo "==> model : $MODEL"
echo "==> audio : $AUDIO_FILE  (format=$FMT, $BYTES bytes)"

curl -sS $CURL_EXTRA -w "\nHTTP_STATUS:%{http_code}\n" "$API" \
  -H "Authorization: Bearer $OPENROUTER_API_KEY" \
  -H "Content-Type: application/json" \
  --data @/tmp/or_payload.json

echo
echo "==> INTERPRETATION"
echo "   200 + a transcript            => AUDIO PATH WORKS  -> proceed to build openrouter.js"
echo "   200 + [inaudible]/empty      => model got the audio but returned no speech (note it)"
echo "   400 w/ 'input_audio'/'audio' => schema wrong; try audio_url data-URI variant"
echo "   400 w/ 'modality'/'unsupported'/'audio' => audio NOT supported for this model -> ABORT"
echo "   401 => key wrong | 429 => quota/rate | 404 => model id wrong | 5xx => retry"
