/**
 * deepseek.js — DeepSeek provider module.
 *
 * Implements: generateText, translate, testConnection.
 *
 * DeepSeek's API is OpenAI-compatible (chat/completions, Bearer auth), so this
 * reuses the same request shape as openai.js with a different base URL and default
 * model. It does NOT implement TTS/STT/OCR/image (DeepSeek has no such endpoints),
 * so it is only registered for the llm / translation capabilities.
 *
 * An optional `baseUrl` lets the same module target a DeepSeek-compatible proxy.
 *
 * No Firebase dependency — runs purely on a passed config + global fetch, so it is
 * admin-safe to require (health.js / test route).
 */

const { normalizeGenerateText, normalizeTranslate } = require("./normalize");

const DEFAULT_API = "https://api.deepseek.com/v1";

function apiRoot(baseUrl) {
  if (baseUrl && baseUrl.trim()) {
    const u = baseUrl.trim().replace(/\/+$/, "");
    return u.endsWith("/v1") ? u : `${u}/v1`;
  }
  return DEFAULT_API;
}

function authHeaders(apiKey) {
  if (!apiKey) throw new Error("DeepSeek API key not configured");
  return { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" };
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

// ─── generateText ──────────────────────────────────────────
async function generateText(args) {
  const { contents, model = "deepseek-chat", generationConfig = {}, apiKey, baseUrl, providerId } = args;
  const { system, messages } = contentsToMessages(contents);

  const body = {
    model,
    messages: system ? [{ role: "system", content: system }, ...messages] : messages,
    temperature: generationConfig.temperature ?? 0.7,
    max_tokens: generationConfig.maxOutputTokens ?? 1024,
  };

  const t0 = Date.now();
  const res = await fetch(`${apiRoot(baseUrl)}/chat/completions`, {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("DeepSeek chat error:", res.status, e);
    throw new Error(`DeepSeek error: ${res.status}`);
  }
  const data = await res.json();
  const text = data?.choices?.[0]?.message?.content?.trim();
  if (!text) throw new Error("Empty DeepSeek response");

  return normalizeGenerateText({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── translate ─────────────────────────────────────────────
async function translate(args) {
  const { text, targetLanguage, model = "deepseek-chat", apiKey, baseUrl, providerId } = args;
  const messages = [
    {
      role: "system",
      content: `You are a translator. Translate the user's text into ${targetLanguage}. Reply with ONLY the translated text, no commentary.`,
    },
    { role: "user", content: text },
  ];

  const t0 = Date.now();
  const res = await fetch(`${apiRoot(baseUrl)}/chat/completions`, {
    method: "POST",
    headers: authHeaders(apiKey),
    body: JSON.stringify({ model, messages, temperature: 0.3, max_tokens: 1024 }),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("DeepSeek translate error:", res.status, e);
    throw new Error(`DeepSeek error: ${res.status}`);
  }
  const data = await res.json();
  const translatedText = data?.choices?.[0]?.message?.content?.trim();
  if (!translatedText) throw new Error("Empty DeepSeek response");

  return normalizeTranslate({ translatedText, model, latencyMs: Date.now() - t0, providerId });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, model = "deepseek-chat", capability = "llm", baseUrl } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(`${apiRoot(baseUrl)}/chat/completions`, {
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
    return { ok: true, capability, latencyMs, detail: "Connected to DeepSeek" };
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
  testConnection,
};
