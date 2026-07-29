/**
 * openai.js — OpenAI provider module (M3).
 *
 * Implements: generateText, translate, synthesizeSpeech, speechToText,
 *            generateImage, testConnection.
 *
 * OpenAI is the seeded default `image` provider (DEFAULT_ROUTING.image → openai)
 * and an alternate for llm / tts / stt. It is the only module that implements
 * generateImage (gemini.js intentionally does not — §13.4).
 *
 * generateText / translate translate the Gemini-shaped `contents` array into
 * OpenAI's chat format. Per AI_PROVIDER_HANDOFF.md §8's pragmatic deviation,
 * the FIRST "user" part of `contents` is the embedded system prompt, so it is
 * mapped to OpenAI's `system` role; subsequent parts follow as user/assistant.
 *
 * No Firebase dependency — runs purely on a passed config + global fetch.
 */

const {
  normalizeGenerateText,
  normalizeTranslate,
  normalizeSpeech,
  normalizeTts,
} = require("./normalize");

const API = "https://api.openai.com/v1";

function authHeaders(apiKey) {
  if (!apiKey) throw new Error("OpenAI API key not configured");
  return { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" };
}

// Gemini-shaped contents → OpenAI chat messages.
function contentsToOpenAiMessages(contents) {
  const messages = [];
  let systemText = null;
  (Array.isArray(contents) ? contents : []).forEach((c, i) => {
    const text = (Array.isArray(c?.parts) ? c.parts : [])
      .map((p) => p?.text || "")
      .join("\n")
      .trim();
    if (!text) return;
    if (i === 0 && c.role === "user") {
      systemText = text; // system prompt embedded as first user part
      return;
    }
    const role = c.role === "model" ? "assistant" : "user";
    messages.push({ role, content: text });
  });
  return { system: systemText, messages };
}

// ─── generateText ──────────────────────────────────────────
async function generateText(args) {
  const { contents, model = "gpt-4o-mini", generationConfig = {}, apiKey, providerId } = args;
  const headers = authHeaders(apiKey);
  const { system, messages } = contentsToOpenAiMessages(contents);

  const body = {
    model,
    messages: system ? [{ role: "system", content: system }, ...messages] : messages,
    temperature: generationConfig.temperature ?? 0.7,
    max_tokens: generationConfig.maxOutputTokens ?? 1024,
  };

  const t0 = Date.now();
  const res = await fetch(`${API}/chat/completions`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenAI chat error:", res.status, e);
    throw new Error(`OpenAI error: ${res.status}`);
  }
  const data = await res.json();
  const text = data?.choices?.[0]?.message?.content?.trim();
  if (!text) throw new Error("Empty OpenAI response");

  return normalizeGenerateText({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── translate ─────────────────────────────────────────────
async function translate(args) {
  const { text, targetLanguage, model = "gpt-4o-mini", apiKey, providerId } = args;
  const headers = authHeaders(apiKey);
  const messages = [
    {
      role: "system",
      content: `You are a translator. Translate the user's text into ${targetLanguage}. Reply with ONLY the translated text, no commentary.`,
    },
    { role: "user", content: text },
  ];

  const t0 = Date.now();
  const res = await fetch(`${API}/chat/completions`, {
    method: "POST",
    headers,
    body: JSON.stringify({ model, messages, temperature: 0.3, max_tokens: 1024 }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenAI translate error:", res.status, e);
    throw new Error(`OpenAI error: ${res.status}`);
  }
  const data = await res.json();
  const translatedText = data?.choices?.[0]?.message?.content?.trim();
  if (!translatedText) throw new Error("Empty OpenAI response");

  return normalizeTranslate({ translatedText, model, latencyMs: Date.now() - t0, providerId });
}

// ─── synthesizeSpeech ──────────────────────────────────────
async function synthesizeSpeech(args) {
  const { text, voice, model = "tts-1", apiKey, providerId } = args;
  const headers = authHeaders(apiKey);
  const voiceId = voice || "alloy";

  const t0 = Date.now();
  const res = await fetch(`${API}/audio/speech`, {
    method: "POST",
    headers,
    body: JSON.stringify({ model, voice: voiceId, input: text, response_format: "mp3" }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenAI TTS error:", res.status, e);
    throw new Error(`OpenAI TTS error: ${res.status}`);
  }
  const buf = Buffer.from(await res.arrayBuffer());
  const audioContent = buf.toString("base64");
  if (!audioContent) throw new Error("No audio content from OpenAI");

  return normalizeTts({
    audioContent,
    mimeType: "audio/mpeg",
    voice: voiceId,
    model,
    latencyMs: Date.now() - t0,
    providerId,
  });
}

// ─── speechToText ──────────────────────────────────────────
async function speechToText(args) {
  const { audioBase64, mimeType, languageHint, model = "whisper-1", apiKey, providerId } = args;
  if (!apiKey) throw new Error("OpenAI API key not configured");

  const t0 = Date.now();
  const blob = new Blob([Buffer.from(audioBase64, "base64")], {
    type: mimeType || "audio/mpeg",
  });
  const form = new FormData();
  form.append("file", blob, "audio");
  form.append("model", model);
  if (languageHint) form.append("language", languageHint.split("-")[0].toLowerCase());

  const res = await fetch(`${API}/audio/transcriptions`, {
    method: "POST",
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenAI STT error:", res.status, e);
    throw new Error(`OpenAI STT error: ${res.status}`);
  }
  const data = await res.json();
  const text = data?.text?.trim() || "[inaudible]";

  return normalizeSpeech({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── generateImage ─────────────────────────────────────────
async function generateImage(args) {
  const { prompt, model = "dall-e-3", size = "1024x1024", n = 1, apiKey, providerId } = args;
  if (!apiKey) throw new Error("OpenAI API key not configured");

  const t0 = Date.now();
  const res = await fetch(`${API}/images/generations`, {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify({ model, prompt, n, size, response_format: "b64_json" }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("OpenAI image error:", res.status, e);
    throw new Error(`OpenAI image error: ${res.status}`);
  }
  const data = await res.json();
  const imageBase64 = data?.data?.[0]?.b64_json;
  if (!imageBase64) throw new Error("No image returned from OpenAI");

  return { imageBase64, imageUrl: null, model, latencyMs: Date.now() - t0, providerId };
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, model = "gpt-4o-mini", capability = "llm" } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(`${API}/chat/completions`, {
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
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    return { ok: true, capability, latencyMs, detail: "Connected to OpenAI" };
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
  synthesizeSpeech,
  speechToText,
  generateImage,
  testConnection,
};
