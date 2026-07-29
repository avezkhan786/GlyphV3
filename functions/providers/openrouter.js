/**
 * openrouter.js — OpenRouter provider module.
 *
 * Implements: generateText, translate, speechToText, testConnection.
 *
 * OpenRouter exposes an OpenAI-compatible chat/completions API (Bearer auth).
 * It also forwards AUDIO input to multimodal models via the OpenAI `input_audio`
 * content part. This was verified (M5 OpenRouter STT gate) against
 * `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free`: the audio request is
 * ACCEPTED — OpenRouter returns HTTP 402 ("requires at least $0.50 in balance
 * for audio") rather than a modality/schema rejection, i.e. the audio path works;
 * the only gate is a small prepaid balance floor (even on :free models).
 *
 * It does NOT implement synthesizeSpeech / ocr / generateImage (OpenRouter has no
 * native TTS/OCR/image-gen endpoint), so it is registered for llm / translation
 * / stt. Hinglish (hi-Latn) romanization is Gemini-only (same limitation as
 * DeepSeek/OpenAI — see HANDOFF §15.4); OpenRouter returns Devanagari.
 *
 * No Firebase dependency — runs purely on a passed config + global fetch, so it is
 * admin-safe to require (health.js / test route).
 */

const { normalizeGenerateText, normalizeTranslate, normalizeSpeech } = require("./normalize");

const DEFAULT_API = "https://openrouter.ai/api/v1";
// Audio-capable model this provider is seeded for (OpenRouter STT gate, M5).
const DEFAULT_AUDIO_MODEL = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free";
const DEFAULT_CHAT_MODEL = "openai/gpt-3.5-turbo";

function apiRoot(baseUrl) {
  if (baseUrl && baseUrl.trim()) {
    const u = baseUrl.trim().replace(/\/+$/, "");
    return u.endsWith("/v1") ? u : `${u}/v1`;
  }
  return DEFAULT_API;
}

function chatUrl(baseUrl) {
  return `${apiRoot(baseUrl)}/chat/completions`;
}

function authHeaders(apiKey) {
  if (!apiKey) throw new Error("OpenRouter API key not configured");
  // HTTP-Referer / X-Title are OpenRouter-recommended (optional) attribution headers.
  return {
    Authorization: `Bearer ${apiKey}`,
    "Content-Type": "application/json",
    "HTTP-Referer": "https://glyph.app",
    "X-Title": "Glyph",
  };
}

// Map an Android/CF mimeType → the OpenAI/OpenRouter audio "format" hint.
function audioFormat(mimeType) {
  const m = (mimeType || "").toLowerCase();
  if (m.includes("wav")) return "wav";
  if (m.includes("mp3") || m.includes("mpeg")) return "mp3";
  if (m.includes("mp4") || m.includes("aac") || m.includes("m4a")) return "mp4";
  if (m.includes("webm")) return "webm";
  if (m.includes("ogg") || m.includes("opus")) return "ogg";
  return "wav"; // safe default
}

// Gemini-shaped contents → OpenAI-style chat messages (first "user" part is the
// embedded system prompt, per AI_PROVIDER_HANDOFF.md §8 pragmatic deviation).
function contentsToMessages(contents) {
  const messages = [];
  let systemText = null;
  (Array.isArray(contents) ? contents : []).forEach((c, i) => {
    const text = (Array.isArray(c?.parts) ? c.parts : [])
      .map((p) => p?.text || "")
      .join("\n")
      .trim();
    if (!text) return;
    if (i === 0 && c.role === "user") {
      systemText = text;
      return;
    }
    const role = c.role === "model" ? "assistant" : "user";
    messages.push({ role, content: text });
  });
  return { system: systemText, messages };
}

// ─── generateText ─────────────────────────────────────
async function generateText(args) {
  const { contents, model = DEFAULT_CHAT_MODEL, generationConfig = {}, apiKey, baseUrl, providerId } = args;
  const { system, messages } = contentsToMessages(contents);

  const body = {
    model,
    messages: system ? [{ role: "system", content: system }, ...messages] : messages,
    temperature: generationConfig.temperature ?? 0.7,
    max_tokens: generationConfig.maxOutputTokens ?? 1024,
  };

  const t0 = Date.now();
  const res = await fetch(chatUrl(baseUrl), {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenRouter chat error:", res.status, e);
    throw new Error(`OpenRouter error: ${res.status}`);
  }
  const data = await res.json();
  const text = data?.choices?.[0]?.message?.content?.trim();
  if (!text) throw new Error("Empty OpenRouter response");

  return normalizeGenerateText({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── translate ────────────────────────────────────────
async function translate(args) {
  const { text, targetLanguage, model = DEFAULT_CHAT_MODEL, apiKey, baseUrl, providerId } = args;
  const messages = [
    {
      role: "system",
      content: `You are a translator. Translate the user's text into ${targetLanguage}. Reply with ONLY the translated text, no commentary.`,
    },
    { role: "user", content: text },
  ];

  const t0 = Date.now();
  const res = await fetch(chatUrl(baseUrl), {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify({ model, messages, temperature: 0.3, max_tokens: 1024 }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenRouter translate error:", res.status, e);
    throw new Error(`OpenRouter error: ${res.status}`);
  }
  const data = await res.json();
  const translatedText = data?.choices?.[0]?.message?.content?.trim();
  if (!translatedText) throw new Error("Empty OpenRouter response");

  return normalizeTranslate({ translatedText, model, latencyMs: Date.now() - t0, providerId });
}

// ─── speechToText ────────────────────────────────────
async function speechToText(args) {
  const { audioBase64, mimeType, languageHint, model = DEFAULT_AUDIO_MODEL, apiKey, providerId } = args;
  if (!apiKey) throw new Error("OpenRouter API key not configured");
  if (!audioBase64) throw new Error("OpenRouter STT: no audio provided");

  const prompt =
    (languageHint ? `The spoken language is ${languageHint}.\n` : "") +
    "Transcribe the audio clip verbatim. If there is no speech, reply with the single token [inaudible].";

  const messages = [
    {
      role: "user",
      content: [
        { type: "text", text: prompt },
        { type: "input_audio", input_audio: { data: audioBase64, format: audioFormat(mimeType) } },
      ],
    },
  ];

  const t0 = Date.now();
  const res = await fetch(chatUrl(), {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify({ model, messages, temperature: 0 }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenRouter STT error:", res.status, e);
    throw new Error(`OpenRouter STT error: ${res.status}`);
  }
  const data = await res.json();
  const text = data?.choices?.[0]?.message?.content?.trim() || "[inaudible]";

  return normalizeSpeech({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── testConnection ───────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, model = DEFAULT_CHAT_MODEL, capability = "llm", baseUrl } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(chatUrl(baseUrl), {
      method: "POST",
      headers: authHeaders(apiKey),
      body: JSON.stringify({
        model,
        messages: [{ role: "user", content: "Reply with the single word: OK" }],
        temperature: 0,
        max_tokens: 5,
      }),
    });
    const latencyMs = Date.now() - t0;
    if (!res.ok) {
      const e = await res.text();
      return { ok: false, capability, latencyMs, detail: `HTTP ${res.status} ${e.slice(0, 200)}` };
    }
    return { ok: true, capability, latencyMs, detail: "Connected to OpenRouter" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = {
  generateText,
  translate,
  speechToText,
  testConnection,
};
