/**
 * elevenlabs.js — ElevenLabs TTS provider module (M3).
 *
 * Implements: synthesizeSpeech, testConnection.
 *
 * ElevenLabs is an alternate TTS provider. When an admin switches the `tts`
 * capability to ElevenLabs (pastes a key + sets defaultModel to a voice id),
 * router.synthesizeSpeech dispatches here. The module returns MP3 base64
 * (invariant §13.3 #1: Android plays audioContent inline as base64 MP3), so
 * the response shape matches google_tts exactly.
 *
 * No Firebase dependency — this module runs purely on a passed config + global
 * fetch, so it can also be required by the admin's Test-connection route.
 */

const { normalizeTts } = require("./normalize");

const DEFAULT_BASE = "https://api.elevenlabs.io";
const DEFAULT_VOICE_ID = "21m00Tcm4TlvDq8ikWAM"; // "Rachel"

function resolveBase(baseUrl) {
  return (baseUrl || DEFAULT_BASE).replace(/\/$/, "");
}

// ─── synthesizeSpeech ───────────────────────────────────────
// `model` carries the ElevenLabs voice id (the seeded provider's defaultModel).
// `voice` is honored if passed, else `model`, else a safe default.
async function synthesizeSpeech(args) {
  const { text, voice, model, apiKey, targetLanguage, providerId } = args;
  if (!apiKey) throw new Error("ElevenLabs API key not configured");

  const voiceId = model || voice || DEFAULT_VOICE_ID;
  const base = resolveBase(args.baseUrl);

  const t0 = Date.now();
  const response = await fetch(
    `${base}/v1/text-to-speech/${voiceId}?output_format=mp3_44100_128`,
    {
      method: "POST",
      headers: { "xi-api-key": apiKey, "Content-Type": "application/json" },
      body: JSON.stringify({ text, model_id: "eleven_multilingual_v2" }),
    },
  );

  if (!response.ok) {
    const err = await response.text();
    console.error("ElevenLabs TTS error:", response.status, err);
    throw new Error(`ElevenLabs TTS error: ${response.status}`);
  }

  const buf = Buffer.from(await response.arrayBuffer());
  const audioContent = buf.toString("base64");
  if (!audioContent) throw new Error("No audio content from ElevenLabs");

  return normalizeTts({
    audioContent,
    mimeType: "audio/mpeg",
    voice: voiceId,
    model: voiceId,
    latencyMs: Date.now() - t0,
    providerId,
  });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, baseUrl, capability = "tts" } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const base = resolveBase(baseUrl);
  const t0 = Date.now();
  try {
    const res = await fetch(`${base}/v1/voices`, { headers: { "xi-api-key": apiKey } });
    const latencyMs = Date.now() - t0;
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    return { ok: true, capability, latencyMs, detail: "Connected to ElevenLabs" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = { synthesizeSpeech, testConnection };
