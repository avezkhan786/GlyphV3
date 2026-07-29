/**
 * normalize.js — Builds normalized response objects for every provider capability.
 *
 * Every provider module (gemini, google_tts, and the M3/M4 ones) returns results in
 * slightly different shapes. These helpers coerce them into the canonical contract
 * used by the router and the Cloud Functions, and ALWAYS include `latencyMs` so the
 * caller can report timings.
 *
 * Canonical shapes (see AI_PROVIDER_HANDOFF.md §8):
 *   generateText  → { text, model, latencyMs, providerId? }
 *   translate     → { translatedText, model, latencyMs, providerId? }
 *   speechToText  → { text, model, latencyMs, providerId? }
 *   synthesizeSpeech → { audioContent, mimeType, voice, model, latencyMs, providerId? }
 */

function num(v) {
  return typeof v === "number" && !isNaN(v) ? v : 0;
}

function normalizeGenerateText(result) {
  const r = result || {};
  return {
    text: r.text != null ? String(r.text) : "",
    model: r.model || null,
    latencyMs: num(r.latencyMs),
    providerId: r.providerId || null,
  };
}

function normalizeTranslate(result) {
  const r = result || {};
  return {
    translatedText: r.translatedText != null ? String(r.translatedText) : "",
    model: r.model || null,
    latencyMs: num(r.latencyMs),
    providerId: r.providerId || null,
  };
}

function normalizeSpeech(result) {
  const r = result || {};
  return {
    text: r.text != null ? String(r.text) : "",
    model: r.model || null,
    latencyMs: num(r.latencyMs),
    providerId: r.providerId || null,
  };
}

function normalizeTts(result) {
  const r = result || {};
  return {
    audioContent: r.audioContent != null ? String(r.audioContent) : null,
    mimeType: r.mimeType || "audio/mpeg",
    voice: r.voice || null,
    model: r.model || null,
    latencyMs: num(r.latencyMs),
    providerId: r.providerId || null,
  };
}

module.exports = {
  normalizeGenerateText,
  normalizeTranslate,
  normalizeSpeech,
  normalizeTts,
  num,
};
