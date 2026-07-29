/**
 * anthropic.js — Anthropic Claude provider module (M3).
 *
 * Implements: generateText, testConnection.
 *
 * Alternate LLM provider. When an admin switches the `llm` capability to
 * Anthropic, router.generateText dispatches here. Translates the Gemini-shaped
 * `contents` array into Anthropic's messages API (first user part → `system`,
 * per §8 pragmatic deviation). Returns the canonical { text, model, latencyMs }.
 *
 * No Firebase dependency — runs purely on a passed config + global fetch.
 */

const { normalizeGenerateText } = require("./normalize");

const API = "https://api.anthropic.com/v1";
const ANTHROPIC_VERSION = "2023-06-01";

function authHeaders(apiKey) {
  if (!apiKey) throw new Error("Anthropic API key not configured");
  return {
    "x-api-key": apiKey,
    "anthropic-version": ANTHROPIC_VERSION,
    "Content-Type": "application/json",
  };
}

function contentsToAnthropic(contents) {
  let system = null;
  const messages = [];
  (Array.isArray(contents) ? contents : []).forEach((c, i) => {
    const text = (Array.isArray(c?.parts) ? c.parts : [])
      .map((p) => p?.text || "")
      .join("\n")
      .trim();
    if (!text) return;
    if (i === 0 && c.role === "user") {
      system = text; // system prompt embedded as first user part
      return;
    }
    const role = c.role === "model" ? "assistant" : "user";
    messages.push({ role, content: text });
  });
  return { system, messages };
}

// ─── generateText ──────────────────────────────────────────
async function generateText(args) {
  const { contents, model = "claude-3-5-haiku-latest", generationConfig = {}, apiKey, providerId } =
    args;
  const headers = authHeaders(apiKey);
  const { system, messages } = contentsToAnthropic(contents);

  const body = {
    model,
    max_tokens: generationConfig.maxOutputTokens ?? 1024,
    ...(system ? { system } : {}),
    messages,
  };

  const t0 = Date.now();
  const res = await fetch(`${API}/messages`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });
  if (!res.ok) {
    const e = await res.text();
    console.error("Anthropic error:", res.status, e);
    throw new Error(`Anthropic error: ${res.status}`);
  }
  const data = await res.json();
  const text = (data?.content || [])
    .map((b) => b?.text || "")
    .join("")
    .trim();
  if (!text) throw new Error("Empty Anthropic response");

  return normalizeGenerateText({ text, model, latencyMs: Date.now() - t0, providerId });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, model = "claude-3-5-haiku-latest", capability = "llm" } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const t0 = Date.now();
  try {
    const res = await fetch(`${API}/messages`, {
      method: "POST",
      headers: authHeaders(apiKey),
      body: JSON.stringify({
        model,
        max_tokens: 16,
        messages: [{ role: "user", content: "Reply with the single word: OK" }],
      }),
    });
    const latencyMs = Date.now() - t0;
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    return { ok: true, capability, latencyMs, detail: "Connected to Anthropic" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = { generateText, testConnection };
