/**
 * google_tts.js — Google Cloud Text-to-Speech provider module (M2: Google parity).
 *
 * Implements: synthesizeSpeech.
 *
 * Behavior must be IDENTICAL to the inline Google Cloud TTS call previously in
 * translate.js: same voice-per-language map (ttsLangMap), same default fallback
 * (en-US / en-US-Standard-C), same MP3 request, same base64-MP3 output
 * (invariant §13.3 #1, #6).
 *
 * Never reads functions.config() at module load (invariant §13.3 #9) — only lazily
 * inside resolveKey(), and the firebase-functions require itself is deferred so this
 * module can also be loaded by the admin's Test-connection route without that dep.
 */

const { normalizeTts } = require("./normalize");

const TTS_URL = "https://texttospeech.googleapis.com/v1/text:synthesize";

// Map language code → BCP-47 languageCode + voice name for Cloud TTS.
// Copied VERBATIM from translate.js (lines 402–431). Android depends on the
// resulting audio, so do not alter these mappings.
const ttsLangMap = {
  ur: { languageCode: "ur-PK", name: "ur-PK-Standard-A" },
  ar: { languageCode: "ar-XA", name: "ar-XA-Standard-A" },
  hi: { languageCode: "hi-IN", name: "hi-IN-Standard-A" },
  "hi-Latn": { languageCode: "hi-IN", name: "hi-IN-Standard-A" }, // Hinglish reads as Hindi
  zh: { languageCode: "cmn-CN", name: "cmn-CN-Standard-A" },
  "zh-TW": { languageCode: "cmn-TW", name: "cmn-TW-Standard-A" },
  ja: { languageCode: "ja-JP", name: "ja-JP-Standard-A" },
  ko: { languageCode: "ko-KR", name: "ko-KR-Standard-A" },
  fr: { languageCode: "fr-FR", name: "fr-FR-Standard-A" },
  de: { languageCode: "de-DE", name: "de-DE-Standard-A" },
  es: { languageCode: "es-ES", name: "es-ES-Standard-A" },
  pt: { languageCode: "pt-BR", name: "pt-BR-Standard-A" },
  it: { languageCode: "it-IT", name: "it-IT-Standard-A" },
  ru: { languageCode: "ru-RU", name: "ru-RU-Standard-A" },
  tr: { languageCode: "tr-TR", name: "tr-TR-Standard-A" },
  nl: { languageCode: "nl-NL", name: "nl-NL-Standard-A" },
  pl: { languageCode: "pl-PL", name: "pl-PL-Standard-A" },
  sv: { languageCode: "sv-SE", name: "sv-SE-Standard-A" },
  da: { languageCode: "da-DK", name: "da-DK-Standard-A" },
  fi: { languageCode: "fi-FI", name: "fi-FI-Standard-A" },
  no: { languageCode: "nb-NO", name: "nb-NO-Standard-A" },
  id: { languageCode: "id-ID", name: "id-ID-Standard-A" },
  ms: { languageCode: "ms-MY", name: "ms-MY-Standard-A" },
  th: { languageCode: "th-TH", name: "th-TH-Standard-A" },
  vi: { languageCode: "vi-VN", name: "vi-VN-Standard-A" },
  en: { languageCode: "en-US", name: "en-US-Standard-C" },
};

const DEFAULT_VOICE = { languageCode: "en-US", name: "en-US-Standard-C" };

function resolveKey(explicitKey) {
  if (explicitKey) return explicitKey;
  const env = process.env.GOOGLE_TTS_API_KEY || process.env.GOOGLE_CLOUD_API_KEY;
  if (env) return env;
  try {
    const functions = require("firebase-functions");
    return functions.config().google?.tts_api_key || functions.config().google?.api_key;
  } catch {
    return null;
  }
}

/**
 * synthesizeSpeech({ text, targetLanguage, model?, apiKey?, providerId? })
 *   → { audioContent (base64 MP3), mimeType, voice, model, latencyMs, providerId }
 *
 * `targetLanguage` (e.g. "hi", "hi-Latn", "en") is resolved to a Cloud TTS voice via
 * ttsLangMap. If `languageCode`+`voice` are passed directly instead, they take priority.
 */
async function synthesizeSpeech(args) {
  const { text, targetLanguage, languageCode, voice, model = "google_tts", apiKey, providerId } = args;

  // Resolve voice: explicit override, else targetLanguage lookup, else default.
  let voiceCfg = DEFAULT_VOICE;
  if (languageCode && voice) {
    voiceCfg = { languageCode, name: voice };
  } else if (targetLanguage && ttsLangMap[targetLanguage]) {
    voiceCfg = ttsLangMap[targetLanguage];
  }

  const key = resolveKey(apiKey);
  if (!key) {
    throw new Error("Google TTS API key not configured");
  }

  const ttsRequestBody = {
    input: { text },
    voice: {
      languageCode: voiceCfg.languageCode,
      name: voiceCfg.name,
    },
    audioConfig: {
      audioEncoding: "MP3",
    },
  };

  const t0 = Date.now();
  const response = await fetch(`${TTS_URL}?key=${key}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(ttsRequestBody),
  });

  if (!response.ok) {
    const errorText = await response.text();
    console.error("❌ Cloud TTS API ERROR ❌", response.status, response.statusText, errorText);
    throw new Error(`TTS API error: ${response.status} ${response.statusText}`);
  }

  const ttsResult = await response.json();
  const audioContent = ttsResult.audioContent; // already base64 MP3

  if (!audioContent) {
    console.error("❌ NO AUDIO CONTENT IN TTS RESPONSE ❌");
    throw new Error("No audio content received from TTS API");
  }

  return normalizeTts({
    audioContent,
    mimeType: "audio/mpeg",
    voice: voiceCfg.name,
    model,
    latencyMs: Date.now() - t0,
    providerId,
  });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, capability = "tts" } = args;
  const key = resolveKey(apiKey);
  if (!key) {
    return { ok: false, capability, detail: "Google TTS API key not configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(`${TTS_URL}?key=${key}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        input: { text: "Hello" },
        voice: { languageCode: "en-US", name: "en-US-Standard-C" },
        audioConfig: { audioEncoding: "MP3" },
      }),
    });
    const latencyMs = Date.now() - t0;
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    const data = await res.json();
    if (!data?.audioContent) {
      return { ok: false, capability, latencyMs, detail: "No audio returned" };
    }
    return { ok: true, capability, latencyMs, detail: "Connected to Google TTS" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = { synthesizeSpeech, testConnection, ttsLangMap };
