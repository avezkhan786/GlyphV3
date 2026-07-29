/**
 * router.js — The provider router (M2).
 *
 * Resolves the active provider for each AI capability from the Firestore snapshot
 * (ai_settings + api_providers) and dispatches to the matching provider module.
 *
 * Routing key:  ai_settings/{capability}.activeProvider → provider doc id.
 * Failover:     if the active provider is disabled/missing/unhealthy, pick the next
 *               enabled provider of that capability by `priority` (lower = first).
 * Legacy fallback: if no providers are seeded (collection empty / unset), route through
 *               the existing hardcoded keys
 *                 LLM/translation/stt/ocr → process.env.GOOGLE_CLOUD_API_KEY ||
 *                                           functions.config().google?.api_key
 *                 tts → GOOGLE_TTS_API_KEY || tts_api_key || gemini key
 *               using the gemini / google_tts modules directly. This guarantees zero
 *               regression before seeding and on a fresh deploy (invariant §13.3 #9:
 *               functions.config() is only ever read lazily, inside the key resolvers).
 *
 * M4 dispatch: withFailover builds an ordered candidate chain (active provider first,
 * then remaining enabled providers of that capability by priority) and walks it on
 * error, recording a metric on every attempt. When no providers are seeded it falls
 * back to the legacy hardcoded path as the last resort.
 */

const functions = require("firebase-functions");
const { getSnapshot } = require("./cache");
const metrics = require("./metrics");
const gemini = require("./gemini");
const googleTts = require("./google_tts");
const elevenlabs = require("./elevenlabs");
const deepl = require("./deepl");
const openai = require("./openai");
const anthropic = require("./anthropic");
const deepseek = require("./deepseek");
const openrouter = require("./openrouter");

// M4: metrics recording is now live. The admin portal never requires router.js
// (it requires the individual provider modules directly), so flipping this here
// only affects the Cloud Functions runtime.
metrics.setEnabled(true);

// Module registry keyed by provider doc id. The seeded provider ids (gemini,
// google_tts) match these filenames by design; M3 appends elevenlabs / deepl /
// openai / anthropic. Each module implements the §8 interface for the capabilities
// it serves; the router dispatches by the active provider's capability.
const MODULES = {
  gemini: gemini,
  google_tts: googleTts,
  elevenlabs: elevenlabs,
  deepl: deepl,
  openai: openai,
  anthropic: anthropic,
  deepseek: deepseek,
  openrouter: openrouter,
};

const DEFAULT_GEMINI_MODEL = "gemini-2.5-flash";

// Lazy key resolvers — mirror the legacy inline lookups and never run at module load.
function legacyKey() {
  return process.env.GOOGLE_CLOUD_API_KEY || functions.config().google?.api_key;
}
function legacyTtsKey() {
  return (
    process.env.GOOGLE_TTS_API_KEY ||
    functions.config().google?.tts_api_key ||
    legacyKey()
  );
}

/**
 * Resolve the active provider for a capability.
 * Returns { id, doc } or null (no provider available).
 */
function selectProvider(capability, snapshot) {
  if (!snapshot) return null;

  const setting = snapshot.settings.get(capability);
  if (setting && setting.activeProvider) {
    const doc = snapshot.providers.get(setting.activeProvider);
    if (
      doc &&
      doc.enabled &&
      Array.isArray(doc.capabilities) &&
      doc.capabilities.includes(capability)
    ) {
      return { id: doc.id, doc };
    }
  }

  // Active provider unavailable — fall back to the next enabled provider by priority.
  const candidates = [];
  snapshot.providers.forEach((doc) => {
    if (
      doc.enabled &&
      Array.isArray(doc.capabilities) &&
      doc.capabilities.includes(capability)
    ) {
      candidates.push(doc);
    }
  });
  candidates.sort(
    (a, b) => (a.priority != null ? a.priority : 999) - (b.priority != null ? b.priority : 999)
  );
  if (candidates.length) {
    return { id: candidates[0].id, doc: candidates[0] };
  }
  return null;
}

function legacyCall(capability) {
  if (capability === "tts") {
    return {
      mod: googleTts,
      doc: {
        id: "legacy_google_tts",
        apiKey: legacyTtsKey(),
        defaultModel: "google_tts", // google_tts ignores model for the call
        enabled: true,
        capabilities: ["tts"],
      },
    };
  }
  // llm, translation, stt, ocr → gemini (legacy key)
  return {
    mod: gemini,
    doc: {
      id: "legacy_gemini",
      apiKey: legacyKey(),
      defaultModel: DEFAULT_GEMINI_MODEL,
      enabled: true,
      capabilities: [capability],
    },
  };
}

/**
 * Build the ordered failover chain for a capability.
 *  - The active provider (ai_settings[cap].activeProvider) is tried first, if it is
 *    enabled, advertises the capability, and has a backing module.
 *  - Then every other enabled provider of that capability, sorted by priority asc.
 *  - If nothing is seeded/usable, append the legacy hardcoded fallback as the last
 *    resort. legacyCall() reads functions.config() lazily (request-time only), never
 *    at module load (invariant §13.3 #9).
 */
function buildChain(capability, snapshot) {
  const chain = [];
  if (snapshot && snapshot.providers) {
    const setting = snapshot.settings.get(capability);
    const activeId = setting && setting.activeProvider;

    if (activeId) {
      const active = snapshot.providers.get(activeId);
      if (isUsable(active, capability)) {
        chain.push({ id: active.id, doc: active, mod: moduleOf(active) });
      }
    }

    const rest = [];
    snapshot.providers.forEach((doc) => {
      if (doc.id === activeId) return; // already considered as active
      if (isUsable(doc, capability)) rest.push(doc);
    });
    rest.sort((a, b) => priorityOf(a) - priorityOf(b));
    rest.forEach((doc) => chain.push({ id: doc.id, doc, mod: moduleOf(doc) }));
  }

  if (chain.length === 0) {
    const legacy = legacyCall(capability); // lazily reads functions.config()
    if (legacy) chain.push({ id: legacy.doc.id, doc: legacy.doc, mod: legacy.mod });
  }
  return chain;
}

function isUsable(doc, capability) {
  return (
    !!doc &&
    doc.enabled &&
    Array.isArray(doc.capabilities) &&
    doc.capabilities.includes(capability) &&
    !!moduleOf(doc)
  );
}

function priorityOf(doc) {
  return doc.priority != null ? doc.priority : 999;
}

// Resolve the backing module for a provider doc. A custom provider can name its
// implementation module via `doc.module` (filename without .js); otherwise we fall
// back to the provider's doc id (the convention used by the six seeded providers,
// whose ids match their module filenames). This lets the admin's "Add provider" UI
// wire arbitrary providers to an existing module.
function moduleOf(doc) {
  if (!doc) return undefined;
  return MODULES[doc.module || doc.id];
}

// Approximate token/char count from a normalized result, for metrics.estCost.
function tokenish(result) {
  if (!result || typeof result !== "object") return 0;
  if (typeof result.text === "string") return result.text.length;
  if (typeof result.translatedText === "string") return result.translatedText.length;
  if (typeof result.audioContent === "string") {
    // base64 → ~0.75 bytes/char
    return Math.round(result.audioContent.length * 0.75);
  }
  return 0;
}

/**
 * Core dispatch with true multi-provider failover (M4).
 *
 * Walks the candidate chain built from the snapshot. On a thrown error from a provider
 * it logs + records a metric and falls through to the next candidate by priority. Real
 * errors are never silently masked: when the chain is exhausted we rethrow the last
 * error. Each attempt records a metric via metrics.recordMetric. The successful
 * providerId is stamped onto the result so callers can fold it into cache keys.
 *
 * The legacy fallback is only present in the chain when no seeded provider is usable
 * (invariant: legacy is the last resort when nothing is seeded).
 */
async function withFailover(capability, callFn) {
  const snapshot = await getSnapshot();
  const chain = buildChain(capability, snapshot);

  let lastErr;
  for (let i = 0; i < chain.length; i++) {
    const { id, doc, mod } = chain[i];
    const t0 = Date.now();
    try {
      const result = await callFn(mod, doc);
      const latencyMs = Date.now() - t0;
      if (result && typeof result === "object") result.providerId = id;

      const tokensOrChars = tokenish(result);
      await metrics.recordMetric({
        provider: id,
        model: (result && result.model) || doc.defaultModel || null,
        capability,
        success: true,
        latencyMs,
        tokensOrChars,
        estCost: metrics.estimateCost(id, tokensOrChars),
      });

      if (i > 0) {
        console.warn(
          `[provider-router] ${capability}: failover to "${id}" succeeded (attempt ${i + 1}/${chain.length}) in ${latencyMs}ms`
        );
      }
      return result;
    } catch (err) {
      const latencyMs = Date.now() - t0;
      lastErr = err;
      console.error(
        `[provider-router] ${capability}: provider "${id}" failed (attempt ${i + 1}/${chain.length}): ${
          err && err.message ? err.message : err
        }`
      );
      await metrics.recordMetric({
        provider: id,
        model: doc.defaultModel || null,
        capability,
        success: false,
        latencyMs,
        tokensOrChars: 0,
        estCost: 0,
      });
      // fall through to the next candidate
    }
  }

  throw lastErr || new Error(`No provider available for capability: ${capability}`);
}

// ─── Capability entry points ──────────────────────────────

async function generateText(args) {
  return withFailover("llm", (mod, doc) =>
    mod.generateText({
      contents: args.contents,
      model: doc.defaultModel || args.model || DEFAULT_GEMINI_MODEL,
      generationConfig: args.generationConfig,
      safetySettings: args.safetySettings,
      apiKey: doc.apiKey || undefined,
    })
  );
}

async function translate(args) {
  return withFailover("translation", (mod, doc) =>
    mod.translate({
      text: args.text,
      targetLanguage: args.targetLanguage,
      model: doc.defaultModel || args.model || DEFAULT_GEMINI_MODEL,
      apiKey: doc.apiKey || undefined,
    })
  );
}

async function synthesizeSpeech(args) {
  return withFailover("tts", (mod, doc) =>
    mod.synthesizeSpeech({
      text: args.text,
      targetLanguage: args.targetLanguage,
      languageCode: args.languageCode,
      voice: args.voice,
      model: doc.defaultModel || args.model || "google_tts",
      apiKey: doc.apiKey || undefined,
    })
  );
}

async function speechToText(args) {
  return withFailover("stt", (mod, doc) =>
    mod.speechToText({
      audioBase64: args.audioBase64,
      mimeType: args.mimeType,
      languageHint: args.languageHint,
      targetLanguage: args.targetLanguage,
      model: doc.defaultModel || args.model || DEFAULT_GEMINI_MODEL,
      apiKey: doc.apiKey || undefined,
    })
  );
}

async function ocr(args) {
  return withFailover("ocr", (mod, doc) =>
    mod.ocr({
      imageBase64: args.imageBase64,
      mimeType: args.mimeType,
      prompt: args.prompt,
      model: doc.defaultModel || args.model || DEFAULT_GEMINI_MODEL,
      apiKey: doc.apiKey || undefined,
    })
  );
}

async function generateImage(args) {
  return withFailover("image", (mod, doc) =>
    mod.generateImage({
      prompt: args.prompt,
      model: doc.defaultModel || args.model || "dall-e-3",
      size: args.size,
      n: args.n,
      apiKey: doc.apiKey || undefined,
    })
  );
}

/**
 * Resolve the active provider + effective model for a capability WITHOUT performing an
 * API call. Used by callers that must fold provider+model into a cache key before the
 * cache lookup. Returns { providerId, model }.
 */
async function getRouting(capability) {
  const snapshot = await getSnapshot();
  const selected = selectProvider(capability, snapshot);
  if (selected && moduleOf(selected.doc)) {
    return {
      providerId: selected.id,
      model: selected.doc.defaultModel || defaultFor(capability),
    };
  }
  const legacy = legacyCall(capability);
  return { providerId: legacy.doc.id, model: legacy.doc.defaultModel || defaultFor(capability) };
}

function defaultFor(capability) {
  return capability === "tts" ? "google_tts" : DEFAULT_GEMINI_MODEL;
}

module.exports = {
  getCapabilityProvider: selectProvider,
  selectProvider,
  buildChain,
  withFailover,
  generateText,
  translate,
  synthesizeSpeech,
  speechToText,
  ocr,
  generateImage,
  getRouting,
  MODULES,
};
