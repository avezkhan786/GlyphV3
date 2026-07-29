/**
 * deepl.js — DeepL translation provider module (M3).
 *
 * Implements: translate, testConnection.
 *
 * Alternate translation provider. When an admin switches the `translation`
 * capability to DeepL, router.translate dispatches here. Returns the canonical
 * { translatedText, model, latencyMs } shape (invariant §13.3 — same contract
 * as gemini.translate, so translate.js is unchanged).
 *
 * NOTE (limitation): DeepL returns Devanagari Hindi, not romanized Hinglish.
 * The Hinglish (hi-Latn) special-casing from §13.3 #7 is Gemini-specific; when
 * translation is routed to DeepL, hi-Latn cannot be romanized. See §15.
 *
 * No Firebase dependency — runs purely on a passed config + global fetch.
 */

const { normalizeTranslate } = require("./normalize");

const DEFAULT_BASE = "https://api-free.deepl.com";

function resolveBase(baseUrl) {
  return (baseUrl || DEFAULT_BASE).replace(/\/$/, "");
}

// Map the app's BCP-47-ish target codes → DeepL `target_lang` codes.
function toDeepLTarget(target) {
  if (!target) return "EN";
  const overrides = { "PT-BR": "PT-BR", "ZH-TW": "ZH-HANT" };
  if (overrides[target]) return overrides[target];
  return target.split("-")[0].toUpperCase();
}

// ─── translate ─────────────────────────────────────────────
async function translate(args) {
  const { text, targetLanguage, model, apiKey, providerId } = args;
  if (!apiKey) throw new Error("DeepL API key not configured");

  const base = resolveBase(args.baseUrl);
  const targetLang = toDeepLTarget(targetLanguage);

  const t0 = Date.now();
  const response = await fetch(`${base}/v2/translate`, {
    method: "POST",
    headers: { Authorization: `DeepL-Auth-Key ${apiKey}`, "Content-Type": "application/json" },
    body: JSON.stringify({ text: [text], target_lang: targetLang }),
  });

  if (!response.ok) {
    const err = await response.text();
    console.error("DeepL translate error:", response.status, err);
    throw new Error(`DeepL translate error: ${response.status}`);
  }

  const data = await response.json();
  const translatedText = data?.translations?.[0]?.text;
  if (!translatedText) throw new Error("Empty DeepL response");

  return normalizeTranslate({ translatedText, model, latencyMs: Date.now() - t0, providerId });
}

// ─── testConnection ────────────────────────────────────────
async function testConnection(args = {}) {
  const { apiKey, baseUrl, capability = "translation" } = args;
  if (!apiKey) {
    return { ok: false, capability, detail: "No API key configured", latencyMs: 0 };
  }
  const base = resolveBase(baseUrl);
  const t0 = Date.now();
  try {
    const res = await fetch(`${base}/v2/usage`, {
      headers: { Authorization: `DeepL-Auth-Key ${apiKey}` },
    });
    const latencyMs = Date.now() - t0;
    if (!res.ok) return { ok: false, capability, latencyMs, detail: `HTTP ${res.status}` };
    return { ok: true, capability, latencyMs, detail: "Connected to DeepL" };
  } catch (e) {
    return {
      ok: false,
      capability,
      latencyMs: Date.now() - t0,
      detail: (e && e.message) || "Network error",
    };
  }
}

module.exports = { translate, testConnection };
