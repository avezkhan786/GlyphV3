/**
 * metrics.js — Provider usage metrics (M4: recording enabled).
 *
 * recordMetric({ provider, model, capability, success, latencyMs, tokensOrChars?, estCost? })
 *   → Promise<void> (resolves regardless of outcome, never throws).
 *
 * Writes a row into `provider_metrics` on every AI call when ENABLED is true (flipped
 * by router.js at module load). It is best-effort: any Firestore failure is swallowed so
 * a metric write can never crash a provider call or a Cloud Function.
 */

const admin = require("firebase-admin");

let ENABLED = false; // flipped to true by router.js once M4 wiring is in place

// ─── Cost heuristic (M4) ─────────────────────────────────
// Crude USD-per-1k-units approximations for dashboards ONLY — NOT billing-grade.
// `tokensOrChars` is an approximate token/char count supplied by the caller.
// Keep these deliberately rough; refine per real provider pricing later if needed.
const RATE_PER_1K = {
  gemini: 0.00025, // gemini-2.5-flash-ish, per 1k output chars
  google_tts: 0.000015, // per 1k input chars
  elevenlabs: 0.00003, // per 1k input chars (ignores voice tier)
  deepl: 0.00002, // per 1k chars (free tier ~0)
  openai: 0.00015, // gpt-4o-mini-ish, per 1k tokens
  anthropic: 0.00025, // haiku-ish, per 1k tokens
  legacy_gemini: 0.00025,
  legacy_google_tts: 0.000015,
};

function estimateCost(providerId, tokensOrChars) {
  if (!tokensOrChars || tokensOrChars <= 0) return 0;
  const rate = RATE_PER_1K[providerId] || 0;
  if (!rate) return 0;
  // Round to 6 dp to avoid float noise while keeping small values meaningful.
  return Math.round(rate * (tokensOrChars / 1000) * 1e6) / 1e6;
}

async function recordMetric(metric) {
  if (!ENABLED || !metric) return;
  try {
    await admin.firestore().collection("provider_metrics").add({
      provider: metric.provider || null,
      model: metric.model || null,
      capability: metric.capability || null,
      success: !!metric.success,
      latencyMs: typeof metric.latencyMs === "number" ? metric.latencyMs : 0,
      tokensOrChars: metric.tokensOrChars || 0,
      estCost: metric.estCost || 0,
      timestamp: admin.firestore.FieldValue.serverTimestamp(),
    });
  } catch (e) {
    // best-effort only — never surface metric failures to the caller
  }
}

module.exports = {
  recordMetric,
  estimateCost,
  setEnabled: (v) => {
    ENABLED = !!v;
  },
  isEnabled: () => ENABLED,
};
